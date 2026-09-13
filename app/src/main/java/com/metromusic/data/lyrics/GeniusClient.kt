package com.metromusic.data.lyrics

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Lyrics from Genius, through the endpoints its own website uses.
 *
 * No API token: Genius' documented API needs one, and a token identifies an application, so it
 * cannot be shipped in a repo and would make the feature dead on arrival for anyone building this.
 * The site's own search endpoint answers unauthenticated, and the lyrics live in the page HTML, so
 * that is what this reads. The trade is fragility — a markup change breaks the extraction — which is
 * why every step fails soft and a failure is reported as "don't know" rather than "no lyrics".
 *
 * Everything here blocks; call it from IO.
 */
object GeniusClient {

    private const val Tag = "GeniusClient"

    /** Genius rejects the default Java agent outright. */
    private const val UserAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile"

    private const val TimeoutMs = 12_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Finds the song page for [artist] / [title] and returns its lyrics.
     *
     * The search is deliberately given both fields and the result checked against them: searching
     * for a common title like "Home" returns dozens of songs, and pasting the wrong band's lyrics
     * under a track is worse than showing none.
     *
     * Never synced, and that is structural rather than a gap: Genius publishes words, and there is no
     * timing information anywhere in one of its pages to extract. Timed words are what LRCLIB is for.
     */
    fun lyrics(artist: String, title: String): LyricsAnswer {
        val query = "${cleanArtist(artist)} ${cleanTitle(title)}".trim()
        if (query.isBlank()) return LyricsAnswer.NotFound

        val body = get(
            "https://genius.com/api/search/multi?q=" +
                URLEncoder.encode(query, "UTF-8")
        ) ?: return LyricsAnswer.Unavailable

        // Not JSON means we were not talking to the search endpoint at all — a captive portal, a
        // Cloudflare challenge, a proxy's error page. That is emphatically *not* "this song has no
        // lyrics": answering NotFound there would write a permanent "no" for every song in the
        // library on the strength of one bad network.
        val hits = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return LyricsAnswer.Unavailable

        val url = bestMatch(hits, artist, title) ?: return LyricsAnswer.NotFound
        val page = get(url) ?: return LyricsAnswer.Unavailable
        val text = extractLyrics(page)
        return when {
            !text.isNullOrBlank() -> LyricsAnswer.Found(text, synced = false)
            // A page that mentions Genius but has no lyrics container really has none; a page that
            // does not is something else wearing that URL.
            page.contains("genius", ignoreCase = true) -> LyricsAnswer.NotFound
            else -> LyricsAnswer.Unavailable
        }
    }

