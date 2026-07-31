package com.metromusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.metrocompose.MetroListSort
import com.metromusic.R
import com.metromusic.data.model.Album
import com.metromusic.data.model.Artist
import com.metromusic.data.model.Library
import com.metromusic.data.model.Track
import com.metromusic.data.store.AlbumSort
import com.metromusic.data.store.ArtistSort
import com.metromusic.data.store.SongSort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The ways the three searchable sections can be read, as the framework's own MetroListSort — so the
 * lists themselves do the arranging, the grouping and the picker, and this file only says what the
 * arrangements *are*.
 *
 * Every one of them groups, because a heading is what makes a sorted list legible: told that this run
 * of songs is "5-9 plays" you can see where you are, where a list ordered by an invisible number only
 * lets you infer it. A tap on a heading zooms out over those groups and a hold offers the other
 * arrangements; both of those belong to MetroLongList.
 *
 * Two rules run through all of them:
 *
 *  - **Directions are the ones the question implies** rather than a uniform ascending. Date added, year
 *    and every count are asked in order to see the top of the list — what arrived last, what you play
 *    most — while name and length are asked to find a place in a list you already picture.
 *  - **A closed set of bands names all of its bands** (`jumpDomain`), so zoomed out, a library with
 *    nothing over ten minutes in it says so with a dimmed block, exactly as the alphabet dims a letter
 *    you own no artists under. Dates cannot do this — there is no set of all months and no set of all
 *    years — so those zoom out over what they have.
 */

/** Seconds since the epoch as "july 2026", the unit a person actually remembers a file arriving in. */
private fun monthLabeller(unknown: String): (Long) -> String {
    val zone = ZoneId.systemDefault()
    val format = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    return { seconds ->
        // MediaStore writes seconds, and writes nothing at all for the odd row that was indexed
        // without one. Zero is not "January 1970" here, it is "unknown".
        if (seconds <= 0L) {
            unknown
        } else {
            Instant.ofEpochSecond(seconds).atZone(zone).format(format).lowercase(Locale.getDefault())
        }
    }
}

/**
 * The four ways the songs section can be read.
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
        val month = monthLabeller(dateUnknown)

        SongSort.entries.map { sort ->
            when (sort) {
                SongSort.Name -> MetroListSort.alphabetical(name) { it.title }

                // Newest first, and grouped by the month a file arrived — where a day per heading
                // would be a list of headings and a year would be one heading.
                SongSort.DateAdded -> MetroListSort(
                    name = dateAdded,
                    comparator = compareByDescending<Track> { it.dateAdded }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { month(it.dateAdded) }
                )

                // Shortest first, in the four bands a music library actually has: interludes, songs,
                // long songs, and the sets and mixes.
                SongSort.Duration -> MetroListSort(
                    name = duration,
                    comparator = compareBy<Track> { it.durationMs }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { track ->
                        when (track.durationMs / 60_000L) {
                            in 0L..1L -> under2
                            in 2L..4L -> to5
                            in 5L..9L -> to10
                            else -> over10
                        }
                    },
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

/** The four ways the albums section can be read. */
@Composable
fun rememberAlbumSorts(): List<MetroListSort<Album>> {
    val name = stringResource(R.string.sort_name)
    val artist = stringResource(R.string.sort_artist)
    val dateAdded = stringResource(R.string.sort_date_added)
    val year = stringResource(R.string.sort_year)

    val dateUnknown = stringResource(R.string.sort_group_added_unknown)
    val yearUnknown = stringResource(R.string.sort_group_year_unknown)

    return remember(name, artist, dateAdded, year) {
        val month = monthLabeller(dateUnknown)

        AlbumSort.entries.map { sort ->
            when (sort) {
                AlbumSort.Name -> MetroListSort.alphabetical(name) { it.title }

                // Filed under the *artist's* letter — the same alphabet and the same zoom-out grid,
                // answering a different question. Records by one artist keep the order the library
                // hands them in, which is by title: an artist's own page is where a discography is
                // read, and this list is still a list of albums.
                AlbumSort.Artist -> MetroListSort.alphabetical(artist) { it.artist }

                AlbumSort.DateAdded -> MetroListSort(
                    name = dateAdded,
                    comparator = compareByDescending<Album> { it.dateAdded }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { month(it.dateAdded) }
                )

                // Newest first, a heading per year. Unknown is not year zero: a tag the scanner could
                // not read sits at the end under a heading that says so, rather than in the middle of
                // the twentieth century.
                AlbumSort.Year -> MetroListSort(
                    name = year,
                    comparator = compareByDescending<Album> { it.year }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    header = { if (it.year <= 0) yearUnknown else it.year.toString() }
                )
            }
        }
    }
}

