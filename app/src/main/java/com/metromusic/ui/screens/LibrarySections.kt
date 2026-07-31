package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.ListRow
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroListBox
import com.metrocompose.MetroLongList
import com.metrocompose.MetroTextBox
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Library
import com.metromusic.data.model.Track
import com.metromusic.ui.components.CollectionRowWithActions
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.rememberCollectionActions
import com.metromusic.ui.components.TrackActions
import com.metromusic.ui.components.TrackRowWithActions
import com.metromusic.ui.components.WideTile
import com.metromusic.data.store.AlbumSort
import com.metromusic.data.store.ArtistSort
import com.metromusic.data.store.SongSort
import com.metromusic.ui.formatAlbumCount
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.rememberAlbumSorts
import com.metromusic.ui.rememberArtistSorts
import com.metromusic.ui.rememberSongSorts
import com.metromusic.ui.nav.Screen

/**
 * The ways of slicing the library, each one a panorama section on the home screen.
 *
 * They are separate composables rather than branches of one screen because the home panorama
 * composes all of them at once, and because each is a whole long list in its own right — the
 * grouping and the jump grid come from [MetroLongList] per section.
 *
 * Every one of them takes its [modifier] and does nothing about its own size: inside a panorama
 * the section decides that (an explicit width, since the scrolling row measures its children
 * unbounded, and a weight for the height left under the header).
 */
/**
 * A section that can be searched: the box appears above the list when [search] is non-null, which is
 * what tapping the section's header does.
 *
 * The box goes inside the section rather than over the panorama because that is where the thing being
 * searched is — the query belongs to this list, and swiping to the next section should leave it
 * behind rather than carry a stale filter along.
 */
@Composable
private fun SearchableSection(
    modifier: Modifier,
    search: String?,
    onSearchChange: (String) -> Unit,
    list: @Composable ColumnScope.(Modifier) -> Unit
) {
    Column(modifier) {
        if (search != null) {
            MetroTextBox(
                value = search,
                onValueChange = onSearchChange,
                placeholder = stringResource(R.string.search_placeholder),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                // The box exists because the header was just tapped, so the keyboard comes with it.
                autoFocus = true
            )
        }
        list(Modifier.weight(1f))
    }
}

/** Case-insensitive "contains", which is what people mean by searching a music library. */
private fun matches(text: String, query: String?): Boolean =
    query.isNullOrBlank() || text.contains(query.trim(), ignoreCase = true)

@Composable
fun ArtistsSection(
    library: Library,
    modifier: Modifier,
    onNavigate: (Screen) -> Unit,
    search: String? = null,
    onSearchChange: (String) -> Unit = {}
) {
    if (library.artists.isEmpty()) {
        EmptyNote(stringResource(R.string.empty_no_artists), modifier)
        return
    }
    val services = LocalServices.current
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val actions = rememberCollectionActions()
    val shown = remember(library.artists, search) {
        library.artists.filter { matches(it.name, search) }
    }
    val sorts = rememberArtistSorts(library, stats.playCounts)

    SearchableSection(modifier, search, onSearchChange) { listModifier ->
    MetroLongList(
        items = shown,
        key = { it.id },
        sort = sorts[settings.artistSort.ordinal],
        modifier = listModifier,
        filledGroupHeaders = settings.letterTiles,
        sorts = sorts,
        onSortSelected = { index -> services.settings.setArtistSort(ArtistSort.entries[index]) }
    ) { artist ->
        CollectionRowWithActions(
            key = artist.id,
            actions = actions,
            onPlay = { services.player.play(library.tracksOfArtist(artist.id)) },
            onPlayNext = {
                services.player.playNext(library.tracksOfArtist(artist.id), artist.name)
            },
            onHide = { services.hidden.hideArtist(artist.name) }
        ) {
            ListRow(
                primary = artist.name,
                secondary = "${formatAlbumCount(artist.albumCount)} · " +
                    formatTrackCount(artist.trackCount),
                onClick = { onNavigate(Screen.ArtistDetail(artist.id)) },
                onLongClick = { actions.open(artist.id) }
            )
        }
    }
    }
}

@Composable
fun AlbumsSection(
    library: Library,
    modifier: Modifier,
    onNavigate: (Screen) -> Unit,
    search: String? = null,
    onSearchChange: (String) -> Unit = {}
) {
    if (library.albums.isEmpty()) {
        EmptyNote(stringResource(R.string.empty_no_albums), modifier)
        return
    }
    val services = LocalServices.current
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val actions = rememberCollectionActions()
    val shown = remember(library.albums, search) {
        library.albums.filter { matches(it.title, search) || matches(it.artist, search) }
    }
    val sorts = rememberAlbumSorts()

    SearchableSection(modifier, search, onSearchChange) { listModifier ->
    MetroLongList(
        items = shown,
        key = { it.id },
        sort = sorts[settings.albumSort.ordinal],
        modifier = listModifier,
        // The same answer as every other list, from settings: two sections disagreeing about their
        // own group letters is what made this section look like a different app.
        filledGroupHeaders = settings.letterTiles,
        sorts = sorts,
        onSortSelected = { index -> services.settings.setAlbumSort(AlbumSort.entries[index]) }
    ) { album ->
        CollectionRowWithActions(
            key = album.id,
            actions = actions,
            onPlay = { services.player.play(library.tracksOf(album)) },
            onPlayNext = { services.player.playNext(library.tracksOf(album), album.title) },
            onHide = { services.hidden.hideAlbum(album.artist, album.title) },
            onEdit = { onNavigate(Screen.AlbumEdit(album.id)) }
        ) {
            WideTile(
                albumId = album.id,
                representativeTrackId = album.representativeTrackId,
                primary = album.title,
                secondary = album.artist,
                onClick = { onNavigate(Screen.AlbumDetail(album.id)) },
                continuumKey = "album-art-${album.id}",
                onLongClick = { actions.open(album.id) }
            )
        }
    }
    }
}

