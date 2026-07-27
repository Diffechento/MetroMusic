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
import com.metromusic.data.store.SettingsStore
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

        // Both of these are read *during* the scan — the duration filter and how a credit is split
        // into artists — so changing either has to re-run it.
        scope.launch {
            settings.settings
                .map { it.minTrackSeconds to it.splitArtistCredits }
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
                    splitCredits = values.splitArtistCredits
                )
                // Album ids can be reused after a media rescan; stale covers would be wrong.
                if (scanned.albums != _library.value.albums) artwork.clear()
                _library.value = scanned
                _report.value = scanner.lastReport
                _loaded.value = true
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
