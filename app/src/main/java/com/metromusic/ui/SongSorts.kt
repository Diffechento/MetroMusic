package com.metromusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.metrocompose.MetroListSort
import com.metromusic.R
import com.metromusic.data.model.Track
import com.metromusic.data.store.SongSort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four ways the songs section can be read, as the framework's own [MetroListSort] — so the list
 * itself does the arranging, the grouping and the picker, and this file only says what the four
 * arrangements *are*.
 *
 * Every one of them groups, because a heading is what makes a sorted list legible: told that this run
 * of songs is "5–9 plays" you can see where you are, where a bare list ordered by an invisible number
 * only lets you infer it. The alphabet keeps its zoom-out grid; the others have no domain to zoom out
 * to, and for them a tap on the heading opens the picker instead (see `MetroListSort.jumpDomain`).
 *
 * Directions are the ones the question implies rather than a uniform ascending: *date added* and
 * *times played* are asked in order to see the top of the list — what arrived last, what you play
 * most — while *name* and *length* are asked to find a place in a list you already picture.
 *
 * @param playCounts from `StatsStore`; a song nobody has played is simply absent from it.
 */
@Composable
fun rememberSongSorts(playCounts: Map<Long, Int>): List<MetroListSort<Track>> {
    val name = stringResource(R.string.sort_name)
    val dateAdded = stringResource(R.string.sort_date_added)
    val duration = stringResource(R.string.sort_duration)
    val plays = stringResource(R.string.sort_plays)

    val dateUnknown = stringResource(R.string.sort_group_added_unknown)
    val under2 = stringResource(R.string.sort_group_under_2min)
    val to5 = stringResource(R.string.sort_group_2_to_5min)
    val to10 = stringResource(R.string.sort_group_5_to_10min)
    val over10 = stringResource(R.string.sort_group_over_10min)
    val never = stringResource(R.string.sort_group_plays_never)
    val few = stringResource(R.string.sort_group_plays_few)
    val some = stringResource(R.string.sort_group_plays_some)
    val many = stringResource(R.string.sort_group_plays_many)

    // The list is rebuilt only when the counts or the words change: the framework re-sorts when the
    // sort object does, so handing it a fresh one every recomposition would sort the whole library
    // on every frame.
    return remember(playCounts, name, dateAdded, duration, plays) {
        val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
        val zone = ZoneId.systemDefault()

        SongSort.entries.map { sort ->
            when (sort) {
                SongSort.Name -> MetroListSort.alphabetical(name) { it.title }

                // Newest first, and grouped by the month a file arrived — the unit a person actually
                // remembers ("the stuff I put on in July"), where a day per heading would be a list
                // of headings and a year one heading.
                SongSort.DateAdded -> MetroListSort(
                    name = dateAdded,
                    comparator = compareByDescending<Track> { it.dateAdded }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { track ->
                        // MediaStore writes seconds, and writes nothing at all for the odd row that
                        // was indexed without one. Zero is not "January 1970" here, it is "unknown".
                        if (track.dateAdded <= 0L) {
                            dateUnknown
                        } else {
                            Instant.ofEpochSecond(track.dateAdded)
                                .atZone(zone)
                                .format(monthFormat)
                                .lowercase(Locale.getDefault())
                        }
                    }
                )

                // Shortest first, in the four bands a music library actually has: interludes, songs,
                // long songs, and the sets and mixes.
                SongSort.Duration -> MetroListSort(
                    name = duration,
                    comparator = compareBy<Track> { it.durationMs }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { track ->
                        val minutes = track.durationMs / 60_000
                        when {
                            minutes < 2 -> under2
                            minutes < 5 -> to5
                            minutes < 10 -> to10
                            else -> over10
                        }
                    },
                    // Four bands, always all four: zoomed out, a library with nothing over ten minutes
                    // in it should say so with a dimmed block, the way the alphabet dims a letter you
                    // own no artists under. Months cannot do this — there is no set of all months —
                    // so that arrangement zooms out over what it has.
                    jumpDomain = { listOf(under2, to5, to10, over10) }
                )

                // Most played first. The bands are wide at the bottom on purpose: the interesting end
                // of this list is the top, and everything nobody has played yet is one heading rather
                // than the whole library under a heading each.
                SongSort.PlayCount -> MetroListSort(
                    name = plays,
                    comparator = compareByDescending<Track> { playCounts[it.id] ?: 0 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { track ->
                        when (playCounts[track.id] ?: 0) {
                            0 -> never
                            in 1..4 -> few
                            in 5..9 -> some
                            else -> many
                        }
                    },
                    jumpDomain = { listOf(many, some, few, never) }
                )
            }
        }
    }
}
