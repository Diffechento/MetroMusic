package com.metromusic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroBackdrop
import com.metrocompose.MetroBottomBar
import com.metrocompose.MetroNavHost
import com.metrocompose.MetroRegular
import com.metrocompose.MetroRisingPage
import com.metrocompose.MetroTheme
import com.metrocompose.MetroTopBanner
import com.metrocompose.MetroVolumeBanner
import com.metrocompose.rememberMetroBackStack
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.components.AppBackdrop
import com.metromusic.ui.components.LocalOpenLyrics
import com.metromusic.ui.components.LocalOpenPlayer
import com.metromusic.ui.components.MiniPlayer
import com.metromusic.ui.components.MiniPlayerHeight
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen
import com.metromusic.playback.QueueNotice
import com.metromusic.ui.screens.AlbumDetailScreen
import com.metromusic.ui.screens.AlbumEditScreen
import com.metromusic.ui.screens.ArtistDetailScreen
import com.metromusic.ui.screens.CollectionScreen
import com.metromusic.ui.screens.GenreDetailScreen
import com.metromusic.ui.screens.LyricsScreen
import com.metromusic.ui.screens.NowPlayingScreen
import com.metromusic.ui.screens.PlaylistDetailScreen
import com.metromusic.ui.screens.PlaylistsScreen
import com.metromusic.ui.screens.SettingsDetailScreen
import com.metromusic.ui.screens.SettingsScreen

/**
 * The app's shell: a back stack, the turnstile between destinations, the mini player pinned
 * underneath, the full player over the top of all of it, and the volume banner over even that.
 *
 * Adding a screen is a `Screen` entry plus a branch here — the `when` is exhaustive, so the
 * compiler will point at this spot until the new destination is handled.
 *
 * The player is the exception: it is not a destination but an overlay, opened by [LocalOpenPlayer]
 * and closed by Back. It has to be, for two reasons. It comes up out of the mini player rather than
 * swinging in from the side, and it is opened from anywhere — a list, the strip — so making it a
 * page would mean pushing it on top of whatever you were looking at and then tearing that page
 * down. Tearing it down is what made closing the player stutter: coming back re-composed a whole
 * panorama of lists in the frames the animation needed. As an overlay, nothing underneath moves.
 */