/**
 * Songs, arranged whichever way the user last asked for — the one section where that is offered.
 *
 * The handle is the group header itself: hold it and the four arrangements come up in a WP8 list
 * picker (the framework's, on `MetroListSort`), with the one in force in accent. Holding the heading
 * rather than putting a control in the app bar is what keeps the section a list and not a screen with
 * a toolbar, and the heading is already the thing you press to zoom out to the alphabet.
 *
 * [onPlay] is handed the whole list *as it is arranged*, not just the track: tapping a song plays
 * from there onwards, and "from there" has to mean what is on screen — sorting by play count and
 * tapping the top song would otherwise carry on alphabetically from wherever that song happens to sit
 * in the library.
 */
@Composable
fun SongsSection(
    library: Library,
    modifier: Modifier,
    currentTrackId: Long?,
    actions: TrackActions,
    onPlay: (List<Track>, Int) -> Unit,
    search: String? = null,
    onSearchChange: (String) -> Unit = {}
) {
    if (library.tracks.isEmpty()) {
        EmptyNote(stringResource(R.string.empty_no_songs), modifier)
        return
    }
    val services = LocalServices.current
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val shown = remember(library.tracks, search) {
        library.tracks.filter { matches(it.title, search) || matches(it.artist, search) }
    }

    val sorts = rememberSongSorts(stats.playCounts)
    val sort = sorts[settings.songSort.ordinal]

    SearchableSection(modifier, search, onSearchChange) { listModifier ->
        MetroLongList(
            items = shown,
            key = { it.id },
            sort = sort,
            modifier = listModifier,
            filledGroupHeaders = settings.letterTiles,
            sorts = sorts,
            onSortSelected = { index -> services.settings.setSongSort(SongSort.entries[index]) }
        ) { track ->
            TrackRowWithActions(
                track = track,
                actions = actions,
                // Ordered on the tap rather than per frame: the arranged list is the framework's,
                // and re-deriving it here for every visible row would sort the library each time one
                // scrolled past.
                onPlay = {
                    val queue = shown.sortedWith(sort.comparator)
                    onPlay(queue, queue.indexOf(track))
                },
                isCurrent = track.id == currentTrackId
            )
        }
    }
}

/**
 * Genres, on the platforms that have them.
 *
 * Present even when empty, unlike before: the home panorama wraps and numbers its pages modulo the
 * section count, so a section that appeared when the scan found its first genre tag would shift every
 * other section along. An empty note is the cheaper honesty.
 */
@Composable
fun GenresSection(library: Library, modifier: Modifier, onNavigate: (Screen) -> Unit) {
    if (library.genres.isEmpty()) {
        EmptyNote(stringResource(R.string.empty_no_genres), modifier)
        return
    }
    val services = LocalServices.current
    val actions = rememberCollectionActions()
    // Which genre is waiting to be merged into another; the picker below is what chooses the target.
    var mergeSource by remember { mutableStateOf<String?>(null) }

    // Counting per genre in one pass beats filtering the whole track list per row.
    val counts = remember(library) { library.tracks.groupingBy { it.genre }.eachCount() }
    LazyColumn(modifier) {
        items(library.genres, key = { it }) { genre ->
            val tracks = remember(library, genre) { library.tracksOfGenre(genre) }
            MetroContextMenu(
                expanded = actions.isOpen(genre),
                items = listOf(
                    stringResource(R.string.action_play),
                    stringResource(R.string.menu_play_next),
                    stringResource(R.string.menu_merge_genre)
                ),
                onSelect = { index ->
                    actions.close()
                    when (index) {
                        0 -> services.player.play(tracks)
                        1 -> services.player.playNext(tracks, genre)
                        else -> mergeSource = genre
                    }
                },
                onDismiss = { actions.close() }
            ) {
                ListRow(
                    primary = genre,
                    secondary = formatTrackCount(counts[genre] ?: 0),
                    onClick = { onNavigate(Screen.GenreDetail(genre)) },
                    onLongClick = { actions.open(genre) }
                )
            }
        }
        // The section reaches the bottom edge of the screen, so the list carries the gesture bar's
        // room at its end instead of stopping short of it.
        item { MetroBottomInset(extra = 20.dp) }
    }

    // Everything except the genre being merged — merging something into itself is the one choice that
    // cannot mean anything.
    val targets = remember(library.genres, mergeSource) {
        library.genres.filterNot { it == mergeSource }
    }
    MetroListBox(
        visible = mergeSource != null,
        title = stringResource(R.string.menu_merge_genre),
        items = targets,
        onSelect = { index ->
            val from = mergeSource
            if (from != null) services.genres.merge(from, targets[index])
            mergeSource = null
        },
        onDismiss = { mergeSource = null }
    )
}
