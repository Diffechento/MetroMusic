package com.metromusic.ui.nav

/**
 * Which page of settings a [Screen.SettingsDetail] is showing.
 *
 * Settings are a section of the home panorama listing these, rather than one long scroll: on the
 * phone each group of settings is its own page you go into and come back from, and the panorama's
 * section is the index of them.
 */
enum class SettingsPage {
    Theme, Interface, Gestures, Equalizer, LastFm, Playback, Library, Hidden, About
}

/**
 * Every destination in the app.
 *
 * A sealed interface rather than string routes, so arguments are typed and the `when` in
 * [com.metromusic.MetroMusicRoot] is exhaustive — add a destination and the compiler points
 * at the one place that still needs handling.
 *
 * [encode]/[decode] exist only so the back stack can survive process death; they are a
 * private detail of persistence, not how you navigate.
 */
sealed interface Screen {

    data object Collection : Screen
    data object Playlists : Screen
    data object Settings : Screen

    /**
     * One query against the whole library, as opposed to the panorama's per-section box.
     *
     * A destination and not a filter on the panorama: it is a place you go and come back from, and
     * the thing it finds is as likely to be an artist or a playlist as a song in the list you
     * happened to be looking at.
     */
    data object Search : Screen

    data class SettingsDetail(val page: SettingsPage) : Screen
    data class AlbumDetail(val albumId: Long) : Screen
    data class AlbumEdit(val albumId: Long) : Screen
    data class ArtistDetail(val artistId: Long) : Screen
    data class GenreDetail(val genre: String) : Screen
    data class PlaylistDetail(val playlistId: String) : Screen

    /**
     * Ticking songs to add to one playlist.
     *
     * A destination rather than a panel over the playlist: choosing twenty songs out of a library
     * needs a search box and a list that scrolls, which is a page. [playlistId] is a file — see
     * [com.metromusic.data.playlist.Playlist] — so it may contain anything a file name may, which
     * [encode] copes with by putting it last.
     */
    data class PlaylistAdd(val playlistId: String) : Screen
    data class Lyrics(val trackId: Long) : Screen

    companion object {

        fun encode(screen: Screen): String = when (screen) {
            Collection -> "collection"
            Playlists -> "playlists"
            Settings -> "settings"
            Search -> "search"
            is SettingsDetail -> "setting:${screen.page.name}"
            is AlbumDetail -> "album:${screen.albumId}"
            is AlbumEdit -> "album-edit:${screen.albumId}"
            is ArtistDetail -> "artist:${screen.artistId}"
            is GenreDetail -> "genre:${screen.genre}"
            is PlaylistDetail -> "playlist:${screen.playlistId}"
            is PlaylistAdd -> "playlist-add:${screen.playlistId}"
            is Lyrics -> "lyrics:${screen.trackId}"
        }

        fun decode(value: String): Screen {
            val separator = value.indexOf(':')
            if (separator < 0) {
                return when (value) {
                    "playlists" -> Playlists
                    "settings" -> Settings
                    "search" -> Search
                    else -> Collection
                }
            }
            val tag = value.substring(0, separator)
            val argument = value.substring(separator + 1)
            return when (tag) {
                "setting" -> SettingsPage.entries
                    .firstOrNull { it.name == argument }
                    ?.let(::SettingsDetail)
                    ?: Settings
                "album" -> argument.toLongOrNull()?.let(::AlbumDetail) ?: Collection
                "album-edit" -> argument.toLongOrNull()?.let(::AlbumEdit) ?: Collection
                "artist" -> argument.toLongOrNull()?.let(::ArtistDetail) ?: Collection
                "genre" -> GenreDetail(argument)
                "playlist" -> PlaylistDetail(argument)
                "playlist-add" -> PlaylistAdd(argument)
                "lyrics" -> argument.toLongOrNull()?.let(::Lyrics) ?: Collection
                else -> Collection
            }
        }

        /**
         * Deep-link targets used by the home-screen tile's `screen` extra.
         *
         * "Library" still resolves, to the home panorama: the library stopped being a screen of
         * its own when its sections moved onto that panorama, and a tile or a shell command
         * pointing at it should land on the same content rather than fail. "NowPlaying" resolves
         * here too, and is picked up separately by [widgetTargetIsPlayer] — the player is an
         * overlay over whatever page is showing, not a destination of its own.
         */
        fun fromWidgetTarget(target: String?): Screen = when (target) {
            "Playlists" -> Playlists
            "Settings" -> Settings
            else -> Collection
        }

        /** Whether a widget target asks for the player to be open on top of the page. */
        fun widgetTargetIsPlayer(target: String?): Boolean = target == "NowPlaying"
    }
}
