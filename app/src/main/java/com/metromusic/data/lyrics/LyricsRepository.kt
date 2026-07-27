package com.metromusic.data.lyrics

import android.content.Context
import com.metromusic.data.model.Track
import com.metromusic.data.store.JsonStore
import com.metromusic.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Whether a track has lyrics — and the third answer, which is what makes the menu honest. */
enum class LyricsStatus { Unknown, Available, Missing }

/**
 * What is known about which songs have lyrics, keyed by artist-and-title rather than by track id.
 *
 * Two copies of the same song — a single and the album version, the same file on two cards — share
 * one answer that way, and the index survives a rescan handing out new MediaStore ids, which it does
 * every time the media database is rebuilt.
 */
@Serializable
data class LyricsIndex(val known: Map<String, Boolean> = emptyMap())

/**
 * Finds, caches and remembers song lyrics.
 *
 * Availability is settled when a track enters the library rather than when you ask for it: the menu
 * has to know whether to grey out "show lyrics" before you open it, and finding out then would mean
 * a network round trip while the menu unfolds. [probe] runs down the tracks it has never seen, one at
 * a time and slowly, and writes the verdict into an index that persists.
 *
 * The text itself is cached in a file per song, so a song you have read once opens instantly and
 * offline. Nothing here ever blocks the caller: every entry point either reads state that is already
 * in memory or suspends.
 */
class LyricsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore
) {
    private val cacheDir = File(context.filesDir, "lyrics")

    private val index = JsonStore(
        file = File(context.filesDir, "lyrics-index.json"),
        serializer = LyricsIndex.serializer(),
        defaultValue = LyricsIndex(),
        scope = scope
    )

    /** Reading this in composition is what makes a greyed menu entry re-enable when a probe lands. */
    val verdicts: StateFlow<LyricsIndex> = index.state

    private val probeMutex = Mutex()
    private var probeJob: Job? = null

    fun status(track: Track): LyricsStatus = statusIn(verdicts.value, track)

    /**
     * The verdict according to a snapshot of the index.
     *
     * The UI wants this form: it collects [verdicts] so a greyed menu entry re-enables when a
     * background probe lands, and asking the repository through the value it already collected keeps
     * that subscription honest — [status] reads the current value without subscribing to it.
     */
    fun statusIn(index: LyricsIndex, track: Track): LyricsStatus =
        when (index.known[keyOf(track)]) {
            true -> LyricsStatus.Available
            false -> LyricsStatus.Missing
            null -> LyricsStatus.Unknown
        }

    /**
     * The text for a track, from the cache if it is there and from Genius otherwise.
     *
     * Returns null when there is nothing to show — no match, or no network. The index is updated
     * either way, but a network failure deliberately leaves the entry alone so the next probe tries
     * again instead of writing "no lyrics" because a train went into a tunnel.
     */
    suspend fun lyrics(track: Track): String? = withContext(Dispatchers.IO) {
        val key = keyOf(track)
        cached(key)?.let { return@withContext it }
        if (!settings.settings.value.lyricsEnabled) return@withContext null

        when (val result = GeniusClient.lyrics(track.artist, track.title)) {
            is GeniusClient.Result.Found -> {
                store(key, result.text)
                mark(key, true)
                result.text
            }
            GeniusClient.Result.NotFound -> {
                mark(key, false)
                null
            }
            GeniusClient.Result.Unavailable -> null
        }
    }

    /**
     * Settles the availability of every track this has never looked at.
     *
     * Deliberately unhurried — one request every [ProbeIntervalMs], and it gives up for now after a
     * run of failures rather than hammering a site that is not answering. A fresh library of a
     * thousand tracks therefore takes a while to fill in, and that is fine: the menu treats "unknown"
     * as clickable, so the only cost of not having probed a song yet is that opening its lyrics has
     * to go and look.
     */
    fun probe(tracks: List<Track>) {
        if (!settings.settings.value.lyricsEnabled) return
        probeJob?.cancel()
        probeJob = scope.launch(Dispatchers.IO) {
            probeMutex.withLock {
                var failures = 0
                for (track in tracks) {
                    if (!settings.settings.value.lyricsEnabled) return@withLock
                    val key = keyOf(track)
                    if (verdicts.value.known.containsKey(key) || cached(key) != null) continue

                    when (val result = GeniusClient.lyrics(track.artist, track.title)) {
                        is GeniusClient.Result.Found -> {
                            store(key, result.text)
                            mark(key, true)
                            failures = 0
                        }
                        GeniusClient.Result.NotFound -> {
                            mark(key, false)
                            failures = 0
                        }
                        GeniusClient.Result.Unavailable -> {
                            failures++
                            if (failures >= MaxFailures) return@withLock
                        }
                    }
                    delay(ProbeIntervalMs)
                }
            }
        }
    }

    fun forget(track: Track) {
        val key = keyOf(track)
        index.update { LyricsIndex(it.known - key) }
        scope.launch(Dispatchers.IO) { fileFor(key).delete() }
    }

    private fun mark(key: String, available: Boolean) =
        index.update { LyricsIndex(it.known + (key to available)) }

    private fun cached(key: String): String? =
        fileFor(key).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    private fun store(key: String, text: String) {
        runCatching {
            cacheDir.mkdirs()
            fileFor(key).writeText(text)
        }
    }

    /** A hash, because a title can contain anything a filesystem objects to. */
    private fun fileFor(key: String) = File(cacheDir, "${key.hashCode().toUInt()}.txt")

    private companion object {
        const val ProbeIntervalMs = 900L
        const val MaxFailures = 3

        fun keyOf(track: Track): String {
            val artist = GeniusClient.cleanArtist(track.artist).lowercase()
            val title = GeniusClient.cleanTitle(track.title).lowercase()
            return "$artist|$title"
        }
    }
}
