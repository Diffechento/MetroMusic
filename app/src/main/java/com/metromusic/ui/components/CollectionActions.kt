package com.metromusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.metrocompose.MetroContextMenu
import com.metromusic.R

/**
 * Which album or artist is currently showing its long-press menu.
 *
 * One holder per list rather than a boolean per row: only one menu can be open at a time, and the
 * row that owns it is the one whose key matches. Keys are whatever the list already has — an id.
 */
@Stable
class CollectionActions internal constructor() {

    internal var openFor by mutableStateOf<Any?>(null)
        private set

    fun open(key: Any) {
        openFor = key
    }

    fun isOpen(key: Any): Boolean = openFor == key

    fun close() {
        openFor = null
    }
}

@Composable
fun rememberCollectionActions(): CollectionActions = remember { CollectionActions() }

/**
 * The long-press menu an album or an artist gets — the same sheet a track gets, with the actions
 * that make sense for a whole record or a whole discography.
 *
 * "hide" is the one that isn't obvious: it takes the artist or album out of every list in the app
 * until you put it back from settings. That is what a library full of ringtones, podcasts ripped as
 * albums and "Unknown artist" needs, and deleting the files is not an acceptable substitute.
 *
 * "add to playlist" is here because the alternative was holding thirteen rows in turn: a record is
 * the unit people add, and the menu that already knows which record was held is where to say so.
 *
 * The entries are built as label-and-action pairs rather than a list of strings and a `when` over
 * indices. That is not tidiness — an index is what the old version got wrong the moment an entry
 * became conditional, and there are two conditional entries now.
 */
@Composable
fun CollectionRowWithActions(
    key: Any,
    actions: CollectionActions,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onHide: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val entries = buildList<Pair<String, () -> Unit>> {
        add(stringResource(R.string.action_play) to onPlay)
        add(stringResource(R.string.menu_play_next) to onPlayNext)
        if (onAddToPlaylist != null) {
            add(stringResource(R.string.menu_add_to_playlist) to onAddToPlaylist)
        }
        // "edit" only where there is something to edit: an album has metadata of its own, an artist
        // is only the name its tracks carry.
        if (onEdit != null) add(stringResource(R.string.menu_edit) to onEdit)
        add(stringResource(R.string.menu_hide) to onHide)
    }
    MetroContextMenu(
        expanded = actions.isOpen(key),
        items = entries.map { it.first },
        onSelect = { index ->
            actions.close()
            entries.getOrNull(index)?.second?.invoke()
        },
        onDismiss = { actions.close() },
        content = content
    )
}
