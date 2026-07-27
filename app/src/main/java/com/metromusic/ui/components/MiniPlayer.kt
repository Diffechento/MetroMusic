package com.metromusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroIcon
import com.metrocompose.MetroProgressBar
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metrocompose.TransportButton
import com.metrocompose.metroDismissDown
import com.metrocompose.metroSwipe
import com.metrocompose.rememberMetroReveal
import com.metrocompose.rememberMetroSwipe
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.playback.PlayerState

/**
 * The strip's exact height: the progress hairline plus the row under it.
 *
 * Fixed rather than measured because the shell needs the number before it lays anything out — the
 * player rises out of this strip, so its transition has to know how tall the strip is.
 */
val MiniPlayerHeight = 75.dp

/**
 * The strip that sits under every library screen once something is playing.
 *
 * Position is collected here and nowhere else, so the 2 Hz tick only runs while this is on
 * screen. Tapping anywhere but the play button opens the full player, and swiping it sideways
 * changes track — the same gesture as in the player itself, from the framework, so the strip's
 * content follows the finger and flies out rather than jumping to the next song.
 *
 * The cover is optional (settings → interface): on a strip this short it is the one element that can
 * be dropped without losing anything you need, and some people want the title to have the room.
 *
 * No continuum here: the mini player lives outside the navigation host's shared-transition
 * layout, so there is nothing for a shared element to pair with. The cover still flows into
 * the full player when you come from an album page, where both ends are inside the host.
 */
@Composable
fun MiniPlayer(
    state: PlayerState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val position by services.player.positionFlow().collectAsStateWithLifecycle(0L)
    val settings by services.settings.settings.collectAsStateWithLifecycle()

    val progress = if (state.durationMs > 0) {
        (position.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    val swipe = rememberMetroSwipe(
        onNext = { services.player.next() },
        onPrevious = { services.player.previous() }
    )
    // Pull the strip up and the player comes out of it — the gesture that matches how the page
    // arrives, and the inverse of pushing that page back down. Both are switchable, because a strip
    // swiped by accident while scrolling past it is worse than a strip you have to tap.
    val reveal = rememberMetroReveal(onReveal = onOpen)

    Column(
        modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .background(colors.bg)
            .metroSwipe(swipe, enabled = settings.gestureStripSwipe)
            .metroDismissDown(reveal, enabled = settings.gestureStripUp)
    ) {
        MetroProgressBar(progress)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .graphicsLayer {
                    translationX = swipe.offset
                    translationY = reveal.offset
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (settings.stripArtwork) {
                AlbumArt(
                    albumId = state.albumId,
                    representativeTrackId = state.trackId ?: -1L,
                    size = 52.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    color = colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.artist,
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Drawn, not typed: the ❚❚ that used to be here came from a fallback font with a
            // much taller ink box than ▶, so the strip's button changed size when you paused it.
            TransportButton(
                icon = if (state.isPlaying) MetroIcon.Pause else MetroIcon.Play,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.action_pause else R.string.action_play
                ),
                iconSize = 17.dp,
                touchSize = 44.dp
            ) { services.player.togglePlayPause() }
        }
    }
}
