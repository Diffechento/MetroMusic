package com.metromusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTextBox
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Track
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackRow

/**
 * Picking songs for a playlist, several at a time.
 *
 * Reported as the thing that was missing: everything the app offered added *one* song, so building
 * a playlist out of an album meant holding thirteen rows in turn and answering the same panel
 * thirteen times. A whole album, artist or genre now goes in from its own long-press menu; this is
 * the other half — the case where what somebody wants is twenty songs that have nothing in common
 * except that they want them.
 *
 * It is a page and not a modal because it needs a search box and a scrolling list, and because the
 * search box is what makes "an album" reachable here too: type the record's name, tap **all**.
 *
 * **Songs already in the playlist are ticked and cannot be unticked.** This screen only adds — the
 * playlist's own page is where rows are removed and reordered — so an unticked box that does
 * nothing would be a lie about what the tick means.
 */
@Composable
fun AddToPlaylistScreen(playlistId: String, onDone: () -> Unit) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val data by services.playlists.playlists.collectAsStateWithLifecycle()

    val playlist = data.items.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        MetroPage(
            stringResource(R.string.overline_playlist),
            stringResource(R.string.playlist_not_found)
        ) {
            if (data.loaded) EmptyNote(stringResource(R.string.playlist_gone))
        }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    val already = remember(playlist.entries) { playlist.trackIds.toSet() }
    val trimmed = query.trim()
    val shown = remember(library, trimmed) {
        if (trimmed.isEmpty()) {
            library.tracks
        } else {
            library.tracks.filter { track ->
                track.title.contains(trimmed, true) ||
                    track.artist.contains(trimmed, true) ||
                    track.album.contains(trimmed, true)
            }
        }
    }

    MetroPage(playlist.name.uppercase(), stringResource(R.string.playlist_add_title)) {
        MetroTextBox(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.search_placeholder),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)
        )

        AppBar(Modifier.padding(bottom = 8.dp)) {
            // "all" is over what is *shown*, not over the library: with a query in the box that is
            // the album or the artist somebody just searched for, which is what makes this screen
            // an answer to "add a whole record" as well as to "add these twenty".
            AppBarButton(Glyphs.SelectAll, stringResource(R.string.playlist_select_all)) {
                selected = selected + shown.map { it.id }.filterNot { it in already }
            }
            AppBarButton(Glyphs.SelectNone, stringResource(R.string.playlist_select_none)) {
                selected = emptySet()
            }
            AppBarButton(Glyphs.Add, stringResource(R.string.playlist_add_done)) {
                if (selected.isNotEmpty()) {
                    // In the library's own order rather than the order they were tapped: nobody
                    // remembers the order they ticked twenty boxes in, and the list they were
                    // ticked on is in front of them.
                    services.playlists.add(playlist.id, library.tracks.map { it.id }
                        .filter { it in selected })
                }
                onDone()
            }
        }

        Text(
            text = pluralStringResource(
                R.plurals.playlist_selected,
                selected.size,
                selected.size
            ),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp)
        )

        if (shown.isEmpty()) {
            EmptyNote(stringResource(R.string.playlist_add_empty))
            return@MetroPage
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { track ->
                val isAlready = track.id in already
                SelectableTrackRow(
                    track = track,
                    checked = isAlready || track.id in selected,
                    locked = isAlready,
                    onToggle = {
                        selected = if (track.id in selected) {
                            selected - track.id
                        } else {
                            selected + track.id
                        }
                    }
                )
            }
        }
    }
}

/**
 * A track row with a tick box in front of it.
 *
 * The box is WP8's — a square outline that fills with the accent — drawn here rather than taken
 * from the framework's [com.metrocompose.MetroCheckBox] because that one comes with a label of its
 * own and this needs a whole [TrackRow] beside it. The row and the box are one target: a list of
 * things to tick where the text is not tickable is a list that feels broken.
 */
@Composable
private fun SelectableTrackRow(
    track: Track,
    checked: Boolean,
    locked: Boolean,
    onToggle: () -> Unit
) {
    val colors = MetroTheme.colors
    Row(
        Modifier.clickable(enabled = !locked, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(24.dp))
        Box(
            Modifier
                .size(26.dp)
                .border(2.dp, if (checked) colors.accent else colors.subtle)
                .background(if (checked) colors.accent else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", color = colors.fg, fontFamily = MetroRegular, fontSize = 17.sp)
            }
        }
        Box(Modifier.weight(1f)) {
            TrackRow(
                track = track,
                onClick = { if (!locked) onToggle() },
                // The album is in the second line here and nowhere else in the app, because the
                // question on this screen is "which of these do I want" and a title alone cannot
                // tell two rips of one song apart.
                secondary = track.artist + " · " + track.album,
                isCurrent = false
            )
        }
    }
}
