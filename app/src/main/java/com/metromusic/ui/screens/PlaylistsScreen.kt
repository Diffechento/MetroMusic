package com.metromusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.ListRow
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroEmptyNote
import com.metrocompose.MetroInputBox
import com.metrocompose.MetroMessageBox
import com.metrocompose.MetroPage
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Library
import com.metromusic.data.playlist.Playlist
import com.metromusic.ui.Glyphs
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.nav.Screen

/**
 * The user's playlists, plus favorites as a permanent pseudo-playlist at the top.
 *
 * Hold a playlist for rename, export and delete; delete asks first, because there is no undo — and
 * now because it removes a file from the device rather than a line from this app's own database.
 *
 * **Import and export are the two halves of the format being worth having.** A playlist here is a
 * `.m3u` in `Music/Playlists`, so anything on the phone can already find it; import is for the ones
 * that are somewhere else entirely — a download, a cloud folder, a card — and export is for handing
 * one to a computer. Import *copies* rather than links, so the folder stays the one place to look.
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
    var exporting by remember { mutableStateOf<Playlist?>(null) }

    val favorites = remember(library, stats.favorites) {
        library.tracks.filter { it.id in stats.favorites }
    }

    // `*/*` rather than the playlist mime types: a `.m3u` arrives from a download or a messenger as
    // `application/octet-stream` about as often as it arrives correctly typed, and a picker that
    // greys out the file the user came to fetch is worse than one that shows too much.
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) services.playlists.import(uri) }

    // The extension already matches the type, so the provider leaves the name alone — the trap the
    // lyrics writer documents, which bites when the two disagree.
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PlaylistMime)
    ) { uri ->
        val target = exporting
        if (uri != null && target != null) services.playlists.export(target.id, uri)
        exporting = null
    }

    Box(Modifier.fillMaxSize()) {
        MetroPage(stringResource(R.string.overline_app), stringResource(R.string.row_playlists)) {
            AppBar(Modifier.padding(bottom = 8.dp)) {
                AppBarButton(Glyphs.Add, stringResource(R.string.action_new)) { creating = true }
                AppBarButton(Glyphs.Import, stringResource(R.string.playlist_import)) {
                    importFile.launch(arrayOf("*/*"))
                }
                AppBarButton(Glyphs.Play, stringResource(R.string.playlists_favorites)) {
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

                if (data.items.isEmpty() && data.loaded) {
                    item { MetroEmptyNote(stringResource(R.string.playlists_empty)) }
                }

                items(data.items, key = { it.id }) { playlist ->
                    MetroContextMenu(
                        expanded = menuFor?.id == playlist.id,
                        items = listOf(
                            stringResource(R.string.action_play),
                            stringResource(R.string.action_rename),
                            stringResource(R.string.playlist_export),
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
                                2 -> if (target != null) {
                                    exporting = target
                                    exportFile.launch(services.playlists.exportName(target.id))
                                }

                                else -> deleting = target
                            }
                        },
                        onDismiss = { menuFor = null }
                    ) {
                        ListRow(
                            primary = playlist.name,
                            secondary = secondaryOf(playlist, library),
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

/**
 * The grey line under a playlist's name: how many songs, and anything odd about the file.
 *
 * The two odd things are worth saying out loud rather than leaving to be discovered. Lines that
 * point at files this device does not have are the normal state of a playlist that came from
 * somewhere else, and they are kept rather than dropped — so the count on the row and the number of
 * rows inside it would otherwise disagree with nothing to explain it. A file another app owns can
 * be edited once the user has agreed to it, and knowing that beforehand is what makes the dialog
 * make sense when it appears.
 *
 * **Counted against the library and not against the file**, which is the same question the
 * playlist's own page asks. A line can name a file MediaStore knows and the app does not — a hidden
 * artist, a track under the duration filter — and counting those as songs here made this row say
 * three where the page underneath it drew two.
 */
@Composable
private fun secondaryOf(playlist: Playlist, library: Library): String {
    val playable = library.resolve(playlist.trackIds).size
    val missing = playlist.entries.size - playable
    val parts = buildList {
        add(formatTrackCount(playable))
        if (missing > 0) add(pluralStringResource(R.plurals.playlist_missing, missing, missing))
        if (!playlist.writable) add(stringResource(R.string.playlist_read_only))
    }
    return parts.joinToString(" · ")
}

/** What a `.m3u` is to the platform — see `PlaylistFiles` for why it is this and not `.m3u8`. */
private const val PlaylistMime = "audio/x-mpegurl"
