package com.metromusic.data.lyrics

import android.content.Context
import android.net.Uri
import com.metromusic.data.model.Track
import com.metromusic.data.store.JsonStore
import com.metromusic.data.store.LyricsSource
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
 * There are three places a song's words can come from, and they are tried in that order:
 *
 * 1. **A `.lrc` or plain text file on the device** — beside the track, or in the folder the user
 *    pointed at (see [LyricsFiles]). This one comes first and is the only one that can be *timed*:
 *    a file that carries `[mm:ss.xx]` stamps makes the page follow the music. Somebody who has taken
 *    the trouble to put a synced file next to a song is not to be shown a flat page fetched from a
 *    website instead.
 * 2. **The cache**, a file per song, so a set of words fetched once opens instantly and offline.
 * 3. **The chosen online service**, LRCLIB or Genius — see [LyricsSource]. Only the first of those
 *    can be timed; Genius publishes words and has no timings at all.
 *
 * Availability is settled when a track enters the library rather than when you ask for it: the menu
 * has to know whether to grey out "show lyrics" before you open it, and finding out then would mean
 * a network round trip while the menu unfolds. [probe] runs down the tracks it has never seen — the
 * local half for all of them and quickly, the network half one at a time and slowly — and writes the
 * verdict into an index that persists.
 *
 * Nothing here ever blocks the caller: every entry point either reads state that is already in
 * memory or suspends.
 */
class LyricsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore
) {
    private val cacheDir = File(context.filesDir, "lyrics")

    private val files = LyricsFiles(context, settings)

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

    // ---- the lyrics folder ----

    /** The chosen folder's own name, for the settings row. */
    fun lyricsFolderLabel(): String? = files.folderLabel()

    /**
     * Remembers the folder the system picker just returned, or forgets it when handed null.
     *
     * The index is dropped with it: a folder full of `.lrc` files answers for songs that were
     * written off as having none, and leaving those verdicts in place would grey out the menu entry
     * for exactly the songs the user just pointed at.
     */
    fun setLyricsFolder(uri: Uri?) {
        files.rememberFolder(uri)
        index.update { LyricsIndex(it.known.filterValues { known -> known }) }
    }

    // ---- which service is asked ----

    /**
     * Changes the online source, and throws away everything the old one said.
     *
     * Both halves of that are necessary, and the second is the one that is easy to miss. The index
     * loses its **no**s, because "Genius has never heard of this" says nothing about whether LRCLIB
     * has, and leaving those in place would grey the menu entry out for exactly the songs the user
     * just went looking for a second opinion about. The **cache** goes too, which matters more: it
     * holds whatever the old service answered, it is consulted before the network, and so a library
     * full of flat Genius text would quietly win over the timed answers that were the entire reason
     * for switching to LRCLIB.
     *
     * The yeses are kept, because they are mostly about files on the device, which owe nothing to
     * either service.
     */
    fun setLyricsSource(source: LyricsSource) {
        if (settings.settings.value.lyricsSource == source) return
        settings.setLyricsSource(source)
        index.update { LyricsIndex(it.known.filterValues { known -> known }) }
        scope.launch(Dispatchers.IO) {
            runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
        }
    }

    // ---- reading ----

    /**
     * The words for a track: a file if there is one, then the cache, then the chosen service.
     *
     * Returns null when there is nothing to show — no file, no match, or no network. The index is
     * updated either way, but a network failure deliberately leaves the entry alone so the next probe
     * tries again instead of writing "no lyrics" because a train went into a tunnel.
     */
    suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val key = keyOf(track)

        // A file on the device wins over anything remembered, every time it is asked for rather than
        // once: dropping a synced `.lrc` next to a song has to take effect on the next time you open
        // it, not after the next scan.
        files.read(track)?.let {
            mark(key, true)
            return@withContext it
        }

        cached(key)?.let { return@withContext it }
        val current = settings.settings.value
        if (!current.lyricsEnabled) return@withContext null

        when (val answer = lookUpLyrics(current.lyricsSource, track)) {
            is LyricsAnswer.Found -> {
                val lyrics = Lrc.parse(answer.text, originOf(current.lyricsSource))
                    ?: return@withContext null.also { mark(key, false) }
                store(key, answer.text)
                mark(key, true)
                // The whole reason the setting exists: the words leave the app, into a file in the
                // format every other player reads, so they are still there when this one is not. What
                // LRCLIB answers is already `.lrc`, so a file saved from it is a *timed* one.
                if (current.lyricsSaveLrc) files.save(track, lyrics)
                lyrics
            }
            LyricsAnswer.NotFound -> {
                mark(key, false)
                null
            }
            LyricsAnswer.Unavailable -> null
        }
    }

    /**
     * Settles the availability of every track this has never looked at.
     *
     * Two passes, and the split is the point. The **local** one runs over the whole list, including
     * songs already written off as having none: a `.lrc` dropped into the folder yesterday has to be
     * able to overturn a "no" that Genius gave last week, and nothing else would ever look again. It
     * touches no network, so it runs whether or not the online lookup is switched on.
     *
     * The **network** one is deliberately unhurried — one request every [ProbeIntervalMs], giving up
     * for now after a run of failures rather than hammering a site that is not answering. A fresh
     * library of a thousand tracks therefore takes a while to fill in, and that is fine: the menu
     * treats "unknown" as clickable, so the only cost of not having probed a song yet is that opening
     * its lyrics has to go and look.
     */
    fun probe(tracks: List<Track>) {
        probeJob?.cancel()
        probeJob = scope.launch(Dispatchers.IO) {
            probeMutex.withLock {
                // One cursor for the whole volume's file paths, and one listing of the folder, rather
                // than a query per song — see [LyricsFiles.audioPath].
                files.refresh()
                for (track in tracks) {
                    val key = keyOf(track)
                    if (verdicts.value.known[key] == true) continue
                    if (files.has(track)) mark(key, true)
                }

                // The tags, asked about only where there is no verdict at all — so once per song,
                // ever, rather than on every launch. See [LyricsFiles.hasTags] for what that costs
                // and why the two halves are not asked the same way.
                for (track in tracks) {
                    val key = keyOf(track)
                    if (verdicts.value.known.containsKey(key)) continue
                    if (files.hasTags(track)) mark(key, true)
                }

                if (!settings.settings.value.lyricsEnabled) return@withLock
                var failures = 0
                for (track in tracks) {
                    val current = settings.settings.value
                    if (!current.lyricsEnabled) return@withLock
                    val key = keyOf(track)
                    if (verdicts.value.known.containsKey(key) || cached(key) != null) continue

                    when (val answer = lookUpLyrics(current.lyricsSource, track)) {
                        is LyricsAnswer.Found -> {
                            store(key, answer.text)
                            mark(key, true)
                            failures = 0
                        }
                        LyricsAnswer.NotFound -> {
                            mark(key, false)
                            failures = 0
                        }
                        LyricsAnswer.Unavailable -> {
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
        scope.launch(Dispatchers.IO) {
            fileFor(key).delete()
            legacyFileFor(key).delete()
        }
    }

    /**
     * Records a verdict — **and a yes is never overwritten by a no.**
     *
     * The two are not symmetrical, because what they cost is not symmetrical. A stale *yes* is
     * harmless: the menu entry stays tappable and the page says it found nothing. A stale *no* greys
     * the entry out, so there is no way to ask again about a song whose words are sitting right there.
     *
     * This is not hypothetical. Verdicts are keyed by artist-and-title, so two files of one song share
     * one — and with LRCLIB they can genuinely disagree, because it matches on duration: a full-length
     * copy is found and a forty-second clip of the same song is refused, correctly, as a different
     * recording. Caught on the device exactly that way round: the page fetched the words for one copy
     * and the background probe then wrote "no lyrics" over the answer, from the other.
     */
    private fun mark(key: String, available: Boolean) {
        val known = verdicts.value.known[key]
        if (known == available) return
        if (!available && known == true) return
        index.update { LyricsIndex(it.known + (key to available)) }
    }

    /**
     * The cached copy, which only ever holds what came off the network.
     *
     * Files on the device are deliberately not cached: they are read from where they are every time,
     * which costs a `readText` and means an edited `.lrc` shows its edit.
     */
    private fun cached(key: String): Lyrics? {
        val file = fileFor(key).takeIf { it.exists() } ?: legacyFileFor(key).takeIf { it.exists() }
        val text = file?.let { runCatching { it.readText() }.getOrNull() } ?: return null
        // Credited to whichever service is selected now, which is true because the cache is emptied
        // whenever that changes — see [setLyricsSource]. Nothing in the file itself says where it
        // came from, and a cache that outlived the switch would credit the wrong one.
        return Lrc.parse(text, originOf(settings.settings.value.lyricsSource))
    }

    /** What the page's footer should credit, for words that came off the network. */
    private fun originOf(source: LyricsSource): LyricsOrigin = when (source) {
        LyricsSource.LrcLib -> LyricsOrigin.LrcLib
        LyricsSource.Genius -> LyricsOrigin.Genius
    }

    private fun store(key: String, text: String) {
        runCatching {
            cacheDir.mkdirs()
            fileFor(key).writeText(text)
        }
    }

    /** A hash, because a title can contain anything a filesystem objects to. */
    private fun fileFor(key: String) = File(cacheDir, "${key.hashCode().toUInt()}.lrc")

    /** What the cache was called before it held `.lrc`; still read, never written. */
    private fun legacyFileFor(key: String) = File(cacheDir, "${key.hashCode().toUInt()}.txt")

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
