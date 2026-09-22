package com.metromusic.core

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.metromusic.data.lastfm.LovesSync
import com.metromusic.data.lastfm.Scrobbler
import com.metromusic.data.library.LibraryRepository
import com.metromusic.data.lyrics.LyricsRepository
import com.metromusic.data.media.ArtworkLoader
import com.metromusic.data.media.AudioPaths
import com.metromusic.data.media.MediaStoreScanner
import com.metromusic.data.media.OnlineArtwork
import com.metromusic.data.store.GenreStore
import com.metromusic.data.store.HiddenStore
import com.metromusic.data.store.PlaybackStateStore
import com.metromusic.data.playlist.PlaylistStore
import com.metromusic.data.playlist.PlaylistTrackInfo
import com.metromusic.data.store.SettingsStore
import com.metromusic.data.store.StatsStore
import com.metromusic.playback.AudioEffects
import com.metromusic.playback.PlayerController
import com.metromusic.playback.VolumeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The composition root: every singleton the app needs, created lazily so nothing is built
 * until something actually asks for it. Adding a dependency means adding one `by lazy` line
 * here and reading it through [LocalServices].
 *
 * No DI framework on purpose — the graph is small enough that the wiring is shorter than the
 * annotations would be, and it costs nothing at startup or in APK size.
 */
class Services(context: Context) {

    val appContext: Context = context.applicationContext

    /** Application-lifetime scope for work that must outlive any one screen. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore by lazy { SettingsStore(appContext, scope) }

    /** Whether the network is usable, so the scrobble queue can go up the moment it is. */
    val connectivity: Connectivity by lazy { Connectivity(appContext) }

    /**
     * Where every track's file is, read once off the volume. Shared rather than built twice: both
     * the `.lrc` sidecar and the `.m3u` line are questions about paths, and the answer is one cursor
     * over the whole library.
     */
    val paths: AudioPaths by lazy { AudioPaths(appContext) }

    /**
     * Playlists, as `.m3u` files. Pointed back at the library for the `#EXTINF` lines rather than
     * handed it, for the reason [ArtworkLoader.albumNames] is: the library is built on top of the
     * stores and cannot be a constructor argument to one of them.
     */
    val playlists: PlaylistStore by lazy {
        PlaylistStore(appContext, scope, settings, paths).also { store ->
            store.trackInfo = { id ->
                library.library.value.track(id)?.let {
                    PlaylistTrackInfo(it.title, it.artist, it.durationMs)
                }
            }
        }
    }

    val stats: StatsStore by lazy { StatsStore(appContext, scope) }

    val onlineArtwork: OnlineArtwork by lazy { OnlineArtwork(appContext, scope, settings) }

    /**
     * Album art. The online fallback is handed over here rather than taken in the constructor, and the
     * album-name lookup it needs is filled in by [LibraryRepository] — which is built on top of this,
     * so it cannot be a constructor argument in either direction.
     */
    val artwork: ArtworkLoader by lazy {
        ArtworkLoader(appContext).also { it.online = onlineArtwork }
    }

    val hidden: HiddenStore by lazy { HiddenStore(appContext, scope) }

    val genres: GenreStore by lazy { GenreStore(appContext, scope) }

    val library: LibraryRepository by lazy {
        LibraryRepository(
            context = appContext,
            scanner = MediaStoreScanner(appContext),
            settings = settings,
            hidden = hidden,
            genres = genres,
            artwork = artwork,
            stats = stats,
            scope = scope
        )
    }

    /** The queue as it was left, so a launch resumes where the last one stopped. */
    val playbackState: PlaybackStateStore by lazy { PlaybackStateStore(appContext, scope) }

    val player: PlayerController by lazy {
        PlayerController(appContext, scope, stats, playbackState, settings).also { player ->
            // Pointed back at the library rather than handed it: the library is built on top of the
            // player's own stores, so it cannot be a constructor argument, and the lambda is not
            // called until a file arrives from another app. Same shape as [ArtworkLoader.albumNames].
            player.trackById = { id -> library.library.value.track(id) }
        }
    }

    /**
     * The equalizer, and the audio session the player is told to use. Built eagerly enough that
     * [PlaybackService] can read the session id while assembling its player — see [AudioEffects].
     */
    val effects: AudioEffects by lazy { AudioEffects(appContext, scope, settings) }

    val volume: VolumeController by lazy { VolumeController(appContext) }

    val lyrics: LyricsRepository by lazy {
        LyricsRepository(appContext, scope, settings, paths, connectivity)
    }

    val scrobbler: Scrobbler by lazy { Scrobbler(appContext, scope, settings, connectivity) }

    /** Favourites and Last.fm's loved tracks, kept the same when the user asks for it. */
    val loves: LovesSync by lazy {
        LovesSync(appContext, scope, settings, stats, library, connectivity)
    }
}

/**
 * Reaches the graph from composables. Provided once in `MainActivity`; failing loudly beats
 * silently constructing a second copy of everything.
 */
val LocalServices = staticCompositionLocalOf<Services> {
    error("LocalServices not provided — wrap the content in CompositionLocalProvider(LocalServices provides …)")
}
