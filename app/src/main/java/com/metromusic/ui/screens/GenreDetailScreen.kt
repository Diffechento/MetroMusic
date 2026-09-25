package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.MetroEmptyNote
import com.metrocompose.MetroPage
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.PlaylistPickerHost
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.rememberPlaylistPicker
import com.metromusic.ui.components.rememberTrackActions

/** Everything tagged with one genre. */
@Composable
fun GenreDetailScreen(genre: String) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val picker = rememberPlaylistPicker()

    val tracks = remember(library, genre) { library.tracksOfGenre(genre) }

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_genre), genre) {
            if (tracks.isEmpty()) {
                MetroEmptyNote(stringResource(R.string.genre_gone))
                return@MetroPage
            }
            AppBar(Modifier.padding(bottom = 8.dp)) {
                AppBarButton(Glyphs.Play, stringResource(R.string.action_play)) {
                    services.player.play(tracks, 0)
                }
                AppBarButton(Glyphs.Shuffle, stringResource(R.string.action_shuffle)) {
                    services.player.shuffleAll(tracks)
                }
                AppBarButton(Glyphs.Grip, stringResource(R.string.playlist_add_to_short)) {
                    picker.open(tracks.map { it.id })
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    TrackRowWithActions(
                        track = track,
                        actions = actions,
                        onPlay = { services.player.play(tracks, tracks.indexOf(track)) },
                        isCurrent = track.id == playerState.trackId
                    )
                }
            }
        }
        TrackActionsHost(actions)
        PlaylistPickerHost(picker)
    }
}
