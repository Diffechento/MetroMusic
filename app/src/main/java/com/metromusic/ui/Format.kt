package com.metromusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.metromusic.R
import java.util.Locale

/** `4:08`, or `1:02:33` once it passes an hour. */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * "1 song" / "12 songs" — used under album and artist titles.
 *
 * A plural resource rather than a format string, and composable so it can reach one. English needs
 * two forms and gets away with `if (count == 1)`; Russian needs three (песня / песни / песен) chosen
 * by rules no call site should have to know. `pluralStringResource` is where that knowledge lives.
 */
@Composable
fun formatTrackCount(count: Int): String = pluralStringResource(R.plurals.songs, count, count)

@Composable
fun formatAlbumCount(count: Int): String = pluralStringResource(R.plurals.albums, count, count)

@Composable
fun formatArtistCount(count: Int): String = pluralStringResource(R.plurals.artists, count, count)
