package com.metromusic.data.lyrics

import android.util.Log
import com.metromusic.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics from LRCLIB, which is the one source here that can answer with *timings*.
 *
 * It is an open community database of `.lrc` files with a documented API and **no token at all** —
 * which is the whole reason it can be in this app. The objection that rules out every commercial
 * lyrics API (a key identifies an application, cannot be committed to a public repo, and would make
 * the feature dead on arrival for anyone building this themselves) simply does not apply: there is
 * nothing to authenticate with. It asks only that clients say who they are, which [UserAgent] does.
 *
 * What comes back is `.lrc` text, so it goes through [Lrc] exactly as a file on the device does and
 * the page follows the music without anything further being arranged.
 *
 * Everything here blocks; call it from IO.
 */
object LrcLibClient {

    private const val Tag = "LrcLibClient"

    private const val Base = "https://lrclib.net/api"

    /**
     * LRCLIB asks clients to identify themselves rather than arrive anonymously, and it is a
     * volunteer-run database, so this is politeness with a return address on it.
     */
    private val UserAgent =
        "MetroMusic/${BuildConfig.VERSION_NAME} (https://github.com/Diffechento/MetroMusic)"

    private const val TimeoutMs = 12_000

    /** How far a candidate's length may be from the file's before it is a different recording. */
    private const val DurationSlackSeconds = 3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The words for one track.
     *
     * Three requests' worth of strategy at most, and the order is the point.
     *
     * `/api/get` is an *exact* lookup — artist, title, album and duration together — and a hit from
     * it is the right recording rather than a song with the same name. When that misses, the search
     * endpoint, where the answer has to be checked against the duration before it is believed: a
     * three-minute single and a nine-minute live version share a title, and pasting the wrong one
     * under a track is worse than showing nothing. And when *that* turns up nothing timed, the title
     * on its own with the artist matched here rather than by the service — see the comment at that
     * step for the spelling problem it exists for.
     *
     * Each step is skipped the moment something *timed* is in hand, so a song the first request
     * answers costs one request. The steps after it are paid for only by songs that have no timings
     * to find, and [LyricsRepository] remembers those so they are not paid for twice.
     */
    fun lyrics(artist: String, title: String, album: String, durationMs: Long): LyricsAnswer {
        val cleanArtist = GeniusClient.cleanArtist(artist)
        val cleanTitle = GeniusClient.cleanTitle(title)
        if (cleanTitle.isBlank()) return LyricsAnswer.NotFound
        val seconds = (durationMs / 1000).toInt()

        val exact = get(
            "$Base/get" +
                "?artist_name=" + encode(cleanArtist) +
                "&track_name=" + encode(cleanTitle) +
                "&album_name=" + encode(album) +
                "&duration=" + seconds
        )
        // What the exact lookup found, if it found only *flat* words. It is the right recording, so
        // it is what we fall back on — but it is not what was asked for, and the same song is
        // routinely in the database twice, once from a tagger that carried the timings over and once
        // from one that did not. So a flat exact hit goes on to the search rather than ending here.
        var untimed: LyricsAnswer.Found? = null
        when (exact) {
            is Response.Body -> when (val answer = parseOne(exact.text)) {
                is LyricsAnswer.Found -> if (answer.synced) return answer else untimed = answer
                null -> Unit
                else -> return answer
            }
            // A 404 here is LRCLIB saying "not with those four fields", which is not the same as
            // "not at all" — the album tag is the field most likely to be wrong in a file, and the
            // duration has to match within a couple of seconds. So it falls through to the search
            // rather than being reported as a definite no.
            Response.Missing -> Unit
            Response.Failed -> return LyricsAnswer.Unavailable
        }

        // Whether any request got through at all, so that "nothing has this song" is never reported
        // on the strength of a failure — the distinction the whole of [LyricsAnswer] exists for.
        var failed = false

        val found = get(
            "$Base/search" +
                "?artist_name=" + encode(cleanArtist) +
                "&track_name=" + encode(cleanTitle)
        )
        when (found) {
            is Response.Body -> when (val picked = pickFromSearch(found.text, seconds)) {
                is LyricsAnswer.Found ->
                    if (picked.synced) return picked else if (untimed == null) untimed = picked
                LyricsAnswer.Unavailable -> failed = true
                LyricsAnswer.NotFound -> Unit
            }
            Response.Missing -> Unit
            Response.Failed -> failed = true
        }

        // The last resort: **the title on its own, and the artist matched back by hand.**
        //
        // LRCLIB's own search wants the artist name to match, and one act is filed under two
        // spellings of it all the time. Reported against *DenDerty — Чёрная дыра*: asking for
        // `artist_name=DenDerty` answers with three records and not one of them is timed, while the
        // timed one sits under **`Den Derty`**, with a space. Folded to letters and digits those are
        // one name, and folding is the same thing this app already does to decide that "Blink 182"
        // and "Blink-182" are one artist.
        //
        // A title on its own is a query that answers with twenty other people's songs — "Чёрная
        // дыра" alone returns Мумий Тролль, Смешарики and KUNTEYNIR — so nothing here is trusted:
        // the artist, the title *and* the length all have to agree before a record is believed.
        for (spelling in spellingsOf(cleanTitle)) {
            when (val loose = get("$Base/search?q=" + encode(spelling))) {
                is Response.Body ->
                    pickByHand(loose.text, cleanArtist, cleanTitle, seconds)?.let { picked ->
                        if (picked.synced) return picked else if (untimed == null) untimed = picked
                    }
                Response.Missing -> Unit
                Response.Failed -> failed = true
            }
        }

        // Flat words in hand and a request that never got through: they are worth showing and worth
        // nothing as a verdict, because the steps that look for *timed* words are exactly the ones
        // that were lost. Saying so is what stops [LyricsRepository] writing the song off.
        untimed?.let { return if (failed) it.copy(complete = false) else it }
        return if (failed) LyricsAnswer.Unavailable else LyricsAnswer.NotFound
    }

