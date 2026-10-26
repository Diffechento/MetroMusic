package com.metromusic.data.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.metromusic.data.media.ArtworkLoader
import com.metromusic.data.media.AudioPaths
import com.metromusic.data.media.CoverQuery
import com.metromusic.data.media.FolderScanner
import com.metromusic.data.media.MediaStoreScanner
import com.metromusic.data.model.Library
import com.metromusic.data.model.hiding
import com.metromusic.data.model.mergingGenres
import com.metromusic.data.store.GenreStore
import com.metromusic.data.store.HiddenStore
import com.metromusic.data.store.Settings
import com.metromusic.data.store.SettingsStore
import com.metromusic.data.store.StatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the in-memory library and keeps it in step with the device.
 *
 * A [ContentObserver] means there's no "refresh" button to remember: ripping a CD or deleting
 * a folder shows up on its own. Notifications arrive in bursts during a media sweep, so the
 * rescan is debounced rather than run per row.
 */
class LibraryRepository(
    private val context: Context,
    private val scanner: MediaStoreScanner,
    private val folderScanner: FolderScanner,
    private val paths: AudioPaths,
    private val settings: SettingsStore,
    private val hidden: HiddenStore,
    private val genres: GenreStore,
    private val artwork: ArtworkLoader,
    private val stats: StatsStore,
    private val scope: CoroutineScope
) {
    /** What the device actually holds, before anything the user asked to hide is taken out. */
    private val _library = MutableStateFlow(Library.Empty)

    /**
     * The library everything else sees. Hiding is applied here, once, so no list has to remember to
     * check — and unhiding something puts it back everywhere on the next frame without a rescan.
     */
    val library: StateFlow<Library> = combine(
        _library,
        hidden.hidden,
        genres.merges,
        settings.settings
    ) { scanned, hide, merges, values ->
        scanned
            .mergingGenres(values.fixGenreDoubling, merges.aliases)
            .hiding(hide.artists, hide.albums)
    }.stateIn(scope, SharingStarted.Eagerly, Library.Empty)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** False until the first scan finishes, so the UI can tell "empty" from "not looked yet". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * What the last scan saw, for the library page to show.
     *
     * "The app cannot see my files" is otherwise unanswerable from inside the app: the store either
     * has a row or it does not, and the app either kept it or dropped it, and none of that is visible.
     * Three numbers make it visible.
     */
    private val _report = MutableStateFlow(MediaStoreScanner.Report())
    val report: StateFlow<MediaStoreScanner.Report> = _report.asStateFlow()

    /** How each library folder fared on the last scan — see [FolderScanner]. */
    private val _folders = MutableStateFlow<List<FolderScanner.Folder>>(emptyList())
    val folders: StateFlow<List<FolderScanner.Folder>> = _folders.asStateFlow()

    private var scanJob: Job? = null
    private var started = false

    init {
        // What an album id is *called*, which is what an online cover lookup has to go on. The loader
        // cannot hold the library — the library is built on top of the loader — so the question is
        // answered by a lookup pointed back here.
        artwork.albumNames = { id ->
            val current = library.value
            current.album(id)?.let { album ->
                val tracks = current.tracksOf(album)
                val sample = tracks.firstOrNull()
                    ?.takeIf { first -> tracks.all { it.artistId == first.artistId } }
                CoverQuery(
                    artist = album.artist,
                    album = album.title,
                    sampleTitle = sample?.title,
                    sampleArtist = sample?.artist
                )
            }
        }
        // MediaStore's id for one of ours, for the legacy albumart URI. The first track answers for
        // the album — an album merged out of several store rows takes the art of the row its first
        // track sits in, which is the same choice [representativeTrackId] already makes.
        artwork.mediaAlbumId = { id ->
            val current = library.value
            current.album(id)?.let { album ->
                current.tracksOf(album).firstOrNull()?.mediaAlbumId
            }
        }
        // A library folder's track has no row in the store to ask for a cover, so the loader reads
        // its file instead. The scanned library rather than the visible one: a cover is still a
        // cover while its artist is hidden.
        artwork.folderTrack = { id -> _library.value.track(id)?.takeIf { it.fromFolder } }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = rescan(debounce = true)
    }

    /**
     * A drive arriving or leaving. MediaStore says nothing about a volume it does not index, which
     * is the whole reason the library folders exist, so the volumes are watched directly — through
     * the storage callback from Android 11, which is what foobar2000 moved to for external disks,
     * and through the mount broadcasts below it.
     */
    private fun onVolumeChanged() {
        if (settings.settings.value.musicFolders.isNotEmpty()) rescan(debounce = true)
    }

    private val volumeCallback: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = onVolumeChanged()
            }
        } else {
            null
        }

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onVolumeChanged()
    }

    /**
     * Call once the audio permission is granted. Safe to call repeatedly — only the first
     * call does anything.
     */
    fun start() {
        if (started) return
        started = true

        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(StorageManager::class.java).registerStorageVolumeCallback(
                    ContextCompat.getMainExecutor(context),
                    volumeCallback as StorageManager.StorageVolumeCallback
                )
            }.onFailure { Log.w(Tag, "No storage callback", it) }
        } else {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addDataScheme("file")
            }
            ContextCompat.registerReceiver(
                context,
                mountReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // Every one of these is read *during* the scan — the duration filter, how a credit is split
        // into artists, whether the album artist tag is believed, whether it is the only thing
        // artists are built from, which of its names then wins, and whether two spellings of a name
        // are one artist — so changing any of them has to re-run it.
        scope.launch {
            settings.settings
                .map { ScanInputs(it) }
                .distinctUntilChanged()
                .collect { rescan(debounce = false) }
        }
    }

    fun rescan(debounce: Boolean = false) {
        scanJob?.cancel()
        scanJob = scope.launch {
            if (debounce) delay(DebounceMs)
            _scanning.value = true
            try {
                val values = settings.settings.value
                // The store's paths afresh first: they are what keeps a folder the store also
                // indexes from listing its songs twice.
                paths.refresh()
                val fromFolders = folderScanner.scan(values.musicFolders, paths::isInStore)
                paths.setFolderPaths(fromFolders.paths)
                _folders.value = fromFolders.folders
                val scanned = scanner.scan(
                    minDurationMs = values.minTrackSeconds * 1000L,
                    splitCredits = values.splitArtistCredits,
                    useAlbumArtist = values.useAlbumArtist,
                    albumArtistOnly = values.artistsFromAlbumArtist,
                    preferKnownArtist = values.preferKnownArtist,
                    fixArtistDoubling = values.fixArtistDoubling,
                    extra = fromFolders.tracks
                )
                // Album ids can be reused after a media rescan; stale covers would be wrong.
                if (scanned.albums != _library.value.albums) artwork.clear()
                _library.value = scanned
                _report.value = scanner.lastReport
                _loaded.value = true
                // History rows written under MediaStore's album ids follow their albums to the
                // tag-derived ones, so the history section survives the id scheme changing under it.
                // A no-op once nothing in the history carries a store id any more.
                stats.migrateAlbumIds(
                    scanned.tracks.asSequence()
                        .filter { it.albumId != it.mediaAlbumId }
                        .associate { it.mediaAlbumId to it.albumId }
                )
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Adds a folder the system's picker handed over. The grant has to be taken as persistable or it
     * lasts until the process dies — and a USB drive's tree survives being unplugged, so the same
     * grant reads the same drive the next time it is plugged in.
     */
    fun addFolder(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.recoverCatching {
            // Some providers grant reading only; that is still a library folder.
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { Log.w(Tag, "Cannot hold on to $tree", it) }
        settings.addMusicFolder(tree.toString())
    }

    /** Adds a folder by path — a drive found with All Files Access on, which needs no grant. */
    fun addFolder(path: String) = settings.addMusicFolder(path)

    fun removeFolder(entry: String) {
        settings.removeMusicFolder(entry)
        if (entry.startsWith("/")) return
        val values = settings.settings.value
        // The same tree can be the playlists' or the lyrics' folder too, and those still need it.
        if (entry == values.playlistFolderUri || entry == values.lyricsFolderUri) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(entry),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun dispose() {
        if (!started) return
        context.contentResolver.unregisterContentObserver(observer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(StorageManager::class.java).unregisterStorageVolumeCallback(
                    volumeCallback as StorageManager.StorageVolumeCallback
                )
            }
        } else {
            runCatching { context.unregisterReceiver(mountReceiver) }
        }
        started = false
    }

    private companion object {
        const val Tag = "LibraryRepository"

        /** MediaStore fires a burst of notifications during a sweep; wait for it to settle. */
        const val DebounceMs = 1500L
    }
}

/**
 * The settings a scan reads, so that a change to one of them — and nothing else — re-runs it.
 *
 * A data class rather than a tuple because there is no `Quadruple`, and because the next setting the
 * scanner comes to read should be added here rather than turning this into a list of anonymous
 * booleans nobody can tell apart.
 */
private data class ScanInputs(
    val minTrackSeconds: Int,
    val splitArtistCredits: Boolean,
    val useAlbumArtist: Boolean,
    val artistsFromAlbumArtist: Boolean,
    val preferKnownArtist: Boolean,
    val fixArtistDoubling: Boolean,
    val musicFolders: List<String>
) {
    constructor(settings: Settings) : this(
        settings.minTrackSeconds,
        settings.splitArtistCredits,
        settings.useAlbumArtist,
        settings.artistsFromAlbumArtist,
        settings.preferKnownArtist,
        settings.fixArtistDoubling,
        settings.musicFolders
    )
}