/**
 * The four ways the artists section can be read.
 *
 * @param library for the per-artist track lists: an artist's plays are the plays of their songs,
 *   including the ones they only guest on, which is what `tracksByArtist` is indexed for.
 * @param playCounts from `StatsStore`.
 */
@Composable
fun rememberArtistSorts(
    library: Library,
    playCounts: Map<Long, Int>
): List<MetroListSort<Artist>> {
    val name = stringResource(R.string.sort_name)
    val songs = stringResource(R.string.sort_song_count)
    val albums = stringResource(R.string.sort_album_count)
    val plays = stringResource(R.string.sort_plays)

    val songsMany = stringResource(R.string.sort_group_songs_many)
    val songsSome = stringResource(R.string.sort_group_songs_some)
    val songsFew = stringResource(R.string.sort_group_songs_few)
    val songsHandful = stringResource(R.string.sort_group_songs_handful)
    val albumsMany = stringResource(R.string.sort_group_albums_many)
    val albumsSome = stringResource(R.string.sort_group_albums_some)
    val albumsFew = stringResource(R.string.sort_group_albums_few)
    val albumsOne = stringResource(R.string.sort_group_albums_one)
    val playsNever = stringResource(R.string.sort_group_plays_never)
    val playsFew = stringResource(R.string.sort_group_artist_plays_few)
    val playsSome = stringResource(R.string.sort_group_artist_plays_some)
    val playsMany = stringResource(R.string.sort_group_artist_plays_many)

    return remember(library, playCounts, name, songs, albums, plays) {
        // Summed once per artist, not inside the comparator: a comparator is called O(n log n) times,
        // and adding up an artist's tracks in there would walk the library that many times over.
        val playsByArtist = library.artists.associate { artist ->
            artist.id to library.tracksOfArtist(artist.id).sumOf { playCounts[it.id] ?: 0 }
        }

        ArtistSort.entries.map { sort ->
            when (sort) {
                ArtistSort.Name -> MetroListSort.alphabetical(name) { it.name }

                ArtistSort.Songs -> MetroListSort(
                    name = songs,
                    comparator = compareByDescending<Artist> { it.trackCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    header = { artist ->
                        when (artist.trackCount) {
                            in 0..4 -> songsHandful
                            in 5..9 -> songsFew
                            in 10..19 -> songsSome
                            else -> songsMany
                        }
                    },
                    jumpDomain = { listOf(songsMany, songsSome, songsFew, songsHandful) }
                )

                ArtistSort.Albums -> MetroListSort(
                    name = albums,
                    comparator = compareByDescending<Artist> { it.albumCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    header = { artist ->
                        when (artist.albumCount) {
                            in 0..1 -> albumsOne
                            in 2..3 -> albumsFew
                            in 4..9 -> albumsSome
                            else -> albumsMany
                        }
                    },
                    jumpDomain = { listOf(albumsMany, albumsSome, albumsFew, albumsOne) }
                )

                // An artist's plays are the plays of all their songs, so the bands are wider than a
                // song's: ten plays is a lot for one track and an afternoon for a band.
                ArtistSort.PlayCount -> MetroListSort(
                    name = plays,
                    comparator = compareByDescending<Artist> { playsByArtist[it.id] ?: 0 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    header = { artist ->
                        when (playsByArtist[artist.id] ?: 0) {
                            0 -> playsNever
                            in 1..9 -> playsFew
                            in 10..49 -> playsSome
                            else -> playsMany
                        }
                    },
                    jumpDomain = { listOf(playsMany, playsSome, playsFew, playsNever) }
                )
            }
        }
    }
}