    /**
     * The spellings of a title worth asking about, which is one — or two where **ё** is involved.
     *
     * LRCLIB does not treat `е` and `ё` as the same letter and neither do taggers: `q=Чёрная дыра`
     * and `q=Черная дыра` come back with two entirely different sets of songs. Whichever a file
     * carries, the other is the one the database may be filed under, so the fallback asks for both.
     * Titles with neither letter, which is most of them, still cost exactly one request.
     */
    private fun spellingsOf(title: String): List<String> {
        val swapped = buildString(title.length) {
            for (c in title) append(
                when (c) {
                    'ё' -> 'е'
                    'Ё' -> 'Е'
                    'е' -> 'ё'
                    'Е' -> 'Ё'
                    else -> c
                }
            )
        }
        return if (swapped == title) listOf(title) else listOf(title, swapped)
    }

    /**
     * The best record in a free-text answer that really is *this* recording.
     *
     * Everything is checked, because the query that produced it was a title and nothing else. A
     * record with no length is refused here, unlike in [pickFromSearch]: there the artist and title
     * had already been matched by the service, and here they are all this has.
     */
    private fun pickByHand(
        body: String,
        artist: String,
        title: String,
        seconds: Int
    ): LyricsAnswer.Found? {
        val results = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return null
        val mine = results
            .mapNotNull { it as? JsonObject }
            .filter { entry ->
                val length = entry["duration"]?.jsonPrimitive?.doubleOrNullSafe()?.toInt()
                fold(entry["artistName"]?.jsonPrimitive?.contentOrNullSafe()) == fold(artist) &&
                    fold(entry["trackName"]?.jsonPrimitive?.contentOrNullSafe()) == fold(title) &&
                    length != null && seconds != 0 && abs(length - seconds) <= DurationSlackSeconds
            }
        val answers = mine.mapNotNull(::answerFor)
        return answers.firstOrNull { it.synced } ?: answers.firstOrNull()
    }

