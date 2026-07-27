package com.metromusic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.metrocompose.Metro
import com.metrocompose.MetroLight
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Album
import com.metromusic.data.model.Track

/**
 * Loads cover art for the requested size and nothing bigger.
 *
 * `produceState` ties the load to the composition, so scrolling past an item cancels its
 * decode instead of finishing work for a row that is already gone. The synchronous `peek`
 * seeds the initial value, which keeps already-cached covers from flashing blank for a frame
 * when a row is recycled.
 *
 * The producer re-reads the cache instead of testing `value` for null. `produceState` keeps its
 * state object across key changes and only restarts the producer, so on a new album `value` still
 * holds the *previous* album's bitmap — guarding the load with `if (value == null)` meant a
 * composable that stays mounted while the album changes (the player) kept showing the old cover
 * forever. Assigning only after the load returns still avoids blanking in the meantime.
 */
@Composable
fun rememberAlbumArt(albumId: Long, representativeTrackId: Long, size: Dp): Bitmap? {
    val services = LocalServices.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val state by produceState<Bitmap?>(
        initialValue = services.artwork.peek(albumId, sizePx),
        albumId, representativeTrackId, sizePx
    ) {
        value = services.artwork.peek(albumId, sizePx)
            ?: services.artwork.load(albumId, representativeTrackId, sizePx)
    }
    return state
}

/**
 * Square cover art. Albums without art get a flat colored square with a note — WP8 never
 * showed a grey placeholder box, and a color derived from the id keeps the same album looking
 * the same everywhere in the app.
 */
@Composable
fun AlbumArt(
    albumId: Long,
    representativeTrackId: Long,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberAlbumArt(albumId, representativeTrackId, size)
    Box(modifier.size(size)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(placeholderColor(albumId)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪",
                    color = Metro.Fg,
                    fontFamily = MetroLight,
                    fontSize = (size.value * 0.42f).sp
                )
            }
        }
    }
}

@Composable
fun AlbumArt(album: Album, size: Dp, modifier: Modifier = Modifier) =
    AlbumArt(album.id, album.representativeTrackId, size, modifier)

@Composable
fun AlbumArt(track: Track, size: Dp, modifier: Modifier = Modifier) =
    AlbumArt(track.albumId, track.id, size, modifier)

/** Deterministic tile color for a coverless album, from the WP8 palette. */
fun placeholderColor(albumId: Long): Color {
    val palette = listOf(
        Metro.Accent, Metro.Green, Metro.Red, Metro.Purple,
        Metro.Orange, Metro.Teal, Metro.Magenta
    )
    val index = ((albumId % palette.size) + palette.size) % palette.size
    return palette[index.toInt()]
}
