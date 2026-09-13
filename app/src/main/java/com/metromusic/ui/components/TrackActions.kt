package com.metromusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroInputBox
import com.metrocompose.MetroListBox
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.lyrics.LyricsStatus
import com.metromusic.data.model.Track

/**
 * The long-press actions shared by every track list in the app.
 *
 * The per-row menu lives with the row (so it opens next to what you pressed), while the
 * follow-up modals — choosing a playlist, naming a new one — are hosted once per screen by
 * [TrackActionsHost]. Screens only have to remember one object and drop the host in.
 */
@Stable
class TrackActions internal constructor() {

    internal var menuFor by mutableStateOf<Track?>(null)
        private set

    internal var pickPlaylistFor by mutableStateOf<Track?>(null)
        private set

    internal var namingPlaylistFor by mutableStateOf<Track?>(null)
        private set

    fun openMenu(track: Track) {
        menuFor = track
    }

    fun isMenuOpen(track: Track): Boolean = menuFor?.id == track.id

    fun closeMenu() {
        menuFor = null
    }

    internal fun startPickPlaylist(track: Track) {
        menuFor = null
        pickPlaylistFor = track
    }

    internal fun startNamePlaylist(track: Track?) {
        pickPlaylistFor = null
        namingPlaylistFor = track
    }

    internal fun dismissAll() {
        menuFor = null
        pickPlaylistFor = null
        namingPlaylistFor = null
    }
}

@Composable
fun rememberTrackActions(): TrackActions = remember { TrackActions() }

/**
 * What starting a track from a list should open, provided by the shell.
 *
 * A composition local rather than a parameter because every list in the app wants the same answer,
 * and threading it through five screens to reach one row would spread a navigation decision across
 * all of them. Defaults to doing nothing, so a row still works in isolation.
 */
val LocalOpenPlayer = staticCompositionLocalOf<() -> Unit> { {} }

/** Same idea for the lyrics page, which is a destination and so also the shell's business. */
val LocalOpenLyrics = staticCompositionLocalOf<(Long) -> Unit> { {} }

/** Where "show lyrics" sits in the built-in part of the menu. */
private const val LyricsIndex = 3

/** Include once per screen that shows tracks. Renders nothing until a menu is opened. */
@Composable
fun TrackActionsHost(actions: TrackActions) {
    val services = LocalServices.current
    val playlists by services.playlists.playlists.collectAsStateWithLifecycle()

    val pickTarget = actions.pickPlaylistFor
    MetroListBox(
        visible = pickTarget != null,
        title = stringResource(R.string.menu_add_to_playlist),
        items = playlists.items.map { it.name } + stringResource(R.string.playlist_new_option),
        onSelect = { index ->
            val track = pickTarget ?: return@MetroListBox
            if (index in playlists.items.indices) {
                services.playlists.add(playlists.items[index].id, listOf(track.id))
                actions.dismissAll()
            } else {
                actions.startNamePlaylist(track)
            }
        },
        onDismiss = { actions.dismissAll() }
    )

    val nameTarget = actions.namingPlaylistFor
    MetroInputBox(
        visible = nameTarget != null,
        title = stringResource(R.string.playlist_new_title),
        placeholder = stringResource(R.string.label_name),
        onConfirm = { name ->
            services.playlists.create(
                name = name,
                trackIds = listOfNotNull(nameTarget?.id),
                now = System.currentTimeMillis()
            )
            actions.dismissAll()
        },
        onDismiss = { actions.dismissAll() }
    )
}

/**
 * A track row wired to [TrackActions]: tap plays and goes to the player, hold opens the WP8
 * context menu.
 *
 * Tapping the row plays it and opens the player — playing something is the point of tapping it, and
 * the player is where you then are. The menu therefore does *not* repeat "play": holding an item is
 * for the things a tap cannot do. What it offers instead is "play next", which is the one queue
 * operation a player like this needs and the one a tap can never mean.
 *
 * "show lyrics" is greyed for songs that are known to have none. The verdict comes from an index
 * filled in in the background as the library is read, so the menu can answer instantly instead of
 * going to the network while it unfolds.
 */
@Composable
fun TrackRowWithActions(
    track: Track,
    actions: TrackActions,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    showArt: Boolean = false,
    trackNumber: Int? = null,
    isCurrent: Boolean = false,
    secondary: String? = null,
    extraActions: List<String> = emptyList(),
    onExtraAction: (Int) -> Unit = {}
) {
    val services = LocalServices.current
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val lyricsKnown by services.lyrics.verdicts.collectAsStateWithLifecycle()
    val isFavorite = track.id in stats.favorites
    val openPlayer = LocalOpenPlayer.current
    val openLyrics = LocalOpenLyrics.current
    val playAndOpen = {
        onPlay()
        openPlayer()
    }

    val builtIn = listOf(
        stringResource(R.string.menu_play_next),
        stringResource(
            if (isFavorite) R.string.menu_favorite_remove else R.string.menu_favorite_add
        ),
        stringResource(R.string.menu_add_to_playlist),
        stringResource(R.string.menu_show_lyrics)
    )

    // Missing is the only "no": a song nobody has looked up yet stays tappable, and looking it up is
    // what the page then does. Reading `lyricsKnown` here is what re-enables the entry when a
    // background probe lands while the list is on screen.
    //
    // The online lookup being switched off is deliberately *not* a no any more. Words can come out of
    // a `.lrc` on the device, which owes nothing to Genius or to a network, so greying the entry out
    // with that switch would hide the files the user put there themselves.
    val lyricsMissing = services.lyrics.statusIn(lyricsKnown, track) == LyricsStatus.Missing
    val disabled = buildSet {
        if (lyricsMissing) add(LyricsIndex)
    }

    MetroContextMenu(
        expanded = actions.isMenuOpen(track),
        items = builtIn + extraActions,
        onSelect = { index ->
            when (index) {
                0 -> {
                    actions.closeMenu()
                    services.player.playNext(track)
                }
                1 -> {
                    actions.closeMenu()
                    services.stats.toggleFavorite(track.id)
                }
                2 -> actions.startPickPlaylist(track)
                LyricsIndex -> {
                    actions.closeMenu()
                    openLyrics(track.id)
                }
                else -> {
                    actions.closeMenu()
                    onExtraAction(index - builtIn.size)
                }
            }
        },
        onDismiss = { actions.closeMenu() },
        disabledItems = disabled
    ) {
        TrackRow(
            track = track,
            onClick = playAndOpen,
            modifier = modifier,
            showArt = showArt,
            trackNumber = trackNumber,
            isCurrent = isCurrent,
            isFavorite = isFavorite,
            secondary = secondary,
            onLongClick = { actions.openMenu(track) }
        )
    }
}
