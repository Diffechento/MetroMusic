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
     * Two requests' worth of strategy, and the order is the point. `/api/get` is an *exact* lookup —
     * artist, title, album and duration together — and a hit from it is the right recording rather
     * than a song with the same name. Only when that misses does this fall back to the search
     * endpoint, where the answer has to be checked against the duration before it is believed: a
     * three-minute single and a nine-minute live version share a title, and pasting the wrong one
     * under a track is worse than showing nothing.
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
        when (exact) {
            is Response.Body -> parseOne(exact.text)?.let { return it }
            // A 404 here is LRCLIB saying "not with those four fields", which is not the same as
            // "not at all" — the album tag is the field most likely to be wrong in a file, and the
            // duration has to match within a couple of seconds. So it falls through to the search
            // rather than being reported as a definite no.
            Response.Missing -> Unit
            Response.Failed -> return LyricsAnswer.Unavailable
        }

        val found = get(
            "$Base/search" +
                "?artist_name=" + encode(cleanArtist) +
                "&track_name=" + encode(cleanTitle)
        )
        return when (found) {
            is Response.Body -> pickFromSearch(found.text, seconds)
            Response.Missing -> LyricsAnswer.NotFound
            Response.Failed -> LyricsAnswer.Unavailable
        }
    }

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
