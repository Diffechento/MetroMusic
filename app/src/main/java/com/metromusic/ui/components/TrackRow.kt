@file:OptIn(ExperimentalFoundationApi::class)

package com.metromusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocompose.MetroIcon
import com.metrocompose.MetroLineIcon
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metrocompose.metroContinuum
import com.metromusic.data.model.Track
import com.metromusic.ui.formatDuration

/**
 * One track in a list.
 *
 * The row is deliberately flat and tall enough to hit comfortably; the currently playing
 * track is marked by coloring the title with the accent rather than by an icon, which is how
 * WP8 did it.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArt: Boolean = false,
    trackNumber: Int? = null,
    isCurrent: Boolean = false,
    isFavorite: Boolean = false,
    secondary: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    val colors = MetroTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showArt) {
            AlbumArt(track, 56.dp)
            Spacer(Modifier.width(14.dp))
        } else if (trackNumber != null) {
            Text(
                text = trackNumber.toString(),
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 17.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp)
            )
            Spacer(Modifier.width(14.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isCurrent) colors.accent else colors.fg,
                fontFamily = MetroRegular,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = secondary ?: track.artist
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // The same star the player marks a favourite with, drawn rather than typed — see
        // [MetroIcon]. A ♥ glyph here came from the emoji font at its own weight and never
        // matched anything else on screen.
        if (isFavorite) {
            MetroLineIcon(
                icon = MetroIcon.StarFilled,
                color = colors.accent,
                modifier = Modifier.padding(start = 10.dp).size(15.dp)
            )
        }

        // Only when it is known. Some formats leave the media database with no duration at all —
        // ALAC in an .m4a on a Galaxy, for one — and "0:00" against a four-minute song is a worse
        // answer than nothing. The player gets the real length from the file when it plays it.
        if (track.durationMs > 0L) {
            Text(
                text = formatDuration(track.durationMs),
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/**
 * A wide album/artist tile: cover on the left, two lines of text filling the rest.
 *
 * [continuumKey] is applied to the cover alone, not to the whole row. Keying the row would
 * pair a cover-plus-text block with the bare cover on the destination page, and the text would
 * ride along and squash as the bounds morph.
 *
 * The row spans the list and brings its own gutter, exactly as [TrackRow] does. Sized to its content
 * instead it came out about two thirds of the screen wide — invisible until something painted the
 * row's bounds, and then obvious: the long-press menu unrolls out of the item it belongs to, so a
 * short row gives a short sheet while the track rows above it give a full-width one.
 */
@Composable
fun WideTile(
    albumId: Long,
    representativeTrackId: Long,
    primary: String,
    secondary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    continuumKey: Any? = null,
    onLongClick: (() -> Unit)? = null
) {
    val colors = MetroTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(
            albumId = albumId,
            representativeTrackId = representativeTrackId,
            size = 104.dp,
            modifier = if (continuumKey != null) {
                Modifier.metroContinuum(continuumKey)
            } else {
                Modifier
            }
        )
        // Takes what the cover leaves, so a long album title ellipsises instead of pushing the row
        // wider than the list it is in.
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = primary,
                color = colors.fg,
                fontFamily = MetroRegular,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = secondary,
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
