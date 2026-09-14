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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.metrocompose.Metro
import com.metrocompose.MetroLight
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Album
import com.metromusic.data.model.Track

/**
 * How the playing album's cover is drawn when it is being used as a *backdrop* rather than as a cover.
 *
 * One set of numbers, in one place, because two different surfaces draw the same picture and the eye
 * compares them directly: the player's whole screen, and the strip it rests in. The strip is not
 * composed underneath the player — at rest the page does not exist at all — so what the strip paints
 * *is* what is on screen until a finger touches it, and the page then takes over that exact
 * rectangle. Any disagreement between the two shows up as the picture jumping at the start of every
 * gesture, which is worth more than the convenience of tuning them separately.
 *
 * **The cover is drawn at the screen's width, square, with its top at the top of the page.** Not
 * cropped to fill the page: a square stretched over a 2340px-tall screen is magnified two and a half
 * times, and the band of it that shows behind a 300px strip is then an unrecognisable enlargement of
 * some corner of the artwork — which is what "the cover shrivels up in the mini player" and "the
 * backdrop changes as you pull" both were. At the screen's width the strip shows the *top of the
 * cover*, pulling the page up draws the rest of it out from underneath, and the magnification is the
 * same everywhere. Below the square the page is its own flat colour, which the scrim fades into.
 *
 * There is no overscale left, and so no sideways parallax on the backdrop either: at this scale the
 * picture is exactly as wide as the screen, so any horizontal travel would expose an edge. The
 * foreground still moves at the finger's full speed, which is where the depth reads.
 *
 * The alpha and the two ends of the scrim are what keep white text on an arbitrary album cover
 * readable — a third of an all-white cover over a near-black background is still a dark grey.
 */
const val BackdropAlpha = 0.30f
const val BackdropScrimTop = 0.1f
const val BackdropScrimBottom = 0.95f

/**
 * One decode for the backdrop, shared by the strip and the player, and deliberately smaller than the
 * player's own cover.
 *
 * It is stretched over a whole screen and drawn at [BackdropAlpha] behind a scrim, so the softness of
 * an upscale is not visible — while a second full-size bitmap of the same album would be megabytes of
 * the cache's ceiling for no difference anyone can see.
 */
val BackdropDecode = 280.dp

/**
 * The playing album's cover for use as a backdrop, held so that it **survives the player being
 * removed from the composition**.
 *
 * Call it from the shell, not from inside the page. The rising player does not exist while it rests in
 * the strip, so anything it remembers is built again from nothing every time a finger starts to pull:
 * the first frames of the gesture then have no bitmap at all, the page shows flat colour where the
 * artwork should be, and the cover arrives a moment later through a cross-fade. That reads as the
 * backdrop *changing* as you pull, which is exactly what it was reported as. Held out here it is
 * already there on the page's first frame, so the fade has nothing to do and the picture the strip was
 * showing is the picture the page shows.
 *
 * The initial value is the loader's own — which is a synchronous cache peek — rather than null, so a
 * warm cache needs no frame at all to catch up.
 *
 * **"Not loaded yet" and "there is no cover" are two different answers, and holding on to the old
 * picture is right for only one of them.** [rememberAlbumArt] returns null for both, so keeping the
 * last non-null bitmap — which is what avoids a black blink between two albums — also kept the
 * *previous* album's artwork behind the strip for ever once a track with no cover came on: the 52dp
 * tile correctly showed its accent placeholder while the band behind it was still the record before
 * it. [rememberAlbumArtState] is the same load reporting which of the two answers it has, and the
 * bitmap is dropped as soon as the answer is a settled no.
 */
@Composable
fun rememberPlayingBackdrop(albumId: Long, representativeTrackId: Long): Bitmap? {
    val art = rememberAlbumArtState(albumId, representativeTrackId, BackdropDecode)
    // The last cover actually decoded, kept while the next one is on its way: a track change swaps the
    // album id before its bitmap exists, and drawing that gap is a black blink between two pictures.
    // A *settled* answer replaces it whatever it is, including nothing.
    var shown by remember { mutableStateOf(art.bitmap) }
    LaunchedEffect(art) {
        if (art.bitmap != null || art.settled) shown = art.bitmap
    }
    return shown
}

/**
 * What [rememberAlbumArtState] answers: the cover, and whether that is the last word on it.
 *
 * [settled] is false only while a decode is still outstanding. It is the distinction the drawing
 * surfaces mostly do not need — a tile with no art draws its placeholder either way — and the one
 * anything that *holds* a bitmap across a change absolutely does.
 */
data class AlbumArtState(val bitmap: Bitmap?, val settled: Boolean)

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
fun rememberAlbumArt(albumId: Long, representativeTrackId: Long, size: Dp): Bitmap? =
    rememberAlbumArtState(albumId, representativeTrackId, size).bitmap

/**
 * [rememberAlbumArt], with the load's own answer to "is there a cover at all" alongside the bitmap.
 *
 * The single write inside the producer is what keeps the no-blanking behaviour described above: on a
 * new album the state still holds the previous [AlbumArtState] — settled, with the old bitmap in it —
 * until this one's peek or load returns, rather than passing through a null. What it must not do is
 * report *this* album as settled before it is, which is why nothing is written on the way in.
 */
@Composable
fun rememberAlbumArtState(albumId: Long, representativeTrackId: Long, size: Dp): AlbumArtState {
    val services = LocalServices.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val state by produceState(
        initialValue = AlbumArtState(services.artwork.peek(albumId, sizePx), settled = false),
        albumId, representativeTrackId, sizePx
    ) {
        val peeked = services.artwork.peek(albumId, sizePx)
        value = AlbumArtState(
            bitmap = peeked ?: services.artwork.load(albumId, representativeTrackId, sizePx),
            settled = true
        )
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
