package com.metromusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.ListRow
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.playlist.PlaylistEntry
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.rememberTrackActions
import com.metromusic.ui.nav.Screen

/**
 * One playlist, in the user's own order.
 *
 * Rows reorder by dragging the grip on the right rather than by long-pressing the row —
 * long press already belongs to the context menu, and a dedicated handle removes the guessing
 * about which gesture you are starting.
 *
 * **The rows are the file's lines, not the tracks they resolve to**, which is the visible half of
 * playlists being `.m3u` files. A line pointing at something this device does not have — a deleted
 * file, a song below the duration filter, a playlist written on a computer whose paths only half
 * match — draws as a row that says so, and is carried through every edit into the file that is
 * written back. Showing only what resolves would have been tidier and would mean that opening
 * somebody's playlist and dragging one row silently deleted everything else in it.
 */
@Composable
fun PlaylistDetailScreen(playlistId: String, onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val data by services.playlists.playlists.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()

    val playlist = data.items.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        MetroPage(
            stringResource(R.string.overline_playlist),
            stringResource(
                if (data.loaded) R.string.playlist_not_found else R.string.row_playlists
            )
        ) {
            if (data.loaded) EmptyNote(stringResource(R.string.playlist_gone))
        }
        return
    }

    val entries = playlist.entries
    // One resolution pass for the whole file, so a row does not go looking per frame. The nulls are
    // kept in place: the index of a row is the index of its line, and that is what an edit names.
    val resolved = remember(library, entries) {
        entries.map { entry -> entry.trackId?.let(library::track) }
    }
    val playable = remember(resolved) { resolved.filterNotNull() }

    // Drag state. Rows are uniform, so a single measured height is enough to know when the
    // dragged row has travelled past its neighbour.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    var menuForMissing by remember { mutableIntStateOf(-1) }
    val lifting = dragIndex >= 0

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_playlist), playlist.name) {
            AppBar(Modifier.padding(bottom = 8.dp)) {
                AppBarButton(Glyphs.Play, stringResource(R.string.action_play)) {
                    if (playable.isNotEmpty()) services.player.play(playable, 0)
                }
                AppBarButton(Glyphs.Shuffle, stringResource(R.string.action_shuffle)) {
                    services.player.shuffleAll(playable)
                }
                // The answer to "add one song at a time is slow", and the reason it is a page
                // rather than a panel: picking twenty songs out of a library is a list with a
                // search box in it, which is a screen.
                AppBarButton(Glyphs.Add, stringResource(R.string.playlist_add_songs)) {
                    onNavigate(Screen.PlaylistAdd(playlist.id))
                }
            }

            if (entries.isEmpty()) {
                EmptyNote(stringResource(R.string.playlist_empty))
                return@MetroPage
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = entries,
                    // The line's own position, because two lines may name the same file — queueing
                    // an album twice is a thing people do — and a track id would be two rows under
                    // one key.
                    key = { index, entry -> "$index:${entry.target}" }
                ) { index, entry ->
                    val currentIndex by rememberUpdatedState(index)
                    val isDragging = index == dragIndex
                    val track = resolved.getOrNull(index)

                    Row(
                        Modifier
                            .onSizeChanged { if (rowHeight == 0) rowHeight = it.height }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            // Opaque only while a row is in the air. A lifted row travelling over
                            // its neighbours has to cover them — two rows of text superimposed
                            // reads as a layout fault rather than as one row passing another — and
                            // that is the *only* reason this is here. Painting it always is what
                            // put a black rectangle over the app's backdrop for the length of the
                            // list, which nobody saw while playlists were a few rows long.
                            .background(if (lifting) colors.bg else Color.Transparent)
                            // The tint goes over the page colour rather than instead of it, for
                            // the same reason the queue screen's lifted row does.
                            .background(
                                if (isDragging) colors.accent.copy(alpha = 0.18f) else Color.Transparent
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The weight has to sit on the menu's anchor, not on the row inside it,
                        // or the anchor fills the width and pushes the grip off screen.
                        Box(Modifier.weight(1f)) {
                            if (track != null) {
                                TrackRowWithActions(
                                    track = track,
                                    actions = actions,
                                    onPlay = {
                                        services.player.play(playable, playable.indexOf(track))
                                    },
                                    showArt = true,
                                    isCurrent = track.id == playerState.trackId,
                                    extraActions = listOf(
                                        stringResource(R.string.menu_remove_from_playlist)
                                    ),
                                    onExtraAction = {
                                        services.playlists.removeAt(playlist.id, currentIndex)
                                    }
                                )
                            } else {
                                MissingRow(
                                    entry = entry,
                                    expanded = menuForMissing == index,
                                    onOpenMenu = { menuForMissing = index },
                                    onDismissMenu = { menuForMissing = -1 },
                                    onRemove = {
                                        menuForMissing = -1
                                        services.playlists.removeAt(playlist.id, currentIndex)
                                    }
                                )
                            }
                        }

                        Box(
                            Modifier
                                .size(44.dp)
                                .pointerInput(playlist.id, index) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragIndex = currentIndex
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            dragIndex = -1
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragIndex = -1
                                            dragOffset = 0f
                                        }
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val height = rowHeight
                                        if (height <= 0 || dragIndex < 0) return@detectDragGestures
                                        // Once the row has cleared a full neighbour, commit the
                                        // swap and keep the leftover offset so it stays under
                                        // the finger.
                                        if (dragOffset > height && dragIndex < entries.lastIndex) {
                                            services.playlists.move(
                                                playlist.id, dragIndex, dragIndex + 1
                                            )
                                            dragIndex += 1
                                            dragOffset -= height
                                        } else if (dragOffset < -height && dragIndex > 0) {
                                            services.playlists.move(
                                                playlist.id, dragIndex, dragIndex - 1
                                            )
                                            dragIndex -= 1
                                            dragOffset += height
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.playlist_reorder),
                                color = colors.dim,
                                fontFamily = MetroRegular,
                                fontSize = 22.sp
                            )
                        }
                    }
                }
            }
        }
        TrackActionsHost(actions)
    }
}

/**
 * A line the library cannot match to a file.
 *
 * It shows whatever the `#EXTINF` claimed, falling back to the file name at the end of the path —
 * which between them is almost always enough to recognise the song, and is the whole reason this
 * app writes an `#EXTINF` for every line it saves. Holding it offers the one thing that can be done
 * about it.
 */
@Composable
private fun MissingRow(
    entry: PlaylistEntry,
    expanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRemove: () -> Unit
) {
    MetroContextMenu(
        expanded = expanded,
        items = listOf(stringResource(R.string.menu_remove_from_playlist)),
        onSelect = { onRemove() },
        onDismiss = onDismissMenu
    ) {
        ListRow(
            primary = entry.title?.takeIf { it.isNotBlank() } ?: entry.fileName,
            secondary = stringResource(R.string.playlist_entry_missing),
            onLongClick = onOpenMenu
        )
    }
}