    /**
     * Picks the hit whose title and artist both look like what was asked for.
     *
     * Genius answers with several sections (songs, artists, albums, lyrics snippets); only the song
     * hits carry a page worth reading, and they arrive roughly in relevance order, so the first
     * plausible one wins.
     */
    private fun bestMatch(root: JsonObject, artist: String, title: String): String? {
        val wantedTitle = normalize(cleanTitle(title))
        val wantedArtist = normalize(cleanArtist(artist))
        if (wantedTitle.isEmpty()) return null

        val sections = root["response"]?.jsonObject?.get("sections") as? JsonArray ?: return null

        for (section in sections) {
            val hits = (section as? JsonObject)?.get("hits") as? JsonArray ?: continue
            for (hit in hits) {
                val result = (hit as? JsonObject)?.get("result")?.jsonObject ?: continue
                val url = result["url"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
                if (!url.endsWith("-lyrics")) continue

                val hitTitle = normalize(
                    result["title"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                )
                val hitArtist = normalize(
                    result["primary_artist"]?.jsonObject
                        ?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        .orEmpty()
                )
                val titleMatches = hitTitle.contains(wantedTitle) || wantedTitle.contains(hitTitle)
                val artistMatches = wantedArtist.isEmpty() ||
                    hitArtist.contains(wantedArtist) ||
                    wantedArtist.contains(hitArtist)
                if (titleMatches && artistMatches) return url
            }
        }
        return null
    }

    /**
     * Pulls the text out of a song page.
     *
     * Genius wraps each verse in a `div[data-lyrics-container]`; inside it, `<br>` is a line break
     * and everything else is markup to drop. Section markers like "[Chorus]" are kept — they are
     * part of how the text reads.
     */
    private fun extractLyrics(page: String): String? {
        val containers = lyricsContainers(page)
        if (containers.isEmpty()) return null

        return containers
            .joinToString("\n\n") { block ->
                block
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("""<[^>]+>"""), "")
                    .let(::unescape)
                    .lines()
                    .joinToString("\n") { it.trim() }
                    .trim()
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * The contents of every lyrics container on the page, matched by counting `div`s.
     *
     * A lazy `(.*?)</div>` cannot do this: the container has nested divs for annotations, so the
     * first closing tag it finds belongs to a child and the match ends a few words in.
     *
     * Genius also marks its translations bar with the same `data-lyrics-container` attribute and
     * distinguishes it with `data-exclude-from-selection` — which is why the first version of this
     * showed "TranslationsFrançaisPolskiDeutsch" where the words should be. Skipping those is not an
     * optimisation, it is the difference between lyrics and a language menu.
     */
    private fun lyricsContainers(page: String): List<String> {
        val opener = Regex(
            """<div\b[^>]*\bdata-lyrics-container="true"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val found = mutableListOf<String>()

        for (match in opener.findAll(page)) {
            if (match.value.contains("data-exclude-from-selection", ignoreCase = true)) continue
            val start = match.range.last + 1
            val end = matchingClose(page, start)
            if (end > start) found += withoutHeader(page.substring(start, end))
        }
        return found
    }

    /**
     * Where the `</div>` closing an already-opened div is, or -1 if the page never closes it.
     *
     * Counting rather than regex-matching, which is the whole reason this exists: the lyrics
     * container has nested divs for annotations, and a lazy `(.*?)</div>` stops at the first child's
     * closing tag a few words in.
     */
    private fun matchingClose(page: String, from: Int): Int {
        val anyDiv = Regex("""</?div\b""", RegexOption.IGNORE_CASE)
        var depth = 1
        var cursor = from
        while (depth > 0) {
            val tag = anyDiv.find(page, cursor) ?: return -1
            if (tag.value.startsWith("</")) {
                depth--
                if (depth == 0) return tag.range.first
            } else {
                depth++
            }
            cursor = tag.range.last + 1
        }
        return -1
    }

    /**
     * Drops the header Genius puts *inside* the first lyrics container.
     *
     * That header holds the translations bar, a repeat of the song title and the production credit —
     * "TranslationsFrançaisPolskiDeutschDigital Love Lyrics[Produced by Daft Punk]" is what it looked
     * like on screen before this existed. It is a nested div whose generated class name starts with
     * `LyricsHeader`, so the subtree goes and the verses stay. If Genius renames that class the worst
     * case is a line of clutter above the first verse, not lost lyrics.
     */
    private fun withoutHeader(container: String): String {
        val header = Regex("""<div\b[^>]*class="[^"]*LyricsHeader[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
        var text = container
        while (true) {
            val match = header.find(text) ?: return text
            val end = matchingClose(text, match.range.last + 1)
            if (end < 0) return text.substring(0, match.range.first)
            // Past the closing tag itself, which the count stopped in front of.
            val after = text.indexOf('>', end).let { if (it < 0) text.length else it + 1 }
            text = text.removeRange(match.range.first, after)
        }
    }

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TimeoutMs
                readTimeout = TimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UserAgent)
                setRequestProperty("Accept-Language", "en")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.d(Tag, "GET failed: $url", e)
            null
        } catch (e: SecurityException) {
            // No INTERNET permission, or a restricted network policy.
            Log.d(Tag, "GET refused: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ---- matching helpers ----

    /** Drops the noise tags in file titles that no lyrics site has in its own. */
    internal fun cleanTitle(title: String): String = title
        .replace(Regex("""\((?:feat|ft|with)\.?[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^]]*]"""), "")
        .replace(
            Regex(
                """[-–(]\s*(?:remaster(?:ed)?|remix|live|acoustic|radio edit|""" +
                    """single version|album version|bonus track|explicit)[^)]*\)?""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        .trim()

    /**
     * The first credited artist: "A feat. B", "A & B", "A / B" all search better as "A".
     *
     * Every alternative is anchored to a word boundary, and that is not decoration: without `\b`
     * before `ft`, this turned "Daft Punk" into "Da" and quietly filed every Daft Punk track as
     * having no lyrics.
     */
    internal fun cleanArtist(artist: String): String = artist
        .split(
            Regex(
                """\s*(?:\bfeat\.?|\bft\.?|\bfeaturing\b|&|,|/|\bvs\.?\b|\bwith\b)\s*""",
                RegexOption.IGNORE_CASE
            )
        )
        .firstOrNull()
        .orEmpty()
        .trim()
        .takeIf { it.isNotEmpty() && !it.equals("unknown artist", ignoreCase = true) }
        .orEmpty()

    /**
     * Reduces a title or a name to letters and digits only, lower case, with accents folded away.
     *
     * Folding matters as much as the case: a file tagged "Bjork" has to match the "Björk" Genius
     * knows, and "Motley Crue" the "Mötley Crüe". Decomposing to NFD and dropping the combining
     * marks turns every accented letter into its base one without a table of special cases.
     */
    private fun normalize(text: String): String = Normalizer
        .normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), "")
}

/** `jsonPrimitive.content` throws on JSON null; this is the version that doesn't. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it != "null" }
