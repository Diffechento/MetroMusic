package com.metromusic.ui.screens

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.ListRow
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroPanorama
import com.metrocompose.MetroPanoramaSection
import com.metrocompose.MetroProgressDots
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.model.Album
import com.metromusic.data.store.LibrarySection
import com.metromusic.ui.components.AppBackdrop
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.components.TrackActionsHost
import com.metromusic.ui.components.WideTile
import com.metromusic.ui.components.rememberTrackActions
import com.metromusic.ui.nav.Screen
import com.metromusic.ui.nav.SettingsPage

/**
 * The home page, and the whole library on it: a WP8 panorama whose sections *are* the ways of
 * slicing the collection rather than a menu that leads to them.
 *
 * That is the point of the arrangement — artists, albums and songs are one swipe from launch, not
 * one tap and then a swipe. The panorama is circular, so the *last* section is also one swipe to the
 * left of the first; settings live there, which is how they are always a single gesture away without
 * taking a place among the things you actually came for.
 *
 * Order comes from settings, so the user can put what they use first. Only the sections near the
 * viewport are composed, so it costs a screenful however many there are.
 */
@Composable
fun CollectionScreen(onNavigate: (Screen) -> Unit) {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val playlists by services.playlists.playlists.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val scanning by services.library.scanning.collectAsStateWithLifecycle()
    val loaded by services.library.loaded.collectAsStateWithLifecycle()
    val actions = rememberTrackActions()

    // Which section's header was tapped, and what has been typed into it. One query at a time: the
    // box belongs to a section, and swiping away from that section puts it away with its filter.
    var searchIn by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    fun toggleSearch(section: LibrarySection) {
        searchIn = if (searchIn == section.name) null else section.name
        query = ""
    }
    fun searchFor(section: LibrarySection): String? = query.takeIf { searchIn == section.name }

    // Going somewhere puts the search away. Coming back to a panorama still holding the box, the
    // keyboard and a filtered list is coming back to unfinished business the user has already left.
    fun leave(screen: Screen) {
        searchIn = null
        query = ""
        onNavigate(screen)
    }

    val recentAlbums = remember(library, stats.recentAlbumIds) {
        stats.recentAlbumIds.mapNotNull(library::album)
    }

    // Genres need API 30+ to exist at all, so below that the section is left out. It is *not* left
    // out for a library that simply has no genre tags: the panorama reads its page numbers modulo the
    // section count, so a section appearing once the scan finishes would renumber every page and jump
    // the user somewhere else. Deciding on the API level keeps the count fixed for the whole session.
    val sections = remember(settings.sectionOrder) {
        settings.sections.filterNot {
            it == LibrarySection.Genres && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        }
    }

    Box(Modifier.fillMaxSize()) {
        MetroPanorama(
            // The app's own name as the big title, and no overline above it. "METROMUSIC /
            // collection" said the same thing twice and cost a line of height at the top of the one
            // screen where height is the scarce thing — the sections underneath want every pixel.
            title = stringResource(R.string.app_name),
            overline = null,
            // The same drawing the shell puts behind every other page, so the wallpaper does not
            // change when you leave the panorama — here it parallaxes, there it stays put.
            background = { AppBackdrop() },
            // The gradient is drawn to wrap and can be tiled; an album cover cannot — two copies of
            // it meet in a hard vertical line through the picture. Which one the backdrop is drawing
            // is a setting, so this follows the same setting.
            backgroundTiles = !settings.artworkBackground,
            // The rows inside these sections bring their own 24dp gutter, so the panorama keeps
            // almost none of its own and each section header takes the same indent instead. That
            // way header and rows sit on one edge — see MetroPanoramaSection's headerPadding.
            sectionPadding = 2.dp,
            // Settings → interface. The title is worth a row and a half of library, and whether that
            // is a good trade is the user's call.
            collapsingTitle = settings.collapseTitle,
            sections = sections.map { section ->
                MetroPanoramaSection(
                    title = stringResource(sectionTitleOf(section)),
                    key = section,
                    headerPadding = 24.dp,
                    // Only the sections that are lists of named things can be searched; tapping
                    // "more" or "settings" has nothing to filter.
                    onHeaderClick = if (section in Searchable) {
                        { toggleSearch(section) }
                    } else {
                        null
                    }
                ) {
                    // The list gets the height left under the header and the section's own width.
                    val modifier = Modifier.fillMaxWidth().weight(1f)
                    when (section) {
                        LibrarySection.Artists -> ArtistsSection(
                            library = library,
                            modifier = modifier,
                            onNavigate = ::leave,
                            search = searchFor(section),
                            onSearchChange = { query = it }
                        )
                        LibrarySection.Albums -> AlbumsSection(
                            library = library,
                            modifier = modifier,
                            onNavigate = ::leave,
                            search = searchFor(section),
                            onSearchChange = { query = it }
                        )
                        LibrarySection.Songs -> SongsSection(
                            library = library,
                            modifier = modifier,
                            currentTrackId = playerState.trackId,
                            actions = actions,
                            // The queue is the list the way the section is showing it — sorted the way
                            // the user asked and filtered by whatever is in the search box — and the
                            // index into it comes with it. Both are resolved on the tap, not during
                            // composition: an indexOf per visible row is O(n) per frame.
                            onPlay = { queue, index ->
                                services.player.play(queue, index)
                                searchIn = null
                                query = ""
                            },
                            search = searchFor(section),
                            onSearchChange = { query = it }
                        )
                        LibrarySection.Genres -> GenresSection(library, modifier, onNavigate)
                        LibrarySection.More -> MoreSection(
                            modifier = modifier,
                            playlistCount = playlists.items.size,
                            scanning = scanning,
                            onRescan = { services.library.rescan() },
                            onNavigate = onNavigate
                        )
                        LibrarySection.History -> HistorySection(
                            modifier = modifier,
                            albums = recentAlbums,
                            loaded = loaded,
                            onNavigate = onNavigate
                        )
                        LibrarySection.Settings -> SettingsSection(modifier, onNavigate)
                    }
                }
            }
        )
        TrackActionsHost(actions)
    }
}

