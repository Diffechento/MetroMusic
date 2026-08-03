package com.metromusic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroCrossfade
import com.metrocompose.MetroIcon
import com.metrocompose.MetroProgressBar
import com.metrocompose.MetroRegular
import com.metrocompose.MetroRisingPageState
import com.metrocompose.MetroTheme
import com.metrocompose.TransportButton
import com.metrocompose.metroRiseDrag
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
 * How much of the page's own scrim the strip's band is worth.
 *
 * The player darkens its backdrop with a gradient running from [BackdropScrimTop] at the top of the
 * screen to [BackdropScrimBottom] at the bottom; the strip shows the top tenth of that, where the
 * gradient has barely begun. This is that tenth as one number.
 */
private const val StripScrim = 0.15f

/**
 * The playing album's cover, behind the strip.
 *
 * Handed to `MetroBottomBar` as its background rather than drawn inside [MiniPlayer], because the bar
 * is what owns the surface under the navigation bar: drawn in the strip's own content it would stop
 * at the gesture pill and leave a band of flat colour along the bottom edge — the same seam this is
 * meant to remove, an inch lower.
 *
 * It exists because of what the *rise* looked like without it. The player's backdrop is the cover
 * full-bleed, so a page dropping back into a flat black strip ends by turning the artwork into a black
 * rectangle, and the last frames of the movement are the ugliest ones.
 *
 * The bitmap is handed in rather than loaded here, and it is the very same object the player draws —
 * see [rememberPlayingBackdrop] for why it has to be held by the shell. Two decodes of one album at
 * two sizes would also be two sharpnesses of the same picture, swapped at the moment the page appears.
 *
 * **It is not a picture of its own; it is the top of the page's.** The same square the player draws —
 * the cover at the screen's width, hung from the top of the page, which at rest is the top of the
 * strip. So the strip shows the top of the cover, and pulling the page up draws the rest of it out
 * from underneath without the magnification changing anywhere.
 */
@Composable
fun MiniPlayerBackdrop(artwork: Bitmap?) {
    val colors = MetroTheme.colors

    // The square hangs from the top of the strip, which is where the page rests, so the strip shows
    // the top of the cover and pulling the page up draws the rest of it out from underneath at one
    // magnification. Cropping the cover to the page's *height* instead is what made the strip show an
    // unrecognisable enlargement of one corner: a square stretched over a whole screen is magnified
    // two and a half times, and 300px of that is a fragment of some detail.
    //
    // The wrapper box is not decoration. A child taller than its parent is placed by that parent's
    // alignment, and the bar's own background box does something unhelpful with one — measured, the
    // square came out with its *bottom* on the bar's top edge, which on screen is a two-pixel line of
    // cover and then black. Owning the parent makes the placement ordinary again: this box is exactly
    // the bar, and an oversized child of it hangs downward from its top.
    // `requiredHeight` and not `aspectRatio`, which is the whole bug this went through four rounds of.
    // `aspectRatio` obeys the constraints it is handed: asked for 1080x1080 inside a bar 300px tall it
    // cannot satisfy the ratio, so it falls back to the constrained size — 1080x300 — and `Crop` then
    // centre-crops the cover into that band. The strip showed the *middle* of the artwork while the
    // page, whose constraint is a whole screen, satisfied the ratio and showed the top. Two different
    // parts of one picture, swapped the instant a drag began. Requiring the height makes the square a
    // square wherever it is put, and it is cut off by the bar rather than squashed into it.
    val coverEdge = LocalConfiguration.current.screenWidthDp.dp
    Box(Modifier.fillMaxSize()) {
        MetroCrossfade(
            target = artwork,
            modifier = Modifier
                .fillMaxWidth()
                // Let it be taller than the bar and keep its *top* at the bar's top. Without the
                // unbounded wrap the parent centres an oversized child — measured: the square sat
                // 405px high, which is (1080-270)/2, so the strip showed the middle of the cover
                // while the page showed the top.
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .requiredHeight(coverEdge)
        ) { cover ->
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { alpha = BackdropAlpha }
                )
            }
        }
        // Flat, and *not* the page's vertical gradient: the band the strip shows is the top tenth of
        // that gradient, where it has barely begun, so one value is the same shading with one layer
        // instead of a brush whose span depends on a box being measured and placed exactly right.
        Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = StripScrim)))
    }
}

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
    rising: MetroRisingPageState,
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
        onPrevious = { services.player.previous() },
        canGoNext = { state.hasNext }
    )

    Column(
        modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            // No background of its own: the bar's surface is what carries the colour *and* the
            // playing cover behind it (see [MiniPlayerBackdrop]), and it reaches the bottom edge of
            // the screen where this row stops above the gesture pill. An opaque fill here would
            // simply paint over the picture.
            // Pull the strip up and the player *is* what comes out from under the thumb: the drag
            // owns the page's position for its whole travel, rather than moving the strip a little
            // and handing over to an animation at a threshold. Both halves are still switchable,
            // because a strip swiped by accident while scrolling past it is worse than a strip you
            // have to tap — and both go through one detector, so a diagonal drag picks one of them.
            .metroRiseDrag(
                rising,
                swipe = swipe,
                enabled = settings.gestureStripUp,
                swipeEnabled = settings.gestureStripSwipe
            )
    ) {
        MetroProgressBar(progress)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                // Sideways only: the upward pull is drawn by the page coming up over this strip, not
                // by the strip moving out of the way underneath it.
                .graphicsLayer { translationX = swipe.offset }
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
