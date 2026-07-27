package com.metromusic.ui.screens

import androidx.annotation.StringRes
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.metrocompose.MetroCrossfade
import com.metrocompose.MetroIcon
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSwap
import com.metrocompose.MetroSlider
import com.metrocompose.MetroTheme
import com.metrocompose.TransportButton
import com.metrocompose.metroDismissDown
import com.metrocompose.metroSlideIn
import com.metrocompose.metroSwipe
import com.metrocompose.rememberMetroDismiss
import com.metrocompose.rememberMetroSwipe
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.components.AlbumArt
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.rememberAlbumArt
import com.metromusic.ui.formatDuration

/**
 * Everything in the column that is not the cover, measured: the padding at both ends, the artist
 * and album above the cover, and below it the position bar with its labels, the track title and the
 * button row. The cover gets whatever the window has left over.
 */
private val ChromeHeight = 424.dp

/** The strip to the right of the cover that carries the toggles. */
private val ToggleColumnWidth = 68.dp

/**
 * How a track change plays out, taken frame by frame off a Lumia doing the same thing.
 *
 * The cover flies in from the right (`MetroSlideInMillis`) and the words turn over at the same time.
 *
 * The lead and the stagger are zero, and that is a deliberate departure from the phone. Measured off
 * a Lumia, the text there waits for the artwork to land and then turns over line by line; carried
 * over here, the owner read it as the name belonging to the *previous* track for a moment — the eye
 * takes cover and title as one label, and one half of a label changing before the other reads as a
 * glitch rather than as choreography. Kept as named constants rather than deleted so that the shape
 * is still expressible: raise them and the phone's sequence comes back.
 */
private const val TextLead = 0
private const val TextStagger = 0

