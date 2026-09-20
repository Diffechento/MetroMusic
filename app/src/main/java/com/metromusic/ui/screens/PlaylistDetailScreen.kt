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
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.rememberTrackActions

/**
 * One playlist, in the user's own order.
 *
 * Rows reorder by dragging the grip on the right rather than by long-pressing the row —
 * long press already belongs to the context menu, and a dedicated handle removes the guessing
 * about which gesture you are starting.
 */
@Composable
fun PlaylistDetailScreen(playlistId: String) {
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
            stringResource(R.string.playlist_not_found)
        ) {
            EmptyNote(stringResource(R.string.playlist_gone))
        }
        return
    }

    val tracks = remember(library, playlist.trackIds) { library.resolve(playlist.trackIds) }

    // Drag state. Rows are uniform, so a single measured height is enough to know when the
    // dragged row has travelled past its neighbour.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_playlist), playlist.name) {
            AppBar(Modifier.padding(bottom = 8.dp)) {
                AppBarButton("▶", stringResource(R.string.action_play)) {
                    if (tracks.isNotEmpty()) services.player.play(tracks, 0)
                }
                AppBarButton(Glyphs.Shuffle, stringResource(R.string.action_shuffle)) {
                    services.player.shuffleAll(tracks)
                }
            }

            if (tracks.isEmpty()) {
                EmptyNote(stringResource(R.string.playlist_empty))
                return@MetroPage
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    val currentIndex by rememberUpdatedState(index)
                    val isDragging = index == dragIndex

                    Row(
                        Modifier
                            .onSizeChanged { if (rowHeight == 0) rowHeight = it.height }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .background(if (isDragging) colors.accent.copy(alpha = 0.18f) else colors.bg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The weight has to sit on the menu's anchor, not on the row inside it,
                        // or the anchor fills the width and pushes the grip off screen.
                        Box(Modifier.weight(1f)) {
                            TrackRowWithActions(
                                track = track,
                                actions = actions,
                                onPlay = { services.player.play(tracks, currentIndex) },
                                showArt = true,
                                isCurrent = track.id == playerState.trackId,
                                extraActions = listOf(stringResource(R.string.menu_remove_from_playlist)),
                                onExtraAction = {
                                    services.playlists.removeAt(playlist.id, currentIndex)
                                }
                            )
                        }

                        Box(
                            Modifier
                                .size(44.dp)
                                .pointerInput(track.id) {
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
                                        if (dragOffset > height && dragIndex < tracks.lastIndex) {
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
