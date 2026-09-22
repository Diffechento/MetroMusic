package com.metromusic.data.playlist

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.metromusic.data.media.AudioPaths
import com.metromusic.data.store.SettingsStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of a playlist, as this app sees it.
 *
 * [trackId] is null when the library has nothing for [target] — a file that was deleted, one below
 * the duration filter, one belonging to a hidden artist, or a line from a playlist written on
 * another machine. Such a line is **kept**: it is drawn as a row that says what it is, and it is
 * written back out exactly as it came in. Dropping it would mean that opening somebody's playlist
 * and adding one song to it quietly deleted every line this device happens not to have.
 */
@Immutable
data class PlaylistEntry(
    val target: String,
    val trackId: Long?,
    /** What the `#EXTINF` claimed, which is all a row has to show when [trackId] is null. */
    val title: String?,
    val durationSec: Long?
) {
    /** The tail of the line, for a row that has nothing better to show. */
    val fileName: String
        get() = target.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
}

/**
 * A playlist, which from here on is a file.
 *
 * [id] is the file: its path, or its document uri inside a folder the user picked. It therefore
 * changes when the playlist is renamed, which is the price of the name on screen and the name on
 * disk being the same thing.
 */
@Immutable
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
    /** False for a file another app owns — editing one needs the user to agree once. */
    val writable: Boolean = true,
    val modifiedAt: Long = 0L
) {
    /**
     * The tracks the file names that MediaStore has a row for, in the playlist's own order.
     *
     * Not the same as the tracks the app will *show*: the library is this filtered again by the
     * duration cut-off and by whatever the user has hidden, so anything counting rows for the user
     * puts these through [com.metromusic.data.model.Library.resolve] rather than taking the size.
     */
    val trackIds: List<Long> get() = entries.mapNotNull { it.trackId }
}

@Immutable
data class Playlists(val items: List<Playlist> = emptyList(), val loaded: Boolean = false)

/** Something the user asked for that the filesystem refused; the shell says so and moves on. */
enum class PlaylistProblem { Save, Import, Export }

/** What an `#EXTINF` needs about a track, answered by whoever holds the library. */
data class PlaylistTrackInfo(val title: String, val artist: String, val durationMs: Long)

/**
 * The user's playlists, kept as `.m3u` files.
 *
 * They used to be a list inside `playlists.json`, which is a perfectly good way to store track ids
 * and a poor way to store a playlist: the ids are MediaStore's, so they are handed out again when
 * the media database is rebuilt, and nothing but this app could ever read the file. `.m3u` is the
 * one thing every player and every desktop program agrees on, it names *files* rather than database
 * rows, and a playlist written here can be copied to a computer and back. An old `playlists.json`
 * is converted on first run and kept beside the new files as `playlists.json.migrated`.
 *
 * **The in-memory list is what the UI reads, and the file is written behind it.** Reordering a
 * playlist by dragging is one write per drag rather than one per step ([WriteDebounceMs]), which is
 * the same bargain [com.metromusic.data.store.JsonStore] strikes; every edit lands in [playlists]
 * immediately so nothing on screen waits for a disk.
 *
 * **A write can be refused**, which is the one way this is harder than a private file. A playlist
 * another app wrote is readable and not writable, so the first edit of one answers with
 * [PlaylistWrite.NeedsConsent]; [consent] carries the system dialog up to the shell and
 * [consentAnswered] runs the edit again once the user has agreed. Nothing is lost while that is
 * happening — the edit is already in [playlists] and the file catches up.
 */
