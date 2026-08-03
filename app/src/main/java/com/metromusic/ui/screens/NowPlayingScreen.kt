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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.metrocompose.MetroCrossfade
import com.metrocompose.MetroIcon
import com.metrocompose.MetroRegular
import com.metrocompose.MetroPageSwipeState
import com.metrocompose.MetroRisingPageState
import com.metrocompose.MetroSwap
import com.metrocompose.MetroSlider
import com.metrocompose.MetroTheme
import com.metrocompose.TransportButton
import com.metrocompose.metroRiseDrag
import com.metrocompose.metroSlideIn
import com.metrocompose.rememberMetroPageSwipe
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.components.AlbumArt
import com.metromusic.ui.components.BackdropAlpha
import com.metromusic.ui.components.BackdropScrimBottom
import com.metromusic.ui.components.BackdropScrimTop
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
 * Dragging sideways moves the page at finger speed. Let go past a quarter of the width and it commits
 * to the next or previous track; anything less springs back. At the end of the queue there is nothing
 * to commit to, and the page says so by barely moving.
 *
 * The backdrop used to lag behind at a third of that, for the panorama's sense of depth. It cannot any
 * more: the cover is now drawn at exactly the screen's width so that the strip can show the top of the
 * same square, and at that scale there is no slack at the edges for anything to travel into.
 *
 * **The downward gesture is not this screen's any more.** Pushing the player away moves the whole
 * rising page, so the drag belongs to [rising] and the page itself is what follows the finger — see
 * [MetroRisingPageState]. This screen only lends its surface to the gesture, and both axes go through
 * one detector so a diagonal thumb cannot start a track change and a dismissal at once.
 */
