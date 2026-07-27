package com.metromusic.data.media

import android.content.Context
import android.util.Log
import com.metromusic.data.lastfm.LastFmClient
import com.metromusic.data.lastfm.lastFmKey
import com.metromusic.data.store.JsonStore
import com.metromusic.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What an album is called, which is all a cover lookup has to go on. */
data class CoverQuery(val artist: String, val album: String, val sampleTitle: String?)

/**
 * Covers for the albums whose files carry none, from Last.fm.
 *
 * Opt-in (settings → library → album art) and failing soft, like everything here that touches the
 * network: with it off, or with no api key, or with no connection, an album without a cover is an
 * album without a cover and nothing about the app behaves differently.
 *
 * **Asked once.** The answers live in `covers-index.json` keyed by artist-and-album rather than by
 * album id — MediaStore hands ids out again when the media database is rebuilt, so an id-keyed answer
 * would be attached to a different album after a rescan. The image itself lands in `files/covers`
 * under the name Last.fm gives it, so two albums that share a cover share the file.
 *
 * **"No cover" and "could not ask" are different answers**, and only the first is remembered. That
 * distinction is the whole reason [LastFmClient.albumArt] returns null separately from an empty list:
 * one captive portal on one morning would otherwise write a permanent "no cover" against a whole
 * library, and nothing in the app would ever ask again.
 */
class OnlineArtwork(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore
) {
    @Serializable
    private data class Index(
        /** Folded artist-and-album to the cached file's name, or an empty string for "there is none". */
        val answers: Map<String, String> = emptyMap()
    )

    private val directory = File(context.filesDir, "covers")

    private val index = JsonStore(
        file = File(context.filesDir, "covers-index.json"),
        serializer = Index.serializer(),
        defaultValue = Index(),
        scope = scope
    )

    /** One fetch per album at a time, so a screen of tiles asking at once asks once. */
    private val inFlight = mutableMapOf<String, Deferred<File?>>()
    private val guard = Mutex()

    /**
     * Two requests at a time. Scrolling a coverless library would otherwise open a connection per
     * visible tile, which is both rude to Last.fm and slower than doing it in order.
     */
    private val permits = Semaphore(2)

    /** The cover for [query], from disk if it is already here, else fetched. Null when there is none. */
    suspend fun cover(query: CoverQuery): File? {
        if (!settings.settings.value.onlineArtwork) return null
        if (query.artist.isBlank() || query.album.isBlank()) return null

        val key = keyOf(query)
        val known = index.state.value.answers[key]
        // Asked before, and the answer was no.
        if (known == "") return null
        if (known != null) {
            val file = File(directory, known)
            // If the image is gone but the answer was kept, ask again rather than show nothing.
            if (withContext(Dispatchers.IO) { file.isFile }) return file
        }

        val task = guard.withLock {
            inFlight[key] ?: scope.async { fetch(key, query) }.also { inFlight[key] = it }
        }
        return try {
            task.await()
        } finally {
            guard.withLock { if (inFlight[key] === task) inFlight.remove(key) }
        }
    }

    private suspend fun fetch(key: String, query: CoverQuery): File? = permits.withPermit {
        val apiKey = settings.settings.value.lastFmKey() ?: return null
        val client = LastFmClient(apiKey, apiSecret = null)

        withContext(Dispatchers.IO) {
            // The album first, then the same question through one of its tracks: the album tag is what
            // is most often missing or wrong in a file, and `track.getinfo` answers with the album it
            // believes the track is on.
            val byAlbum = client.albumArt(query.artist, query.album)
            val urls = when {
                !byAlbum.isNullOrEmpty() -> byAlbum
                query.sampleTitle != null -> client.trackAlbumArt(query.artist, query.sampleTitle)
                else -> byAlbum
            }
            when {
                // Could not ask. Remember nothing, so the next scroll past this album tries again.
                urls == null -> null
                urls.isEmpty() -> {
                    remember(key, "")
                    null
                }
                else -> download(urls.last())?.also { remember(key, it.name) }
            }
        }
    }

    /**
     * Fetches the largest version of a Last.fm image there is.
     *
     * The api answers with sizes up to `300x300`, which is a thumbnail on a phone that is 1080 across.
     * The size is a path segment and the store serves other values for the same image, so `770x0` is
     * tried first and the url as given is the fallback — a cover four times the area for the same
     * request, and the difference is plain on the player's full-bleed backdrop.
     */
    private fun download(url: String): File? {
        val candidates = listOfNotNull(
            SizeSegment.find(url)?.let { url.replaceRange(it.range, "/$LargeSize/") },
            url
        )
        directory.mkdirs()
        val target = File(directory, url.substringAfterLast('/').ifEmpty { "cover" })
        for (candidate in candidates) {
            if (get(candidate, target)) return target
        }
        return null
    }

    /** True when [target] now holds the image. Written through a temporary file, so a cut-off download
     *  cannot leave half a cover behind for the index to point at. */
    private fun get(url: String, target: File): Boolean {
        var connection: HttpURLConnection? = null
        val temp = File(target.parentFile, "${target.name}.part")
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TimeoutMs
                readTimeout = TimeoutMs
                setRequestProperty("User-Agent", "MetroMusic/1.0")
            }
            if (connection.responseCode !in 200..299) return false
            var total = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // A cover is a few hundred kilobytes; anything this large is not one.
                        if (total > MaxBytes) return false
                        output.write(buffer, 0, read)
                    }
                }
            }
            total > 0 && (temp.renameTo(target) || temp.copyTo(target, overwrite = true).isFile)
        } catch (e: IOException) {
            Log.d(Tag, "cover download failed", e)
            false
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    private fun remember(key: String, name: String) =
        index.update { it.copy(answers = it.answers + (key to name)) }

    private fun keyOf(query: CoverQuery): String =
        "${query.artist.trim().lowercase()}|${query.album.trim().lowercase()}"

    private companion object {
        const val Tag = "OnlineArtwork"
        const val TimeoutMs = 12_000
        const val MaxBytes = 4L * 1024 * 1024
        const val LargeSize = "770x0"

        /** The size in a Last.fm image path: `/i/u/300x300/<hash>.jpg`, `/i/u/174s/<hash>.png`. */
        val SizeSegment = Regex("""/\d+(?:x\d+|s)/""")
    }
}