/**
 * The full player.
 *
 * Reading order is the WP8 one — who and what above the cover, which track below it — with the
 * artwork sitting in the middle of the text rather than on top of it, and the toggles stacked down
 * the strip beside the cover so the position bar can hug its bottom edge. Only the transport gets
 * rings, because those are the buttons you hit without looking; it sits low, within thumb reach.
 * Nothing carries a caption — captions belong to the app bar, and the player doesn't use one.
 *
 * Dragging sideways moves the foreground at finger speed and the backdrop at a third of it —
 * the same parallax idea as the panorama, applied to one screen. Let go past a quarter of the
 * width and it commits to the next or previous track; anything less springs back.
 */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit = {}) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val state by services.player.state.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val position by services.player.positionFlow().collectAsStateWithLifecycle(0L)

    if (!state.hasTrack) {
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            EmptyNote(stringResource(R.string.player_nothing))
        }
        return
    }

    val trackId = state.trackId ?: -1L

    // Sideways swipes change track. The gesture, the decision and the fly-out all belong to the
    // framework; this screen only says what "next" means and how far each layer follows the finger.
    val swipe = rememberMetroSwipe(
        onNext = { services.player.next() },
        onPrevious = { services.player.previous() },
        // The page comes home instead of being carried off and replaced from the far edge. A swipe
        // between two tracks of one album would otherwise flip the cover and the backdrop for an
        // identical cover and backdrop; pressing "next" changes only what differs, and so does this.
        carryThrough = false
    )
    // Pushing the page down puts it back in the strip it came out of — the gesture that matches the
    // way it arrived, and the one you reach for instead of Back with a thumb on a tall screen.
    val dismiss = rememberMetroDismiss(onDismiss = onCollapse)

    // While the thumb is held, show where it is rather than where playback is.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val playedFraction = when {
        scrubbing != null -> scrubbing!!
        state.durationMs > 0 -> (position.toFloat() / state.durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownPosition =
        if (scrubbing != null) (state.durationMs * playedFraction).toLong() else position

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .metroSwipe(swipe, enabled = settings.gesturePlayerSwipe)
            .metroDismissDown(dismiss, enabled = settings.gesturePlayerDown)
    ) {
        // As wide as the gutter and the toggle strip leave it, and no taller than what the text
        // and the button bar don't need — on a short screen the chrome wins. The backdrop asks for
        // the same size, so the two of them draw one cached bitmap.
        // The box now runs edge to edge, so the gesture bar's height has to come out of the sum by
        // hand — the column that holds the chrome is inset by it.
        val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val coverSize = (maxHeight - ChromeHeight - navigationInset)
            .coerceIn(120.dp, maxWidth - 24.dp - ToggleColumnWidth)
        val loading = rememberAlbumArt(state.albumId, trackId, coverSize)

        // The artwork the backdrop is *currently* able to show. A track change swaps the album id
        // before the new bitmap has been decoded, and drawing that gap is what made the backdrop
        // blink black between tracks; keeping the last picture we actually have means the old one
        // stays until the new one can take over from it.
        var backdrop by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(loading) {
            if (loading != null) backdrop = loading
        }

        // Layer 1 — the backdrop, overscaled so panning never exposes an edge, at a third of the
        // drag speed, and cross-fading from one album to the next with its own small parallax.
        if (backdrop != null) {
            MetroCrossfade(target = backdrop, modifier = Modifier.fillMaxSize()) { artwork ->
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                                translationX = swipe.offset * 0.33f
                                translationY = dismiss.offset * 0.33f
                                alpha = 0.30f
                            }
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(colors.bg.copy(alpha = 0.1f), colors.bg.copy(alpha = 0.95f))
                    )
                )
            )
        }

        // Layer 2 — everything you actually read and touch, at full drag speed.
        //
        // The insets live here rather than on the whole screen: the backdrop above runs to all four
        // edges, and only the things you press are kept clear of the gesture bar.
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = swipe.offset
                    translationY = dismiss.offset
                }
                .navigationBarsPadding()
                .padding(top = 106.dp, bottom = 52.dp)
        ) {
            // The words wait for the artwork to land before they start turning over, then go in
            // sequence down the screen rather than all at once — both measured off the phone, and
            // together they are most of why a track change reads as slow and deliberate there.
            MetroSwap(
                target = state.artist,
                modifier = Modifier.padding(horizontal = 24.dp),
                delayMillis = TextLead
            ) { artist ->
                Text(
                    text = artist,
                    color = colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 30.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MetroSwap(
                target = state.album,
                modifier = Modifier.padding(horizontal = 24.dp),
                delayMillis = TextLead + TextStagger
            ) { album ->
                Text(
                    text = album,
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.padding(start = 24.dp)) {
                Column(Modifier.width(coverSize)) {
                    // No continuum key: the player is an overlay over the navigation host, not a
                    // page inside it, so there is no element on the page below for a shared
                    // element to pair with. Keyed on the album, not the track, because skipping
                    // between tracks of one album leaves the same cover on screen.
                    AlbumArt(
                        albumId = state.albumId,
                        representativeTrackId = trackId,
                        size = coverSize,
                        modifier = Modifier.metroSlideIn(state.albumId)
                    )

                    // A hairline against the bottom edge of the cover, and the time the track has
                    // left rather than a second copy of its duration.
                    MetroSlider(
                        value = playedFraction,
                        onValueChange = { scrubbing = it },
                        onValueChangeFinished = {
                            scrubbing?.let { services.player.seekToFraction(it) }
                            scrubbing = null
                        },
                        trackHeight = 2.dp,
                        thumbSize = null,
                        height = 18.dp
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimeLabel(formatDuration(shownPosition))
                        TimeLabel("-" + formatDuration(state.durationMs - shownPosition))
                    }
                }

                // The toggles stack down the strip beside the cover, gathered at the bottom of it
                // rather than spread over the whole edge — as a cluster they read as one group of
                // switches instead of three unrelated marks.
                Column(
                    Modifier.width(ToggleColumnWidth).height(coverSize),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
                ) {
                    val favorite = trackId in stats.favorites
                    TransportButton(
                        icon = if (favorite) MetroIcon.StarFilled else MetroIcon.Star,
                        contentDescription = stringResource(
                            if (favorite) R.string.menu_unfavorite else R.string.menu_favorite
                        ),
                        iconSize = 23.dp,
                        touchSize = 52.dp,
                        active = favorite
                    ) { services.stats.toggleFavorite(trackId) }
                    TransportButton(
                        icon = MetroIcon.Shuffle,
                        contentDescription = stringResource(
                            if (state.shuffle) R.string.player_shuffle_on else R.string.action_shuffle
                        ),
                        iconSize = 23.dp,
                        touchSize = 52.dp,
                        active = state.shuffle
                    ) { services.player.toggleShuffle() }
                    TransportButton(
                        icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                            MetroIcon.RepeatOne
                        } else {
                            MetroIcon.Repeat
                        },
                        contentDescription = stringResource(repeatLabel(state.repeatMode)),
                        iconSize = 23.dp,
                        touchSize = 52.dp,
                        active = state.repeatMode != Player.REPEAT_MODE_OFF
                    ) { services.player.cycleRepeat() }
                }
            }

            Spacer(Modifier.height(6.dp))

            MetroSwap(
                target = state.title,
                modifier = Modifier.padding(horizontal = 24.dp),
                delayMillis = TextLead + TextStagger * 2
            ) { title ->
                Text(
                    text = title,
                    color = colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.weight(1f))

            // Left-aligned on the text gutter with room to breathe between the rings, low enough
            // that a thumb reaches them without shifting grip.
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                TransportButton(
                    icon = MetroIcon.Previous,
                    contentDescription = stringResource(R.string.action_previous),
                    iconSize = 22.dp,
                    touchSize = 64.dp,
                    ringSize = 60.dp
                ) { services.player.previous() }
                TransportButton(
                    icon = if (state.isPlaying) MetroIcon.Pause else MetroIcon.Play,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.action_pause else R.string.action_play
                    ),
                    iconSize = 22.dp,
                    touchSize = 64.dp,
                    ringSize = 60.dp
                ) { services.player.togglePlayPause() }
                TransportButton(
                    icon = MetroIcon.Next,
                    contentDescription = stringResource(R.string.action_next),
                    iconSize = 22.dp,
                    touchSize = 64.dp,
                    ringSize = 60.dp
                ) { services.player.next() }
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        color = MetroTheme.colors.subtle,
        fontFamily = MetroRegular,
        fontSize = 12.sp
    )
}

@StringRes
private fun repeatLabel(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
    Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
    else -> R.string.player_repeat_off
}
