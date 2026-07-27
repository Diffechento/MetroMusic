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
 */
@Composable
fun CollectionRowWithActions(
    key: Any,
    actions: CollectionActions,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onHide: () -> Unit,
    onEdit: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // "edit" only where there is something to edit: an album has metadata of its own, an artist is
    // only the name its tracks carry.
    val editable = onEdit != null
    val items = buildList {
        add(stringResource(R.string.action_play))
        add(stringResource(R.string.menu_play_next))
        if (editable) add(stringResource(R.string.menu_edit))
        add(stringResource(R.string.menu_hide))
    }
    MetroContextMenu(
        expanded = actions.isOpen(key),
        items = items,
        onSelect = { index ->
            actions.close()
            when {
                index == 0 -> onPlay()
                index == 1 -> onPlayNext()
                editable && index == 2 -> onEdit()
                else -> onHide()
            }
        },
        onDismiss = { actions.close() },
        content = content
    )
}
