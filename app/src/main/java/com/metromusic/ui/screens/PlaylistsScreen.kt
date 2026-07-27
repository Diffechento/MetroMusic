package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.ListRow
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroInputBox
import com.metrocompose.MetroMessageBox
import com.metrocompose.MetroPage
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.store.Playlist
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen

/**
 * The user's playlists, plus favorites as a permanent pseudo-playlist at the top.
 *
 * Hold a playlist for rename and delete; delete asks first, because there is no undo.
 */
@Composable
fun PlaylistsScreen(onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val data by services.playlists.playlists.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Playlist?>(null) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    val favorites = remember(library, stats.favorites) {
        library.tracks.filter { it.id in stats.favorites }
    }

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.row_playlists)) {
            AppBar(Modifier.padding(bottom = 8.dp)) {
                AppBarButton("+", stringResource(R.string.action_new)) { creating = true }
                AppBarButton("▶", stringResource(R.string.playlists_favorites)) {
                    if (favorites.isNotEmpty()) services.player.play(favorites, 0)
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    ListRow(
                        primary = stringResource(R.string.playlists_favorites),
                        secondary = formatTrackCount(favorites.size),
                        onClick = { if (favorites.isNotEmpty()) services.player.play(favorites, 0) }
                    )
                }

                if (data.items.isEmpty()) {
                    item { EmptyNote(stringResource(R.string.playlists_empty)) }
                }

                items(data.items, key = { it.id }) { playlist ->
                    MetroContextMenu(
                        expanded = menuFor?.id == playlist.id,
                        items = listOf(
                            stringResource(R.string.action_play),
                            stringResource(R.string.action_rename),
                            stringResource(R.string.action_delete)
                        ),
                        onSelect = { index ->
                            val target = menuFor
                            menuFor = null
                            when (index) {
                                0 -> {
                                    val tracks = library.resolve(target?.trackIds.orEmpty())
                                    if (tracks.isNotEmpty()) services.player.play(tracks, 0)
                                }
                                1 -> renaming = target
                                else -> deleting = target
                            }
                        },
                        onDismiss = { menuFor = null }
                    ) {
                        ListRow(
                            primary = playlist.name,
                            secondary = formatTrackCount(playlist.trackIds.size),
                            onLongClick = { menuFor = playlist },
                            onClick = { onNavigate(Screen.PlaylistDetail(playlist.id)) }
                        )
                    }
                }
            }
        }
    }

    MetroInputBox(
        visible = creating,
        title = stringResource(R.string.playlist_new_title),
        placeholder = stringResource(R.string.label_name),
        onConfirm = { name ->
            services.playlists.create(name = name, now = System.currentTimeMillis())
            creating = false
        },
        onDismiss = { creating = false }
    )

    val renameTarget = renaming
    MetroInputBox(
        visible = renameTarget != null,
        title = stringResource(R.string.playlist_rename_title),
        initial = renameTarget?.name.orEmpty(),
        onConfirm = { name ->
            renameTarget?.let { services.playlists.rename(it.id, name) }
            renaming = null
        },
        onDismiss = { renaming = null }
    )

    val deleteTarget = deleting
    MetroMessageBox(
        visible = deleteTarget != null,
        title = stringResource(R.string.playlist_delete_title),
        message = stringResource(
            R.string.playlist_delete_message,
            deleteTarget?.name.orEmpty()
        ),
        confirm = stringResource(R.string.action_delete),
        onConfirm = {
            deleteTarget?.let { services.playlists.delete(it.id) }
            deleting = null
        },
        onDismiss = { deleting = null }
    )
}
