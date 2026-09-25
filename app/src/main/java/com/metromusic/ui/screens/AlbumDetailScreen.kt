package com.metromusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.MetroEmptyNote
import com.metrocompose.MetroPage
import com.metrocompose.metroCollapseOnScroll
import com.metrocompose.metroCollapsingHeader
import com.metrocompose.rememberMetroCollapse
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metrocompose.metroContinuum
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.AlbumArt
import com.metromusic.ui.components.PlaylistPickerHost
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.rememberPlaylistPicker
import com.metromusic.ui.components.rememberTrackActions
import com.metromusic.ui.formatDuration
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen

/**
 * One album: cover, the numbers, and its track listing in disc/track order.
 *
 * The cover carries the same continuum key as the tile that was tapped, so it appears to grow
 * out of the list rather than cross-fading in.
 */
@Composable
fun AlbumDetailScreen(albumId: Long, onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()
    val picker = rememberPlaylistPicker()

    val album = library.album(albumId)
    if (album == null) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.album_title)) {
            MetroEmptyNote(stringResource(R.string.album_gone))
        }
        return
    }

    val tracks = remember(library, albumId) { library.tracksOf(album) }
    val totalMs = remember(tracks) { tracks.sumOf { it.durationMs } }

    // The cover, the numbers and the app bar roll away as the track list is scrolled, and come back
    // when it is dragged past its top. On a record with more than a screenful of tracks that is the
    // difference between six rows and eleven; the cover has already done its job by then, and it is
    // still one drag away. The mechanism is the framework's — see MetroCollapse.
    val collapse = rememberMetroCollapse()
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val rolling = settings.collapseTitle

    Box(Modifier.fillMaxSize()) {
        MetroPage(album.artist.uppercase(), album.title) {
        // Clipped, so the cover slides up behind the page's title instead of over it.
        Column(if (rolling) Modifier.metroCollapseOnScroll(collapse).clipToBounds() else Modifier) {
        Column(if (rolling) Modifier.metroCollapsingHeader(collapse) else Modifier) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                AlbumArt(
                    album = album,
                    size = 148.dp,
                    modifier = Modifier.metroContinuum("album-art-${album.id}")
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    DetailLine(
                        if (album.year > 0) {
                            album.year.toString()
                        } else {
                            stringResource(R.string.album_unknown_year)
                        }
                    )
                    DetailLine(formatTrackCount(album.trackCount))
                    DetailLine(formatDuration(totalMs))
                    Spacer(Modifier.height(10.dp))
                    // The way to the artist is the artist's name. It was already drawn in the accent
                    // colour, which in this app means "this is a link" everywhere else — an
                    // accent-coloured line that does nothing when you press it is worse than no link
                    // at all, and it made the captioned "artist" button redundant.
                    Text(
                        text = album.artist,
                        color = MetroTheme.colors.accent,
                        fontFamily = MetroRegular,
                        fontSize = 17.sp,
                        modifier = Modifier
                            .clickable { onNavigate(Screen.ArtistDetail(album.artistId)) }
                            .padding(top = 2.dp, bottom = 4.dp, end = 8.dp)
                    )
                }
            }

            // The album as a whole, which the track rows underneath cannot express: tapping one of
            // them plays the record *from there*, and neither of them queues it.
            //
            // This page used to have no app bar, on the grounds that play and shuffle were already on
            // the albums list's long-press menu. Queueing is what changed that. Stacking up several
            // albums means deciding about each one while looking at it — its cover, its year, its
            // track list — and a menu you can only reach from a list one screen back is a menu that
            // makes you leave and come back for every record.
            AppBar(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                AppBarButton(Glyphs.Play, stringResource(R.string.action_play)) {
                    services.player.play(tracks, 0)
                }
                AppBarButton(Glyphs.Shuffle, stringResource(R.string.action_shuffle)) {
                    services.player.shuffleAll(tracks)
                }
                // A plus, as WP8's own "add to now playing" was: ⏭ is already the transport's "skip
                // this", which is close to the opposite of what queueing means, and the caption
                // underneath is what actually says which of the two it is.
                AppBarButton(Glyphs.Add, stringResource(R.string.action_play_next)) {
                    services.player.playNext(tracks, album.title)
                }
                // Adding a record to a playlist a track at a time was the thing the issue named.
                // The caption is the short form: four captions across a phone is as many as the
                // bar can spell out.
                AppBarButton(Glyphs.Grip, stringResource(R.string.playlist_add_to_short)) {
                    picker.open(tracks.map { it.id })
                }
            }
            Spacer(Modifier.height(6.dp))
        }

            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id }) { track ->
                    TrackRowWithActions(
                        track = track,
                        actions = actions,
                        onPlay = { services.player.play(tracks, tracks.indexOf(track)) },
                        trackNumber = track.displayTrackNo.takeIf { it > 0 },
                        isCurrent = track.id == playerState.trackId,
                        secondary = ""
                    )
                }
            }
        }
        }
        TrackActionsHost(actions)
        PlaylistPickerHost(picker)
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text = text,
        color = MetroTheme.colors.subtle,
        fontFamily = MetroRegular,
        fontSize = 15.sp
    )
}
