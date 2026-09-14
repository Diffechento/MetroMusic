package com.metromusic.data.library

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.metromusic.data.media.ArtworkLoader
import com.metromusic.data.media.CoverQuery
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

    private var scanJob: Job? = null
    private var started = false

    init {
        // What an album id is *called*, which is what an online cover lookup has to go on. The loader
        // cannot hold the library — the library is built on top of the loader — so the question is
        // answered by a lookup pointed back here.
        artwork.albumNames = { id ->
            val current = library.value
            current.album(id)?.let { album ->
                CoverQuery(
                    artist = album.artist,
                    album = album.title,
                    sampleTitle = current.tracksOf(album).firstOrNull()?.title
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
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = rescan(debounce = true)
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

        // Every one of these is read *during* the scan — the duration filter, how a credit is split
        // into artists, whether the album artist tag is believed, whether it is the only thing
        // artists are built from, and which of its names then wins — so changing any of them has to
        // re-run it.
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
                val scanned = scanner.scan(
                    minDurationMs = values.minTrackSeconds * 1000L,
                    splitCredits = values.splitArtistCredits,
                    useAlbumArtist = values.useAlbumArtist,
                    albumArtistOnly = values.artistsFromAlbumArtist,
                    preferKnownArtist = values.preferKnownArtist
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

    fun dispose() {
        if (!started) return
        context.contentResolver.unregisterContentObserver(observer)
        started = false
    }

    private companion object {
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
    val preferKnownArtist: Boolean
) {
    constructor(settings: Settings) : this(
        settings.minTrackSeconds,
        settings.splitArtistCredits,
        settings.useAlbumArtist,
        settings.artistsFromAlbumArtist,
        settings.preferKnownArtist
    )
}
