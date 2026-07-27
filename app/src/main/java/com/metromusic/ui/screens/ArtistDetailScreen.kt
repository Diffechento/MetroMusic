package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroPage
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroTheme
import com.metrocompose.metroContinuum
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.components.CollectionRowWithActions
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.WideTile
import com.metromusic.ui.components.rememberCollectionActions
import com.metromusic.ui.components.rememberTrackActions
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen

/** One artist: their albums, then everything of theirs in one list. */
@Composable
fun ArtistDetailScreen(artistId: Long, onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val albumActions = rememberCollectionActions()

    val albums = remember(library, artistId) { library.albumsOfArtist(artistId) }
    val tracks = remember(library, artistId) { library.tracksOfArtist(artistId) }
    val name = tracks.firstOrNull()?.artist ?: albums.firstOrNull()?.artist

    if (name == null) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.artist_title)) {
            EmptyNote(stringResource(R.string.artist_gone))
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // No app bar. Play and shuffle for a whole artist live on the long-press menu in the artists
        // list, where you decide to listen to them — by the time you are on their page you came to
        // pick something, and two captioned buttons were pushing the albums off the screen.
        MetroPage(stringResource(R.string.overline_artist), name) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (albums.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.section_albums)) }
                    items(albums, key = { "album-${it.id}" }) { album ->
                        // The same menu these rows have in the albums section. An album row that
                        // answers a long press in one list and ignores it in another is the kind of
                        // difference nobody can hold in their head — and this is where you are when
                        // you want a second record by the artist you are already queueing.
                        CollectionRowWithActions(
                            key = album.id,
                            actions = albumActions,
                            onPlay = { services.player.play(library.tracksOf(album)) },
                            onPlayNext = {
                                services.player.playNext(library.tracksOf(album), album.title)
                            },
                            onHide = { services.hidden.hideAlbum(album.artist, album.title) },
                            onEdit = { onNavigate(Screen.AlbumEdit(album.id)) }
                        ) {
                            WideTile(
                                albumId = album.id,
                                representativeTrackId = album.representativeTrackId,
                                primary = album.title,
                                secondary = listOfNotNull(
                                    album.year.takeIf { it > 0 }?.toString(),
                                    formatTrackCount(album.trackCount)
                                ).joinToString(" · "),
                                onClick = { onNavigate(Screen.AlbumDetail(album.id)) },
                                continuumKey = "album-art-${album.id}",
                                onLongClick = { albumActions.open(album.id) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }

                item { SectionHeader(stringResource(R.string.section_songs)) }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackRowWithActions(
                        track = track,
                        actions = actions,
                        onPlay = { services.player.play(tracks, tracks.indexOf(track)) },
                        isCurrent = track.id == playerState.trackId,
                        secondary = track.album
                    )
                }
            }
        }
        TrackActionsHost(actions)
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MetroTheme.colors.subtle,
        fontFamily = MetroSemilight,
        fontSize = 24.sp,
        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
    )
}