@Composable
fun MetroMusicRoot(initialScreen: Screen = Screen.Collection, openPlayerAtStart: Boolean = false) {
    val services = LocalServices.current
    val nav = rememberMetroBackStack(
        initial = initialScreen,
        save = { Screen.encode(it) },
        restore = { Screen.decode(it) }
    )
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val volume by services.volume.state.collectAsStateWithLifecycle()
    var playerOpen by rememberSaveable { mutableStateOf(openPlayerAtStart) }

    // Permission is granted by the time this composes, so it's safe to touch the library.
    LaunchedEffect(Unit) {
        services.library.start()
        services.player.connect()
        // Scrobbling watches the same state flow the UI does rather than polling the player.
        services.scrobbler.attach(services.player.state)
        services.scrobbler.flush()
    }

    // Which songs have lyrics is settled in the background, once per library, so the context menu
    // can answer without going to the network while it is unfolding.
    val library by services.library.library.collectAsStateWithLifecycle()
    LaunchedEffect(library.tracks.size) {
        if (library.tracks.isNotEmpty()) services.lyrics.probe(library.tracks)
    }

    // Nothing to show the player about, so don't let it linger — losing the queue while it is
    // open (the last track ends, the service is stopped) should put you back on the library.
    LaunchedEffect(playerState.hasTrack) {
        if (!playerState.hasTrack) playerOpen = false
    }

    // One backdrop for the whole app: pages inside a MetroBackdrop leave their own background off, so
    // the gradient (or the cover, if settings say so) stays put while pages come and go over it
    // instead of the panorama having a wallpaper and every detail page dropping to flat black.
    MetroBackdrop(backdrop = { AppBackdrop() }) {
        Box(Modifier.fillMaxSize()) {
        // No inset here on purpose: every page fills the window and the framework's own
        // components — MetroPage, MetroPanorama, MetroBottomBar — keep their *content* clear of the
        // gesture bar while their surfaces reach the bottom edge. Padding the whole column instead
        // is what left a strip of bare background under the panorama's backdrop.
        Column(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalOpenPlayer provides { playerOpen = true },
                LocalOpenLyrics provides { trackId -> nav.push(Screen.Lyrics(trackId)) }
            ) {
                MetroNavHost(nav, Modifier.weight(1f)) { screen ->
                    when (screen) {
                        Screen.Collection -> CollectionScreen(onNavigate = nav::push)
                        Screen.Playlists -> PlaylistsScreen(onNavigate = nav::push)
                        Screen.Settings -> SettingsScreen(onNavigate = nav::push)
                        is Screen.SettingsDetail -> SettingsDetailScreen(screen.page)
                        is Screen.AlbumDetail ->
                            AlbumDetailScreen(screen.albumId, onNavigate = nav::push)
                        is Screen.AlbumEdit ->
                            AlbumEditScreen(screen.albumId, onDone = { nav.pop() })
                        is Screen.ArtistDetail ->
                            ArtistDetailScreen(screen.artistId, onNavigate = nav::push)
                        is Screen.GenreDetail -> GenreDetailScreen(screen.genre)
                        is Screen.PlaylistDetail -> PlaylistDetailScreen(screen.playlistId)
                        is Screen.Lyrics -> LyricsScreen(screen.trackId)
                    }
                }
            }

            // Tied to whether there is a track, and *not* to whether the player is open.
            //
            // It used to be both, and that is what made opening and closing the player feel ragged:
            // hiding the strip animates its height, which re-measures the page above it — a panorama
            // of lists — on every frame of the rise, and the strip the page is supposed to drop back
            // into was shrinking while the page slid towards where it used to be. The player covers
            // the strip completely for the whole animation anyway, so there is nothing to hide.
            MetroBottomBar(visible = playerState.hasTrack) {
                MiniPlayer(state = playerState, onOpen = { playerOpen = true })
            }
        }

        // Full bleed: the player's artwork backdrop reaches the bottom edge of the screen rather
        // than stopping at the gesture bar and leaving a strip of app background — which is exactly
        // what it looked like on a phone. The screen insets its own controls instead, and
        // MetroRisingPage adds the navigation bar to the strip height it rises out of.
            MetroRisingPage(visible = playerOpen, fromHeight = MiniPlayerHeight) {
                NowPlayingScreen(onCollapse = { playerOpen = false })
            }
        }
    }

    // The volume keys are consumed by the activity so Android's own panel never appears; this is
    // what appears instead. What is playing is named under the bar, and nothing more: the banner
    // arrives because a volume key was pressed, so the buttons it used to carry sat directly under a
    // thumb that was already on the side of the phone, one tap from skipping the track you were
    // turning up. The strip and the player are where the transport lives.
    MetroVolumeBanner(
        visible = volume.showing,
        level = volume.level,
        onHide = { services.volume.hide() },
        value = volume.step.toString(),
        muted = volume.muted,
        resetKey = volume.step,
        media = if (playerState.hasTrack) {
            {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = playerState.title,
                        color = MetroTheme.colors.fg,
                        fontFamily = MetroRegular,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playerState.artist,
                        color = MetroTheme.colors.subtle,
                        fontFamily = MetroRegular,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            null
        }
    )

    // "Play next" has nothing on screen to show for itself — the page you are on does not change and
    // neither does the song — so it says so here. Which matters most when queueing several albums in
    // a row, where the only other feedback is three minutes of waiting.
    //
    // Suppressed while the volume banner is up: they are both the same strip in the same place, and
    // the volume one is answering a key that is being held right now.
    var queued by remember { mutableStateOf<QueueNotice?>(null) }
    LaunchedEffect(Unit) {
        services.player.queued.collect { queued = it }
    }
    val notice = queued
    MetroTopBanner(
        visible = notice != null && !volume.showing,
        onHide = { queued = null },
        resetKey = notice?.sequence,
        lingerMillis = QueuedHintMillis
    ) {
        Text(
            text = stringResource(R.string.queued_heading),
            color = MetroTheme.colors.subtle,
            fontFamily = MetroRegular,
            fontSize = 11.sp
        )
        Text(
            text = notice?.label.orEmpty(),
            color = MetroTheme.colors.fg,
            fontFamily = MetroRegular,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (notice != null && notice.count > 1) {
            Text(
                text = formatTrackCount(notice.count),
                color = MetroTheme.colors.dim,
                fontFamily = MetroRegular,
                fontSize = 12.sp
            )
        }
    }

    // Composed after the nav host, so while the player is open this takes Back first and the
    // back stack underneath keeps its place.
    BackHandler(enabled = playerOpen) { playerOpen = false }

    // Leaving takes two presses. At the root of the stack a single stray Back — and the gesture is
    // easy to trigger by accident on a tall phone — would otherwise close a music app mid-song.
    //
    // The second press is not handled here at all: arming the guard *disables* this handler, so Back
    // falls through to the system and closes the activity the way it normally would. No `finish()`,
    // and nothing to get wrong about which press was which.
    var leaving by remember { mutableStateOf(false) }
    BackHandler(enabled = !playerOpen && !nav.canGoBack && !leaving) { leaving = true }
    MetroTopBanner(visible = leaving, onHide = { leaving = false }, lingerMillis = LeaveHintMillis) {
        Text(
            text = stringResource(R.string.press_back_again),
            color = MetroTheme.colors.fg,
            fontFamily = MetroRegular,
            fontSize = 16.sp
        )
    }
}

/** How long the "press back again" hint stays up, and therefore the window for that press. */
private const val LeaveHintMillis = 2200

/** Shorter than that: it is a receipt, not a question, and the next album is one tap away. */
private const val QueuedHintMillis = 1600
