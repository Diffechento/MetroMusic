package com.metromusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroInputBox
import com.metrocompose.MetroListBox
import com.metromusic.R
import com.metromusic.core.LocalServices

/**
 * "Add these to a playlist", for any number of tracks at once.
 *
 * One object rather than a copy of the same two modals in every screen that can offer it, because
 * the answer to "which playlist" is the same question whether it was asked about one song, a whole
 * album or forty songs somebody ticked — and because adding a track at a time was the thing the
 * feature was reported as missing.
 *
 * Hosted once per screen by [PlaylistPickerHost], the way [TrackActions] is: the menu belongs next
 * to the row that was held, while the panel that follows belongs to the screen.
 */
@Stable
class PlaylistPicker internal constructor() {

    internal var pending by mutableStateOf<List<Long>>(emptyList())
        private set

    internal var choosing by mutableStateOf(false)
        private set

    internal var naming by mutableStateOf(false)
        private set

    /** Nothing to add is not a question worth asking, so an empty list opens nothing. */
    fun open(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        pending = trackIds
        choosing = true
        naming = false
    }

    internal fun startNaming() {
        choosing = false
        naming = true
    }

    internal fun dismiss() {
        choosing = false
        naming = false
        pending = emptyList()
    }
}

@Composable
fun rememberPlaylistPicker(): PlaylistPicker = remember { PlaylistPicker() }

/** Include once per screen that offers "add to playlist". Renders nothing until it is asked. */
@Composable
fun PlaylistPickerHost(picker: PlaylistPicker) {
    val services = LocalServices.current
    val playlists by services.playlists.playlists.collectAsStateWithLifecycle()

    MetroListBox(
        visible = picker.choosing,
        title = stringResource(R.string.menu_add_to_playlist),
        items = playlists.items.map { it.name } + stringResource(R.string.playlist_new_option),
        onSelect = { index ->
            val target = playlists.items.getOrNull(index)
            if (target != null) {
                services.playlists.add(target.id, picker.pending)
                picker.dismiss()
            } else {
                picker.startNaming()
            }
        },
        onDismiss = { picker.dismiss() }
    )

    MetroInputBox(
        visible = picker.naming,
        title = stringResource(R.string.playlist_new_title),
        placeholder = stringResource(R.string.label_name),
        onConfirm = { name ->
            services.playlists.create(
                name = name,
                trackIds = picker.pending,
                now = System.currentTimeMillis()
            )
            picker.dismiss()
        },
        onDismiss = { picker.dismiss() }
    )
}