/** The sections whose header opens a search box: the ones that are lists of named things. */
private val Searchable = setOf(
    LibrarySection.Artists,
    LibrarySection.Albums,
    LibrarySection.Songs
)

/**
 * What each section is called, as a resource id.
 *
 * Shared with the interface settings page, which lists the same sections to reorder them: a section
 * named one thing on the panorama and another in the list that reorders it would be a small lie.
 */
@StringRes
internal fun sectionTitleOf(section: LibrarySection): Int = when (section) {
    LibrarySection.Artists -> R.string.section_artists
    LibrarySection.Albums -> R.string.section_albums
    LibrarySection.Songs -> R.string.section_songs
    LibrarySection.Genres -> R.string.section_genres
    LibrarySection.More -> R.string.section_more
    LibrarySection.History -> R.string.section_history
    LibrarySection.Settings -> R.string.section_settings
}

@Composable
private fun MoreSection(
    modifier: Modifier,
    playlistCount: Int,
    scanning: Boolean,
    onRescan: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    LazyColumn(modifier) {
        // The way in to searching the whole library at once. It lives here rather than on the
        // panorama's title because the title has a job already — tapping a *section* header opens
        // that section's own box — and two searches reachable by two taps on the same screen would
        // be a puzzle about which one you were about to get.
        item {
            ListRow(
                primary = stringResource(R.string.search_title),
                secondary = stringResource(R.string.search_everywhere),
                onClick = { onNavigate(Screen.Search) }
            )
        }
        item {
            ListRow(stringResource(R.string.row_playlists), "$playlistCount") {
                onNavigate(Screen.Playlists)
            }
        }
        item {
            ListRow(
                primary = stringResource(R.string.row_rescan),
                secondary = stringResource(R.string.row_rescan_hint),
                onClick = onRescan
            )
        }
        if (scanning) {
            item {
                Box(Modifier.padding(top = 20.dp, start = 24.dp, end = 24.dp)) {
                    MetroProgressDots()
                }
            }
        }
        // The panorama's sections run to the bottom edge of the screen, so every list inside one
        // ends with the gesture bar's worth of room — the framework's own lists do this themselves.
        item { MetroBottomInset(extra = SectionEndGap) }
    }
}

/** Space after the last row of a hand-rolled section list, matching what MetroLongList leaves. */
private val SectionEndGap = 20.dp

/**
 * Settings as a section of the panorama: an index of pages, not a wall of switches.
 *
 * It is last in the default order and the panorama wraps, so this is one swipe *left* of the landing
 * section — the place a WP8 user would look for it, without it sitting between the library and the
 * music.
 */
@Composable
private fun SettingsSection(modifier: Modifier, onNavigate: (Screen) -> Unit) {
    LazyColumn(modifier) {
        items(SettingsPage.entries, key = { it.name }) { page ->
            SettingsIndexRow(page, onNavigate)
        }
        item { MetroBottomInset(extra = SectionEndGap) }
    }
}

/**
 * One row of the settings index. Its name and description come from the same place the standalone
 * settings page reads them, so the two lists cannot drift apart.
 */
@Composable
private fun SettingsIndexRow(page: SettingsPage, onNavigate: (Screen) -> Unit) {
    ListRow(
        primary = stringResource(titleOf(page)),
        secondary = subtitleOf(page)?.let { stringResource(it) },
        onClick = { onNavigate(Screen.SettingsDetail(page)) }
    )
}

@Composable
private fun HistorySection(
    modifier: Modifier,
    albums: List<Album>,
    loaded: Boolean,
    onNavigate: (Screen) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyNote(
            text = stringResource(
                if (loaded) R.string.empty_history else R.string.empty_history_loading
            ),
            modifier = modifier
        )
        return
    }
    LazyColumn(modifier) {
        items(albums, key = { it.id }) { album ->
            WideTile(
                albumId = album.id,
                representativeTrackId = album.representativeTrackId,
                primary = album.title,
                secondary = album.artist,
                onClick = { onNavigate(Screen.AlbumDetail(album.id)) },
                // No continuum key: an album listed here is usually also in the albums section,
                // and two live elements sharing one key are taken for the same object and laid
                // out on top of each other.
            )
        }
        item { MetroBottomInset(extra = SectionEndGap) }
    }
}

