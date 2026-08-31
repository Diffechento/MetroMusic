package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.ListRow
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroPage
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroTextBox
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Album
import com.metromusic.data.model.Artist
import com.metromusic.data.model.Library
import com.metromusic.data.model.Track
import com.metromusic.data.store.Playlist
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.WideTile
import com.metromusic.ui.components.rememberTrackActions
import com.metromusic.ui.formatAlbumCount
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen

/**
 * One query against the whole library at once.
 *
 * The panorama already searches, and does something different: tapping a section header filters
 * *that* section, because the question there is "which of these" and the answer belongs to the list
 * you are looking at. This is the other question — "where is that song" — where the asker does not
 * know, and often does not care, whether what they are remembering is a title, a band or a record.
 * Answering it a section at a time means asking the same thing up to five times.
 *
 * The results are grouped by what they are and each group says how many it found, so a query that
 * hits one artist and forty of their songs reads as that rather than as a wall. Tapping goes where
 * tapping that row goes anywhere else in the app: a song plays, everything else opens.
 *
 * Deliberately **no continuum key** on the album rows. The keys have to be unique among the elements
 * alive at one time, and during the turn between this page and the albums section both lists exist —
 * two rows claiming `album-art-<id>` are treated as one object and laid out on top of each other.
 * The cover here simply crossfades with the page, which is what every other album list that is not
 * the canonical one does.
 */
@Composable
fun SearchScreen(onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val playlistData by services.playlists.playlists.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()

    // Survives the process, like any other screen's state: coming back to a page and finding the
    // question you asked gone is the same fault the back stack's SaveableStateHolder exists to fix.
    var query by rememberSaveable { mutableStateOf("") }
    val trimmed = query.trim()

    // One pass per keystroke over each list, and only when the query really changed — `remember` on
    // the library as well, since a scan finishing while this page is open must re-run it.
    val results = remember(library, playlistData, trimmed) {
        Results.of(library, playlistData.items, trimmed)
    }

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.search_title)) {
            MetroTextBox(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search_placeholder),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                // The page exists to be typed into, so it opens with the keyboard up. The only
                // reason to come here is to ask something.
                autoFocus = true
            )

            when {
                trimmed.isEmpty() -> EmptyNote(stringResource(R.string.search_hint))
                results.isEmpty -> EmptyNote(stringResource(R.string.search_nothing, trimmed))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (results.artists.isNotEmpty()) {
                        item(key = "h-artists") {
                            ResultHeader(
                                stringResource(R.string.section_artists),
                                results.artists.size
                            )
                        }
                        items(results.artists, key = { "artist-${it.id}" }) { artist ->
                            ArtistResult(artist, onNavigate)
                        }
                    }

                    if (results.albums.isNotEmpty()) {
                        item(key = "h-albums") {
                            ResultHeader(
                                stringResource(R.string.section_albums),
                                results.albums.size
                            )
                        }
                        items(results.albums, key = { "album-${it.id}" }) { album ->
                            WideTile(
                                albumId = album.id,
                                representativeTrackId = album.representativeTrackId,
                                primary = album.title,
                                secondary = album.artist,
                                onClick = { onNavigate(Screen.AlbumDetail(album.id)) }
                            )
                        }
                    }

                    if (results.tracks.isNotEmpty()) {
                        item(key = "h-songs") {
                            ResultHeader(
                                stringResource(R.string.section_songs),
                                results.tracks.size
                            )
                        }
                        items(results.tracks, key = { "track-${it.id}" }) { track ->
                            TrackRowWithActions(
                                track = track,
                                actions = actions,
                                // The queue is what the search found, from the row that was tapped —
                                // the same rule the songs section follows: what plays next is what is
                                // on screen, not wherever that track happens to sit in the library.
                                onPlay = {
                                    services.player.play(
                                        results.tracks,
                                        results.tracks.indexOf(track)
                                    )
                                },
                                isCurrent = track.id == playerState.trackId
                            )
                        }
                    }

                    if (results.genres.isNotEmpty()) {
                        item(key = "h-genres") {
                            ResultHeader(
                                stringResource(R.string.section_genres),
                                results.genres.size
                            )
                        }
                        items(results.genres, key = { "genre-$it" }) { genre ->
                            ListRow(
                                primary = genre,
                                secondary = formatTrackCount(library.tracksOfGenre(genre).size),
                                onClick = { onNavigate(Screen.GenreDetail(genre)) }
                            )
                        }
                    }

                    if (results.playlists.isNotEmpty()) {
                        item(key = "h-playlists") {
                            ResultHeader(
                                stringResource(R.string.row_playlists),
                                results.playlists.size
                            )
                        }
                        items(results.playlists, key = { "playlist-${it.id}" }) { playlist ->
                            ListRow(
                                primary = playlist.name,
                                secondary = formatTrackCount(playlist.trackIds.size),
                                onClick = { onNavigate(Screen.PlaylistDetail(playlist.id)) }
                            )
                        }
                    }

                    item(key = "search-inset") { MetroBottomInset(extra = 20.dp) }
                }
            }
        }
        TrackActionsHost(actions)
    }
}

@Composable
private fun ArtistResult(artist: Artist, onNavigate: (Screen) -> Unit) {
    ListRow(
        primary = artist.name,
        secondary = "${formatAlbumCount(artist.albumCount)} · ${formatTrackCount(artist.trackCount)}",
        onClick = { onNavigate(Screen.ArtistDetail(artist.id)) }
    )
}

/**
 * What kind of thing the rows under it are, and how many there were.
 *
 * The count is the useful half: it is what tells "one artist and everything they recorded" from "one
 * song whose title happens to contain the word", without scrolling to find out.
 */
@Composable
private fun ResultHeader(title: String, count: Int) {
    Text(
        text = "$title · $count",
        color = MetroTheme.colors.subtle,
        fontFamily = MetroSemilight,
        fontSize = 24.sp,
        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp)
    )
}

/**
 * Everything one query found, worked out in one place.
 *
 * A class rather than five `remember`s so that "did this find anything at all" is one question with
 * one answer — five separate empties are five ways to draw nothing and no way to say so.
 */
private class Results(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
    val genres: List<String>,
    val playlists: List<Playlist>
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
            genres.isEmpty() && playlists.isEmpty()

    companion object {
        val None = Results(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun of(library: Library, playlists: List<Playlist>, query: String): Results {
            if (query.isEmpty()) return None
            fun hit(text: String) = text.contains(query, ignoreCase = true)
            return Results(
                artists = library.artists.filter { hit(it.name) },
                // An album matches on its own artist too, which is how "daft punk" finds Discovery
                // as well as the band — the same rule the albums section already follows.
                albums = library.albums.filter { hit(it.title) || hit(it.artist) },
                tracks = library.tracks.filter { hit(it.title) || hit(it.artist) || hit(it.album) },
                genres = library.genres.filter { hit(it) },
                playlists = playlists.filter { hit(it.name) }
            )
        }
    }
}