    /**
     * What two spellings of one name have in common: letters and digits, lowercased, with `ё` read
     * as `е`. It is [com.metromusic.data.model.fold]'s rule, kept here because this file talks to a
     * service rather than to the library.
     */
    private fun fold(value: String?): String =
        (value ?: "").lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /**
     * The best of the search results, or [LyricsAnswer.NotFound] if none of them is this recording.
     *
     * A timed result is preferred over a flat one even when the flat one is a closer length match —
     * timings are the reason to be asking this service at all, and a couple of seconds of difference
     * between two pressings of the same song is normal.
     */
    private fun pickFromSearch(body: String, seconds: Int): LyricsAnswer {
        val results = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
        // Not an array means we were not talking to LRCLIB at all — a portal, a proxy's error page.
        // That is emphatically not "this song has no lyrics".
            ?: return LyricsAnswer.Unavailable

        val plausible = results
            .mapNotNull { it as? JsonObject }
            .filter { entry ->
                val length = entry["duration"]?.jsonPrimitive?.doubleOrNullSafe()?.toInt()
                // A result with no length at all is kept: the exact endpoint has already failed, and
                // an unchecked candidate beats nothing when the alternative is no lyrics.
                length == null || seconds == 0 || abs(length - seconds) <= DurationSlackSeconds
            }

        val answers = plausible.mapNotNull(::answerFor)
        return answers.firstOrNull { it.synced } ?: answers.firstOrNull() ?: LyricsAnswer.NotFound
    }

    private fun parseOne(body: String): LyricsAnswer? {
        val entry = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return answerFor(entry) ?: instrumentalOrNull(entry)
    }

    /**
     * One record turned into an answer: its timed words if it has them, its flat ones otherwise.
     *
     * Null means the record carries no usable words at all — which is not the same as the *service*
     * having none, so the caller decides what that means in its context.
     */
    private fun answerFor(entry: JsonObject): LyricsAnswer.Found? {
        val synced = entry["syncedLyrics"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
        if (synced != null) return LyricsAnswer.Found(synced, synced = true)
        val plain = entry["plainLyrics"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
        return plain?.let { LyricsAnswer.Found(it, synced = false) }
    }

    /**
     * LRCLIB marks a track it knows to have no words as `instrumental`, which is a real answer and
     * one worth remembering — the alternative is asking again about a track that will never have any.
     */
    private fun instrumentalOrNull(entry: JsonObject): LyricsAnswer? =
        if (entry["instrumental"]?.jsonPrimitive?.booleanOrNullSafe() == true) {
            LyricsAnswer.NotFound
        } else {
            null
        }

    /** The three things a request can do, kept apart for the reason [LyricsAnswer] exists. */
    private sealed interface Response {
        data class Body(val text: String) : Response
        /** The service answered and has nothing: a 404, which LRCLIB uses for "no such track". */
        data object Missing : Response
        data object Failed : Response
    }

    private fun get(url: String): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TimeoutMs
                readTimeout = TimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UserAgent)
                setRequestProperty("Accept", "application/json")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> Response.Body(
                    connection.inputStream.bufferedReader().use { it.readText() }
                )
                404 -> Response.Missing
                else -> {
                    Log.d(Tag, "GET $url answered $code")
                    Response.Failed
                }
            }
        } catch (e: IOException) {
            Log.d(Tag, "GET failed: $url", e)
            Response.Failed
        } catch (e: SecurityException) {
            // No INTERNET permission, or a restricted network policy.
            Log.d(Tag, "GET refused: $url", e)
            Response.Failed
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")
}

/** `jsonPrimitive.content` throws on JSON null; these are the versions that don't. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it != "null" }

private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNullSafe(): Double? =
    runCatching { content.toDoubleOrNull() }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.booleanOrNullSafe(): Boolean? =
    runCatching { content.toBooleanStrictOrNull() }.getOrNull()
