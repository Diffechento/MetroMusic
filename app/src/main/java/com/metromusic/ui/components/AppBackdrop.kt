package com.metromusic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroLight
import com.metrocompose.MetroTheme
import com.metromusic.core.LocalServices

/**
 * The one background the whole app sits on.
 *
 * Installed once by the shell through `MetroBackdrop`, and handed to the home panorama as its
 * parallaxing background layer as well — the same drawing in both places, so going from the panorama
 * into an album keeps the wallpaper it had instead of dropping to flat black. Only the movement
 * differs: under the panorama it pans with the finger, under a page it stays put.
 *
 * Which drawing it is follows settings: the playing album's cover when "album art behind the
 * panorama" is on, and the gradient otherwise.
 */
@Composable
fun AppBackdrop() {
    val services = LocalServices.current
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()

    val cover = rememberAlbumArt(
        albumId = playerState.albumId,
        representativeTrackId = playerState.trackId ?: -1L,
        size = 260.dp
    ).takeIf { settings.artworkBackground }

    if (cover != null) ArtworkBackdrop(cover) else GradientBackdrop()
}

/**
 * A wide gradient with an oversized note bleeding off the edge.
 *
 * Starts and ends on the same colour on purpose: a wrapping panorama tiles its backdrop, and a
 * gradient whose two ends differ shows a hard edge where the copies meet.
 */
@Composable
private fun GradientBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF10242C),
                        Color(0xFF0A0A0A),
                        Color(0xFF241019),
                        Color(0xFF0A0A0A),
                        Color(0xFF10242C)
                    )
                )
            )
    ) {
        Text(
            text = "♪",
            color = Color(0x1AFFFFFF),
            fontFamily = MetroLight,
            fontSize = 460.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 40.dp)
        )
    }
}

/**
 * The playing album's cover as the backdrop, when settings ask for it.
 *
 * Heavily dimmed and washed into the theme background: the foreground is white text with no plate
 * behind it, and album art is not designed to be read over.
 */
@Composable
private fun ArtworkBackdrop(cover: Bitmap) {
    val colors = MetroTheme.colors
    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Image(
            bitmap = cover.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.30f }
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(colors.bg.copy(alpha = 0.35f), colors.bg.copy(alpha = 0.9f))
                )
            )
        )
    }
}