@Composable
fun NowPlayingScreen(rising: MetroRisingPageState, backdrop: Bitmap?) {
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

    // Sideways drags page between the tracks of the queue, and the neighbours are on screen the whole
    // time: what arrives is what was visibly coming, and letting go only finishes a movement already
    // made. The page it lands on is a queue position, so the swipe commits with `skipToQueueIndex`
    // rather than with "next" — it has shown you the face you are landing on, so it must land there.
    //
    // It replaces a swipe that moved the whole screen under the finger and then, on release, sprang
    // *back* to the middle while the content changed on its own schedule underneath: the finger went
    // one way, the page went the other, and the change was a third movement the hand had no part in.
    //
    // `slideEpoch` counts the changes this gesture caused. The face reads it as a `key`, so a track
    // that arrived by sliding does not *also* fade and fly its cover in — while a track that arrived
    // any other way (a button, the last one ending) keeps that choreography, which is what it is for.
    var slideEpoch by remember { mutableIntStateOf(0) }
    val pager = rememberMetroPageSwipe(
        index = state.queueIndex,
        previousIndex = state.previousIndex,
        nextIndex = state.nextIndex,
        onSettleTo = { index ->
            slideEpoch++
            services.player.skipToQueueIndex(index)
        }
    )

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
            // One detector, both axes, the axis decided once per gesture — and the vertical one is
            // the page's own position rather than a nudge that triggers an animation afterwards.
            .metroRiseDrag(
                rising,
                pager,
                enabled = settings.gesturePlayerDown,
                swipeEnabled = settings.gesturePlayerSwipe
            )
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

        // The neighbours' covers, decoded before a finger asks for them. They are composed only while
        // a swipe is in flight, so without this the first frames of every drag would carry a blank
        // square in from the edge and fill it in a moment later — the same defect the strip's backdrop
        // had, for the same reason. Two extra decodes at the player's own size while the player is
        // open, which the artwork cache is capped for; at rest nothing here holds them.
        val coverPx = with(LocalDensity.current) { coverSize.roundToPx() }
        LaunchedEffect(state.previous?.albumId, state.next?.albumId, coverPx) {
            state.previous?.let { services.artwork.load(it.albumId, it.trackId, coverPx) }
            state.next?.let { services.artwork.load(it.albumId, it.trackId, coverPx) }
        }

        // Layer 1 — the backdrop, overscaled so panning never exposes an edge, at a third of the
        // drag speed, and cross-fading from one album to the next with its own small parallax.
        //
        // [backdrop] is handed in by the shell and is the same object the strip is drawing. It used to
        // be remembered here, with the last decoded cover kept so a track change did not blink black —
        // which is still needed and now happens out there instead. In here it could not work: this page
        // does not exist while it rests in the strip, so that state was rebuilt from nothing on every
        // pull, the first frames of the gesture had no bitmap, and the cover arrived a moment later
        // through the cross-fade. On screen that is the backdrop changing as you drag.
        if (backdrop != null) {
            MetroCrossfade(target = backdrop, modifier = Modifier.fillMaxSize()) { artwork ->
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // A node that *is* the square: as wide as the screen, as tall as it is wide,
                        // at the top of the page — see the note on [BackdropAlpha]. Cropped to fill
                        // the whole page it was magnified two and a half times, which is invisible
                        // here and unrecognisable in the strip that shows one band of it. Expressed
                        // as layout rather than as a painter's alignment, because the strip has to
                        // put the same square in the same place and that has to be exact.
                        //
                        // No sideways parallax any more: at this scale the picture is exactly as wide
                        // as the screen and any travel would expose an edge. The vertical parallax
                        // went earlier, when the page itself started doing the whole travel.
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .graphicsLayer { alpha = BackdropAlpha }
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.bg.copy(alpha = BackdropScrimTop),
                                colors.bg.copy(alpha = BackdropScrimBottom)
                            )
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
                .navigationBarsPadding()
                .padding(top = 106.dp, bottom = 52.dp)
        ) {
            // The words wait for the artwork to land before they start turning over, then go in
            // sequence down the screen rather than all at once — both measured off the phone, and
            // together they are most of why a track change reads as slow and deliberate there. That
            // is for a change nobody dragged for; a slid one is already a movement, so the `key` on
            // `slideEpoch` rebuilds the swap and it starts on the new words instead of turning over.
            PagedFace(
                pager = pager,
                current = state.artist,
                previous = state.previous?.artist,
                next = state.next?.artist,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) { artist, slot ->
                if (slot == 0) {
                    key(slideEpoch) {
                        MetroSwap(target = artist, delayMillis = TextLead) { FaceLine(it, 30.sp, colors.fg) }
                    }
                } else {
                    FaceLine(artist, 30.sp, colors.fg)
                }
            }
            PagedFace(
                pager = pager,
                current = state.album,
                previous = state.previous?.album,
                next = state.next?.album,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) { album, slot ->
                if (slot == 0) {
                    key(slideEpoch) {
                        MetroSwap(target = album, delayMillis = TextLead + TextStagger) {
                            FaceLine(it, 19.sp, colors.subtle)
                        }
                    }
                } else {
                    FaceLine(album, 19.sp, colors.subtle)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.padding(start = 24.dp)) {
                Column(Modifier.width(coverSize)) {
                    // No continuum key: the player is an overlay over the navigation host, not a
                    // page inside it, so there is no element on the page below for a shared
                    // element to pair with. `metroSlideIn` is keyed on the album, not the track,
                    // because skipping between tracks of one album leaves the same cover on screen —
                    // and it is skipped altogether for a cover that arrived by sliding, which has
                    // just travelled the width of the screen under the finger.
                    PagedFace(
                        pager = pager,
                        current = state.face,
                        previous = state.previous,
                        next = state.next
                    ) { face, slot ->
                        if (slot == 0) {
                            key(slideEpoch) {
                                AlbumArt(
                                    albumId = face.albumId,
                                    representativeTrackId = face.trackId,
                                    size = coverSize,
                                    modifier = Modifier.metroSlideIn(face.albumId)
                                )
                            }
                        } else {
                            AlbumArt(
                                albumId = face.albumId,
                                representativeTrackId = face.trackId,
                                size = coverSize
                            )
                        }
                    }

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

            PagedFace(
                pager = pager,
                current = state.title,
                previous = state.previous?.title,
                next = state.next?.title,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) { title, slot ->
                if (slot == 0) {
                    key(slideEpoch) {
                        MetroSwap(target = title, delayMillis = TextLead + TextStagger * 2) {
                            FaceLine(it, 22.sp, colors.fg)
                        }
                    }
                } else {
                    FaceLine(title, 22.sp, colors.fg)
                }
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

/**
 * One element of the track's face — a line of text, the cover — drawn for the current track and, while
 * a swipe is in flight, for the neighbours either side, each translated to its own slot.
 *
 * Only the *face* is built out of these. The slider, the times, the toggles and the transport are not:
 * they belong to the player rather than to the track, and a swipe that carried the play button off the
 * screen with the artwork would be moving the furniture to change a record. That is also why this is a
 * wrapper per element instead of one layer around everything — the face and the chrome are interleaved
 * down the same column, and the column's own order is what puts the slider against the cover's bottom
 * edge.
 *
 * The neighbours are composed only while the pager says so, because each of them decodes a cover at the
 * player's own size: three at once for the length of a gesture is worth it, three at rest is not.
 *
 * [content] is given the slot it is drawing so that the current one can keep the choreography a track
 * change has when nobody swiped for it, and the neighbours — which are already sliding — can do without.
 */
/** One line of the face, so the three slots of a [PagedFace] cannot drift apart in style. */
@Composable
private fun FaceLine(text: String, size: TextUnit, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = MetroRegular,
        fontSize = size,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun <T> PagedFace(
    pager: MetroPageSwipeState,
    current: T,
    previous: T?,
    next: T?,
    modifier: Modifier = Modifier,
    content: @Composable (value: T, slot: Int) -> Unit
) {
    Box(modifier) {
        if (pager.active) {
            previous?.let { value ->
                Box(Modifier.graphicsLayer { translationX = pager.offsetForSlot(-1) }) {
                    content(value, -1)
                }
            }
            next?.let { value ->
                Box(Modifier.graphicsLayer { translationX = pager.offsetForSlot(1) }) {
                    content(value, 1)
                }
            }
        }
        Box(Modifier.graphicsLayer { translationX = pager.offsetForSlot(0) }) {
            content(current, 0)
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