class PlaylistStore(
    context: Context,
    private val scope: CoroutineScope,
    settings: SettingsStore,
    private val paths: AudioPaths
) {

    private val files = PlaylistFiles(context, settings)

    /** The `playlists.json` of every version up to 1.4.1, read once and then put out of the way. */
    private val legacyFile = File(context.filesDir, "playlists.json")

    /**
     * What a track is called and how long it is, for the `#EXTINF` line.
     *
     * A lambda filled in by the composition root rather than a constructor argument, because the
     * library is built on top of this app's stores and cannot be handed to one of them — the same
     * shape as `ArtworkLoader.albumNames` and `PlayerController.trackById`.
     */
    var trackInfo: (Long) -> PlaylistTrackInfo? = { null }

    private val _playlists = MutableStateFlow(Playlists())
    val playlists: StateFlow<Playlists> = _playlists.asStateFlow()

    /** Non-null while the platform is waiting to be asked about a file this app does not own. */
    private val _consent = MutableStateFlow<IntentSender?>(null)
    val consent: StateFlow<IntentSender?> = _consent.asStateFlow()

    private val _problem = MutableStateFlow<PlaylistProblem?>(null)
    val problem: StateFlow<PlaylistProblem?> = _problem.asStateFlow()

    /** Where the files are, for the settings page to show. Read after the first listing. */
    val folderLabel: String get() = files.homeLabel

    val folderIsPrivate: Boolean get() = files.homeIsPrivate

    private val writes = ConcurrentHashMap<String, Job>()
    private val unsaved = ConcurrentHashMap.newKeySet<String>()
    private val reloading = Mutex()
    private var retry: (suspend () -> Unit)? = null

    init {
        scope.launch { reload() }
    }

    // ---- reading ----

    /**
     * Reads the folder again. Called after a scan, because the ids a line resolves to are
     * MediaStore's and a rescan hands out new ones — and because the user is free to drop a `.m3u`
     * into the folder from anywhere at all.
     *
     * Anything still waiting to be written is written first, so a refresh cannot undo an edit the
     * user made a moment ago.
     */
    fun refresh() {
        scope.launch { reload() }
    }

    private suspend fun reload() = reloading.withLock {
        flushPending()
        val items = withContext(Dispatchers.IO) {
            files.list()
                .map { read(it) }
                .sortedBy { it.name.lowercase() }
        }
        // Anything the disk would not take keeps what is in memory. [flushPending] above has
        // already tried, so a playlist still listed as unsaved is one the platform refused — it is
        // waiting on the consent dialog — and reading its file back now would take the user's edit
        // off the screen while they are being asked whether to allow it.
        val held = _playlists.value.items.filter { it.id in unsaved }.associateBy { it.id }
        _playlists.value = Playlists(
            items = if (held.isEmpty()) items else items.map { held[it.id] ?: it },
            loaded = true
        )
        // Tried after every listing rather than once at startup, because it needs the library and
        // the library is not there yet on the first one. A no-op the moment there is no old file.
        migrateLegacy()
    }

    private fun read(stored: StoredPlaylist): Playlist {
        val document = M3u.parse(files.read(stored.ref).orEmpty())
        val directory = files.directoryOf(stored.ref)
        return Playlist(
            id = stored.ref.id,
            // The *file* name and not the `#PLAYLIST` line: renaming changes the file, and a
            // playlist whose row says one thing while the folder says another is a playlist nobody
            // can find again from a computer.
            name = stored.name,
            entries = document.entries.map { entry ->
                PlaylistEntry(
                    target = entry.target,
                    trackId = resolve(entry.target, directory),
                    title = entry.title,
                    durationSec = entry.durationSec
                )
            },
            writable = stored.writable,
            modifiedAt = stored.modifiedAt
        )
    }

    private fun resolve(target: String, directory: String?): Long? {
        Targets.toMediaId(target)?.let { return it }
        val path = Targets.toPath(target, directory) ?: return null
        return paths.idOf(path)
    }

    // ---- editing ----

    /** Makes a playlist and fills it, in that order, because the file is what gives it an id. */
    fun create(name: String, trackIds: List<Long> = emptyList(), now: Long = 0L) {
        scope.launch {
            val ref = withContext(Dispatchers.IO) { files.create(name) }
            if (ref == null) {
                _problem.value = PlaylistProblem.Save
                return@launch
            }
            val directory = files.directoryOf(ref)
            val entries = withContext(Dispatchers.IO) {
                trackIds.mapNotNull { entryFor(it, directory) }
            }
            val playlist = Playlist(
                id = ref.id,
                name = name,
                entries = entries,
                writable = true,
                modifiedAt = now
            )
            _playlists.value = _playlists.value.let { current ->
                current.copy(
                    items = (current.items + playlist).sortedBy { it.name.lowercase() },
                    loaded = true
                )
            }
            if (entries.isNotEmpty()) writeNow(playlist.id)
        }
    }

    fun rename(id: String, name: String) {
        scope.launch {
            // Whatever is queued for the old file, before its name changes underneath the write.
            flushPending(id)
            val renamed = withContext(Dispatchers.IO) { files.rename(files.refOf(id), name) }
            when (val result = renamed.result) {
                is PlaylistWrite.Ok -> {
                    _playlists.value = _playlists.value.mapItems { playlist ->
                        if (playlist.id == id) {
                            playlist.copy(id = renamed.ref.id, name = name)
                        } else {
                            playlist
                        }
                    }
                    // The `#PLAYLIST` line inside the file carries the name too, so it follows.
                    writeNow(renamed.ref.id)
                }

                is PlaylistWrite.NeedsConsent -> askFor(result.request) { rename(id, name) }
                is PlaylistWrite.Failed -> _problem.value = PlaylistProblem.Save
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            writes.remove(id)?.cancel()
            unsaved.remove(id)
            when (val result = withContext(Dispatchers.IO) { files.delete(files.refOf(id)) }) {
                is PlaylistWrite.Ok ->
                    _playlists.value = _playlists.value.let { current ->
                        current.copy(items = current.items.filterNot { it.id == id })
                    }

                is PlaylistWrite.NeedsConsent -> askFor(result.request) { delete(id) }
                is PlaylistWrite.Failed -> _problem.value = PlaylistProblem.Save
            }
        }
    }

    /** Appends, skipping tracks the playlist already names so a double tap cannot duplicate them. */
    fun add(id: String, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        scope.launch {
            val directory = files.directoryOf(files.refOf(id))
            val current = _playlists.value.items.firstOrNull { it.id == id } ?: return@launch
            val already = current.trackIds.toSet()
            val fresh = withContext(Dispatchers.IO) {
                trackIds.asSequence()
                    .distinct()
                    .filterNot { it in already }
                    .mapNotNull { entryFor(it, directory) }
                    .toList()
            }
            if (fresh.isEmpty()) return@launch
            mutate(id) { it.copy(entries = it.entries + fresh) }
        }
    }

    fun removeAt(id: String, index: Int) = mutate(id) { playlist ->
        if (index !in playlist.entries.indices) {
            playlist
        } else {
            playlist.copy(entries = playlist.entries.toMutableList().apply { removeAt(index) })
        }
    }

    fun move(id: String, from: Int, to: Int) = mutate(id) { playlist ->
        val entries = playlist.entries
        if (from !in entries.indices || to !in entries.indices || from == to) {
            playlist
        } else {
            playlist.copy(entries = entries.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    private fun entryFor(trackId: Long, directory: String?): PlaylistEntry? {
        // A track the media database has no path for cannot be written into a file of paths. It is
        // rare enough to be worth a line in the log rather than an apology on screen.
        val path = paths.pathOf(trackId) ?: run {
            Log.w(Tag, "No file path for track " + trackId + ", leaving it out")
            return null
        }
        val info = trackInfo(trackId)
        return PlaylistEntry(
            target = Targets.toLine(path, directory),
            trackId = trackId,
            title = info?.let { it.artist + " - " + it.title },
            durationSec = info?.durationMs?.div(1000)
        )
    }

    private inline fun mutate(id: String, crossinline transform: (Playlist) -> Playlist) {
        val before = _playlists.value
        val after = before.mapItems { if (it.id == id) transform(it) else it }
        if (after == before) return
        _playlists.value = after
        scheduleWrite(id)
    }

    private fun Playlists.mapItems(transform: (Playlist) -> Playlist): Playlists =
        copy(items = items.map(transform))

    // ---- in and out ----

    /** Copies a `.m3u` the user picked from anywhere on the device into the playlist folder. */
    fun import(source: Uri) {
        scope.launch {
            val ref = withContext(Dispatchers.IO) { files.import(source) }
            if (ref == null) {
                _problem.value = PlaylistProblem.Import
                return@launch
            }
            reload()
        }
    }

    /**
     * Writes a playlist out to a document the user picked — **with absolute paths**.
     *
     * Not a copy of the file, which is what this did first and what the file in `Downloads` then
     * showed up the mistake of: the lines in the folder are written relative *to that folder*, so
     * `../Nightbus/01.mp3` exported into `Download` points at a file that is not there. An export
     * is a playlist leaving the one place its relative paths mean anything, so it is rendered
     * again with every line this device can resolve spelled out in full. Lines it cannot resolve
     * go out exactly as they came in — they are somebody else's paths and rewriting them would be
     * inventing.
     */
    fun export(id: String, target: Uri) {
        scope.launch {
            flushPending(id)
            val playlist = _playlists.value.items.firstOrNull { it.id == id }
            val ok = playlist != null && withContext(Dispatchers.IO) {
                val entries = playlist.entries.map { entry ->
                    val path = entry.trackId?.let { paths.pathOf(it) }
                    M3uEntry(path ?: entry.target, entry.title, entry.durationSec)
                }
                files.export(M3u.render(entries, playlist.name), target)
            }
            if (!ok) _problem.value = PlaylistProblem.Export
        }
    }

    /** A name for the file a playlist is exported to, so the picker opens with something sensible. */
    fun exportName(id: String): String {
        val name = _playlists.value.items.firstOrNull { it.id == id }?.name ?: "playlist"
        return name + "." + M3u.Extension
    }

    fun setFolder(uri: Uri?) {
        files.rememberFolder(uri)
        refresh()
    }

    // ---- writing ----

    private fun scheduleWrite(id: String) {
        unsaved += id
        writes.remove(id)?.cancel()
        writes[id] = scope.launch {
            delay(WriteDebounceMs)
            // Taken out of the map *before* the write, and this is not tidiness: [writeNow] cancels
            // whatever is waiting for that playlist, and what was waiting is this coroutine. It
            // cancelled itself at its first suspension point, so the debounced write — every edit
            // that is not a create or a rename — never reached the disk. It cost a run on a device
            // to see, because everything on screen was right: the change is in memory first and the
            // file is what was quietly not catching up.
            writes.remove(id)
            writeNow(id)
        }
    }

    private suspend fun writeNow(id: String) {
        writes.remove(id)?.cancel()
        val playlist = _playlists.value.items.firstOrNull { it.id == id } ?: run {
            unsaved.remove(id)
            return
        }
        val result = withContext(Dispatchers.IO) { files.write(files.refOf(id), textOf(playlist)) }
        when (result) {
            is PlaylistWrite.Ok -> unsaved.remove(id)
            is PlaylistWrite.NeedsConsent -> askFor(result.request) { writeNow(id) }
            is PlaylistWrite.Failed -> {
                unsaved.remove(id)
                Log.w(Tag, "Cannot save " + playlist.name + ": " + result.reason)
                _problem.value = PlaylistProblem.Save
            }
        }
    }

    private fun textOf(playlist: Playlist): String = M3u.render(
        playlist.entries.map { M3uEntry(it.target, it.title, it.durationSec) },
        playlist.name
    )

    /** Writes everything that is waiting. For teardown, and before anything re-reads the folder. */
    suspend fun flush() = flushPending()

    private suspend fun flushPending(only: String? = null) {
        val pending = if (only != null) {
            if (only in unsaved) listOf(only) else emptyList()
        } else {
            unsaved.toList()
        }
        for (id in pending) writeNow(id)
    }

    // ---- the consent dialog ----

    private fun askFor(request: IntentSender, again: suspend () -> Unit) {
        retry = again
        _consent.value = request
    }

    /**
     * The shell has shown the dialog and has an answer. A yes runs the refused operation again,
     * which is the only way to find out whether it is really allowed now; a no leaves the edit in
     * memory and the file alone, and says so.
     */
    fun consentAnswered(granted: Boolean) {
        val again = retry
        retry = null
        _consent.value = null
        if (!granted) {
            _problem.value = PlaylistProblem.Save
            return
        }
        if (again != null) scope.launch { again() }
    }

    fun problemSeen() {
        _problem.value = null
    }

    // ---- the old json ----

    /**
     * Turns a pre-1.5 `playlists.json` into files, once.
     *
     * **The guard is the important part, and it took a run on a device to get right.** A playlist
     * line needs two things the library has to be ready to answer: a *path*, which comes from
     * MediaStore, and a title and length for the `#EXTINF`, which come from the scan. Run at
     * startup it gets the first and not the second, and the files it writes are correct and
     * anonymous — every line reading `#EXTINF:-1,` — which is what another player shows for them
     * and what this app falls back to when a file goes missing. Run before MediaStore can answer at
     * all it would write nothing but empty playlists and then move the only copy of the real ones
     * out of the way.
     *
     * So the test is whether one track of one playlist can be described completely, and a "no" is
     * "not yet": this is called after every listing, and the shell asks for one after every scan.
     * A library that has genuinely lost all of those files never converts and keeps its
     * `playlists.json` — nothing is written and nothing is lost.
     */
    private suspend fun migrateLegacy() {
        if (!legacyFile.exists()) return
        val data = withContext(Dispatchers.IO) {
            runCatching { LegacyJson.decodeFromString(LegacyData.serializer(), legacyFile.readText()) }.onFailure { Log.w(Tag, "Cannot read the old playlists.json", it) }.getOrNull()
        }
        if (data == null || data.items.isEmpty()) {
            retireLegacy()
            return
        }
        val hasTracks = data.items.any { it.trackIds.isNotEmpty() }
        val describable = withContext(Dispatchers.IO) {
            data.items.any { playlist ->
                playlist.trackIds.any { paths.pathOf(it) != null && trackInfo(it) != null }
            }
        }
        if (hasTracks && !describable) {
            Log.i(Tag, "Not converting playlists.json yet: the library cannot describe it")
            return
        }
        Log.i(Tag, "Converting " + data.items.size + " playlists to .m3u")
        for (playlist in data.items) {
            withContext(Dispatchers.IO) {
                val ref = files.create(playlist.name) ?: return@withContext
                val directory = files.directoryOf(ref)
                val entries = playlist.trackIds.mapNotNull { entryFor(it, directory) }
                files.write(
                    ref,
                    M3u.render(
                        entries.map { M3uEntry(it.target, it.title, it.durationSec) },
                        playlist.name
                    )
                )
            }
        }
        retireLegacy()
        // The files are on the disk and the list in memory still says json. Read the folder again —
        // directly, because this is called from inside [reload] and its lock is already held.
        val items = withContext(Dispatchers.IO) {
            files.list().map { read(it) }.sortedBy { it.name.lowercase() }
        }
        _playlists.value = Playlists(items, loaded = true)
    }

    /** Kept rather than deleted: it is the only copy of what the playlists used to be. */
    private suspend fun retireLegacy() = withContext(Dispatchers.IO) {
        runCatching { legacyFile.renameTo(File(legacyFile.parentFile, "playlists.json.migrated")) }
            .onFailure { Log.w(Tag, "Cannot put playlists.json out of the way", it) }
        Unit
    }

    @Serializable
    private data class LegacyPlaylist(
        val id: String = "",
        val name: String = "",
        val trackIds: List<Long> = emptyList(),
        val createdAt: Long = 0L
    )

    @Serializable
    private data class LegacyData(val items: List<LegacyPlaylist> = emptyList())

    private companion object {
        const val Tag = "PlaylistStore"

        /** Read once, on the one launch that finds a `playlists.json` still there. */
        val LegacyJson = Json { ignoreUnknownKeys = true }

        /** Long enough that a drag down a playlist is one write; short enough to be invisible. */
        const val WriteDebounceMs = 500L
    }
}
