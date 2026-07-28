package com.metromusic.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.metromusic.R
import java.text.DateFormat
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
 * A past moment as the phone would write it: the time for today, the date for anything older.
 *
 * `DateUtils` rather than a pattern of our own, so it answers in the user's locale and in their 12- or
 * 24-hour preference — a settings page that says "17:20" to someone whose clock says "5:20 PM" reads
 * as another app's.
 */
fun formatWhen(epochMs: Long): String = DateUtils.formatSameDayTime(
    epochMs,
    System.currentTimeMillis(),
    DateFormat.MEDIUM,
    DateFormat.SHORT
).toString()

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
