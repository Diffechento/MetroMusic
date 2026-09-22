package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The sections of the home panorama, in the order they ship in.
 *
 * The user can reorder them (settings → interface), so the panorama is built from a list of these
 * rather than from a hard-coded sequence of composables. The names are what gets persisted, which is
 * why they are an enum and not indices: inserting a section in a later version must not silently
 * shuffle everyone's saved order.
 *
 * [Settings] is deliberately last. The panorama wraps, so the last section is one backward swipe
 * from the first — which is how settings ends up "to the left of the start screen" without being
 * buried in a submenu.
 */
enum class LibrarySection {
    Artists, Albums, Songs, Genres, More, History, Settings
}

/**
 * How each of the three searchable sections is arranged — picked by holding a group header, and
 * remembered.
 *
 * Remembered because it is a way of *reading* the library rather than a one-off filter: someone who
 * sorts by play count is asking the app what they listen to, and being handed the alphabet again on
 * the next launch answers a question they did not ask. The search box is the opposite case and is
 * deliberately dropped when you leave.
 *
 * One setting per section rather than one for all three: they are different questions. "What did I add
 * last" is asked of songs and of albums; "who do I listen to most" only of artists, and an artist has
 * no length to sort by at all.
 *
 * Persisted by name, for the reason [LibrarySection] is: adding an arrangement must not silently
 * change what an older settings file meant.
 */
enum class SongSort {
    Name, DateAdded, Duration, PlayCount
}

/** How the albums section is arranged; see [SongSort]. */
enum class AlbumSort {
    Name, Artist, DateAdded, Year
}

/** How the artists section is arranged; see [SongSort]. */
enum class ArtistSort {
    Name, Songs, Albums, PlayCount
}

/**
 * The order the albums on one artist's page are listed in.
 *
 * Separate from [AlbumSort], which arranges the albums *section* — a whole library of records by
 * everybody, where the question is where a title sits in the alphabet. One artist's page is the
 * other question: a discography, which is read as a run of releases in time. Hence two directions
 * through those releases and one through the alphabet, with [Year] — newest first — the default
 * because it is what the page has always done and what a discography usually means.
 *
 * **[Year] means newest first and [YearOldest] means oldest first**, which is a clumsy pair of names
 * and is deliberate: [Year] is written into settings files that already exist, and persistence is by
 * name, so renaming it would silently hand everyone who chose it the default back.
 *
 * Persisted by name, like the sorts above; the declaration order here is the order of the picker.
 */
enum class ArtistAlbumOrder {
    Year, YearOldest, Name
}

/**
 * What the player draws in the square where the cover goes.
 *
 * [Cover] is the artwork, which is what the player has always shown. [Lyrics] puts the song's words
 * there instead, following the music line by line where the words carry timings — and it does *not*
 * take the artwork off the screen, because the player's whole backdrop is that same cover: the
 * square stops being a second, smaller copy of it and becomes something else to look at.
 *
 * A track with no words to show falls back to [Cover] by itself, so the square is never empty and
 * nothing has to be switched back for the one song in a library that nobody has written lyrics for.
 *
 * Persisted by name, like the sorts and [LyricsSource]: adding a third face later must not silently
 * change what an older settings file meant.
 */
enum class PlayerFace {
    Cover, Lyrics
}

/**
 * Which service is asked for the words of a song that has none on the device.
 *
 * The two are different products rather than the same one twice, which is why this is a choice the
 * user makes and not a fallback order picked here. **LRCLIB** is a community database of `.lrc`
 * files: it is the only one of the two that can answer with timings, so it is the only way the page
 * follows the music for a track that carries no file of its own. **Genius** is an editorial lyrics
 * site with deeper coverage — obscure releases, non-English catalogues — and no timings at all,
 * ever, because it publishes words rather than `.lrc`.
 *
 * Persisted by name, like [LibrarySection] and the sorts: adding a service later must not silently
 * change what an older settings file meant.
 */
enum class LyricsSource {
    LrcLib, Genius
}

/**
 * User preferences. [accentArgb], [dark] and [backgroundArgb] feed straight into the framework's
 * `MetroTheme`, so changing them re-themes the whole app on the next frame.
 *
 * Every field has a default, and the store keeps unknown fields out of the way, so a settings file
 * written by an older build still loads.
 */
@Serializable
data class Settings(
    val accentArgb: Int = DefaultAccent,
    val dark: Boolean = true,
    /** Files shorter than this are treated as ringtones and hidden from the library. */
    val minTrackSeconds: Int = 30,
    /**
     * Treat genre tags that differ only in case or edge punctuation as one genre.
     *
     * On by default: "Electro", "electro" and "electro." in one library is a defect in the tags, not
     * three genres, and nobody wants to be asked about it before it is fixed.
     */
    val fixGenreDoubling: Boolean = true,
    /**
     * Treat names that differ only in the punctuation between their words as one artist.
     *
     * On by default, for the reason [fixGenreDoubling] is: `Blink 182`, `Blink-182` and `Blink‐182`
     * in one library is a defect in the tags and not three bands, and nobody wants to be asked about
     * it before it is fixed. The spelling kept is the one most of the files use — 99 tracks say
     * `Blink-182` and 14 say `Blink 182`, so the library says what 99 of them say.
     *
     * Separators collapse to a space rather than to nothing, so `Dr. Dre` meets `Dr Dre` while
     * `M.I.A.` and `MIA` stay apart — see `looseFold`. Read while scanning, so changing it re-runs
     * the scan.
     */
    val fixArtistDoubling: Boolean = true,
    /**
     * File a track under every artist its credit names, rather than under the credit as one string.
     *
     * On by default: "Artist", "Artist feat. Guest" and "Artist, Other" in the tags is one performer
     * and three sections otherwise, and the section you were looking for is whichever of them you did
     * not tap. A switch, because the separators it splits on are also punctuation inside band names —
     * "Earth, Wind & Fire" becomes two artists — and the only way to know that has happened to *your*
     * library is to be able to turn it off. See `splitArtists`.
     */
    val splitArtistCredits: Boolean = true,
    /**
     * Let the album artist tag say who a record is by, instead of its first track's credit.
     *
     * On by default, and it is the answer for the two cases the credit cannot give one for: a
     * compilation, which is otherwise filed under whoever happens to open it, and a record whose
     * every track reads "band feat. somebody", which is by the band and says so nowhere else.
     *
     * A switch all the same, because badly tagged files are the norm and this one *moves albums*:
     * a ripper that wrote the composer, or the label, or "Various Artists" over a perfectly ordinary
     * record puts it somewhere its owner will not look, and the only way to find that out about your
     * own library is to be able to turn it off. Read while scanning, so changing it re-runs the scan.
     *
     * Needs API 30 for `ALBUM_ARTIST`, like genres; below that it reads as off.
     */
    val useAlbumArtist: Boolean = true,
    /**
     * File every track under **one** artist: the first name its album artist tag gives.
     *
     * Off by default, and it is the other answer to the problem [splitArtistCredits] solves rather
     * than a refinement of it. A track tagged
     * `"Gorillaz, National Orchestra for Arabic Music, Bashy, Kano"` is one record by one band, and
     * splitting that credit files it under four artists — three of whom have nothing else in the
     * library, so the list fills with names that lead to a single guest appearance and the band you
     * were looking for is harder to find rather than easier. The album artist tag already says which
     * of those four the record is by, so where a file carries one, that is who it is filed under and
     * the guests are not artists at all.
     *
     * **The first name of that tag, not all of them**, because plenty of rips write the whole feature
     * list into the album artist field as well — `"angel vox; BLVCK CVRNVGE"`, `"KUNTEYNIR, Сухарь,
     * Блёв Мс"` — and taking every name back out of it leaves exactly the list this setting exists to
     * get rid of. Measured against such a library, 967 tracks: every name gave 95 artists of whom 41
     * held one track, the first name alone gives 58, and what went were the guests.
     *
     * The credit itself is untouched — a row still reads what the file says — and a file with **no**
     * album artist tag is filed under the first name of its own credit, split or whole as
     * [splitArtistCredits] says: the alternative is a library where every badly tagged track is
     * missing from the artists section altogether.
     *
     * Needs the album artist tag to be believed at all, so turning it on turns [useAlbumArtist] on
     * and turning that off turns this off — see [SettingsStore.setArtistsFromAlbumArtist]. Read while
     * scanning, so changing it re-runs the scan.
     */
    val artistsFromAlbumArtist: Boolean = false,
    /**
     * Where a tag names several artists, file the track under the one with the most records here
     * rather than under whoever is written first.
     *
     * Off by default, and a switch of its own, because it is a guess *on top of* the tags rather than
     * what they say. It is worth having because real tags are not written to one rule: a library
     * where `angel vox` is the first name on 47 tracks also holds ten tagged `"CRASPORE; angel vox"`,
     * `"niteboi; angel vox"`, `"Sibewest; angel vox"` — the same collaborations written the other way
     * round, which the first-name rule files under ten artists of one track each. Asking which of the
     * names the library is actually about sends all ten to angel vox: 58 artists become 40, and the
     * ones holding a single track go from 16 to 3.
     *
     * Only ever chooses *between* the names a track already carries, so it does nothing at all
     * without [artistsFromAlbumArtist] — and turning it on turns that on, as that one turns on
     * [useAlbumArtist]. Read while scanning, so changing it re-runs the scan.
     */
    val preferKnownArtist: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    /**
     * Even out the loudness between tracks, from the ReplayGain tags the files carry.
     *
     * Off by default: it is a change to how loud the music is, made without being asked, and on a
     * library with no gain tags at all it would be a switch that does nothing while looking like it
     * should. Whoever wants it knows they do — see [com.metromusic.playback.ReplayGain] for what it
     * can and cannot do (it can only turn quiet a track that is too loud).
     */
    val volumeNormalization: Boolean = false,

    // ---- appearance ----
    /** Take dark/light from the device instead of from [dark]. */
    val followSystemTheme: Boolean = false,
    /** Overrides the theme's own background. Null means black or white, as the theme says. */
    val backgroundArgb: Int? = null,
    /** Show the playing album's cover behind the home panorama. */
    val artworkBackground: Boolean = false,
    /** Show a cover in the now-playing strip. */
    val stripArtwork: Boolean = true,
    /**
     * Draw the accent square behind a long list's group letters.
     *
     * One answer for every list. The albums section used to opt out on the grounds that its rows are
     * already coloured tiles, and the result was two sections that looked like different apps.
     */
    val letterTiles: Boolean = true,
    /**
     * Let a big page title roll away as the list under it is scrolled, and come back when that list is
     * dragged past its top.
     *
     * On by default: on a phone the title is worth a row and a half of library, and it has said what it
     * has to say by the time you are reading the list. A switch because it is also *how you know where
     * you are* — someone who navigates by that word rather than by what is under it wants it to stay.
     */
    val collapseTitle: Boolean = true,
    /**
     * Hide the status bar, so a page runs to the top edge of the screen.
     *
     * Off by default, and it is a mode to be asked for rather than one to find yourself in: the clock
     * and the battery are worth their strip of screen to most people. With it on the bar is gone
     * everywhere — the panorama, the player, a settings page — and a swipe down from the top edge
     * brings it back for a moment, which is the platform's own behaviour for a hidden bar and the way
     * to read the time without leaving what you are looking at.
     *
     * The bar is *hidden* rather than covered, so its inset goes to zero and every page grows into
     * the strip by itself. Nothing lays out differently for this, which is the whole reason it can be
     * one switch in one place.
     */
    val fullScreen: Boolean = false,
    /**
     * What the player shows where the cover goes; see [PlayerFace].
     *
     * **Set from the player itself and from nowhere else.** It had a row on the interface page for a
     * while and it does not belong there: this is a way of looking at what is playing rather than a
     * preference about the app, it changes with the song, and the song is on the player's screen.
     * What it still is, is *remembered* — which is why it lives here rather than in a
     * `remember` that dies with the page.
     *
     * Named rather than boolean for the same reason the sorts are: a third face — a visualiser, the
     * queue — is a plausible thing to want, and a `playerLyrics = true` in everyone's settings file
     * would be an awkward thing to grow into one.
     */
    val playerFaceName: String = PlayerFace.Cover.name,
    /** Home panorama section order; missing names are appended in their declared order. */
    val sectionOrder: List<String> = emptyList(),
    /** How each section is arranged, as an enum name; anything unknown reads as the default. */
    val songSortName: String = SongSort.Name.name,
    val albumSortName: String = AlbumSort.Name.name,
    val artistSortName: String = ArtistSort.Name.name,
    /**
     * The order albums are listed in on one artist's page; see [ArtistAlbumOrder].
     *
     * Read where the page is drawn rather than where the index is built: the albums-per-artist index
     * is the scanner's, and making it answer to a setting would mean re-running a scan to change the
     * order of six rows. The index's own order is the base and the page arranges it.
     */
    val artistAlbumOrderName: String = ArtistAlbumOrder.Year.name,
    /**
     * Fetch a cover from Last.fm for albums that have none in their files.
     *
     * On by default, as lyrics are: the request carries an artist and an album name and nothing about
     * the person, an album is asked about once, and a wall of blank tiles is the thing the setting
     * exists to fix. Failing soft means with it off — or with no key, or no network — a coverless album
     * is a coverless album and nothing else behaves differently.
     */
    val onlineArtwork: Boolean = true,

    // ---- gestures ----
    /**
     * Each gesture the app adds on top of tapping, and a switch for it.
     *
     * They are switchable because a gesture that fires by accident is worse than no gesture: a strip
     * you swipe by mistake while scrolling a list changes the song, and a phone with a case can make
     * an edge swipe hard to avoid. Tapping always works and is not a setting.
     */
    val gestureStripSwipe: Boolean = true,
    val gestureStripUp: Boolean = true,
    val gesturePlayerSwipe: Boolean = true,
    val gesturePlayerDown: Boolean = true,
    /**
     * Pull the player up again to see the queue, and push the queue back down to leave it.
     *
     * The pair is the same movement as the strip's, one page further on: the player comes out of the
     * strip, the queue comes out of the player. Two switches rather than one because they are two
     * gestures on two surfaces, and the one that can fire by accident is the upward one — it shares an
     * axis with the push that puts the player away.
     */
    val gesturePlayerUp: Boolean = true,
    val gestureQueueDown: Boolean = true,
    /**
     * Swipe a queue row aside to take it out of the queue.
     *
     * The one gesture in the app that *destroys* something, in a list that is also scrolled and whose
     * rows are also held and dragged — so it is the last one anybody should be stuck with. On by
     * default all the same: with it off there is no way at all to remove a track, and the row travels
     * with the word under it long before it commits.
     */
    val gestureQueueRemove: Boolean = true,
    /**
     * Drag the left edge of a long list to scroll it by its whole length.
     *
     * It is the one gesture that takes an axis a list already uses, over rows that are also tapped: a
     * band 32dp wide down the side of the artists, albums, songs and genres, where a vertical drag
     * scrubs instead of scrolling. That is exactly the trade a switch exists for — somebody who scrolls
     * with a thumb hooked over the left edge of the phone would otherwise scrub every time they meant
     * to scroll. A tap in the band still plays the row under it, with the gesture on or off.
     */
    val gestureEdgeScroll: Boolean = true,

    // ---- equalizer ----
    val equalizerEnabled: Boolean = false,
    /** Index into the device's own presets, or -1 for the hand-set [equalizerBands]. */
    val equalizerPreset: Int = -1,
    /** Gain per band in millibels, in the device's band order. */
    val equalizerBands: List<Int> = emptyList(),
    /** 0..1000, the strength AudioFx's bass boost takes. */
    val bassBoost: Int = 0,

    // ---- last.fm ----
    val lastfmUser: String? = null,
    val lastfmSessionKey: String? = null,
    val scrobbleEnabled: Boolean = true,
    /**
     * Whether favourites and Last.fm's loved tracks are kept the same, in both directions.
     *
     * Off by default and deliberately not implied by signing in: scrobbling only ever *adds* to a
     * profile, while this can un-love a track on the website because it was removed from favourites
     * here. Someone with years of loves on Last.fm and an empty favourites list has to be the one who
     * asks for the two to be reconciled.
     */
    val syncLoves: Boolean = false,
    /**
     * Last.fm API credentials. The app ships without them — they identify *an app*, not a user, and
     * cannot be checked into a public repo — so they are either baked in through `local.properties`
     * at build time or pasted in here by whoever runs the build.
     */
    val lastfmApiKey: String? = null,
    val lastfmApiSecret: String? = null,

    // ---- lyrics ----
    val lyricsEnabled: Boolean = true,
    /**
     * Which online service [lyricsEnabled] asks — see [LyricsSource].
     *
     * LRCLIB by default, because it is the only one that can answer with timings and a page that
     * follows the music is the better thing to land on by default. Genius is one tap away for anyone
     * whose library is deeper than a community `.lrc` database reaches.
     */
    val lyricsSourceName: String = LyricsSource.LrcLib.name,
    /**
     * Write the words fetched from the online service out as a `.lrc` beside the music.
     *
     * Off by default, because it is the one lyrics setting that *creates files on the device* — and
     * a switch that quietly scatters a few hundred small files through somebody's music folder is
     * not a default. What it buys is that the words stop being this app's: `.lrc` is the format every
     * other player reads, and a file in the music folder survives this app being uninstalled. An
     * answer from LRCLIB is timed, so the file saved from one is a *synced* `.lrc`; Genius has no
     * timings to save.
     *
     * **It goes beside the track — same folder, same name** (`LyricsFiles.writeBesideTheTrack`), and
     * that works on a modern phone rather than needing a folder handed over: MediaProvider files
     * `.lrc` as a *subtitle*, and subtitles are gated on the audio permission along with the media
     * they belong to. [lyricsFolderUri] is the fallback for a device that refuses, and for somebody
     * who keeps their lyrics somewhere else on purpose.
     */
    val lyricsSaveLrc: Boolean = false,
    /**
     * The folder `.lrc` files are read from and written to, as a persisted document-tree uri.
     *
     * Null means "only the sidecar beside the track", which is all that is possible without asking,
     * and which stops working at Android 13: `READ_MEDIA_AUDIO` covers the audio files and not the
     * text file next to them. Pointing this at the music folder is what makes the traditional layout
     * work again on a modern phone.
     */
    val lyricsFolderUri: String? = null,
    /**
     * The folder `.m3u` playlists are kept in, as a persisted document-tree uri.
     *
     * Null is the normal case and means `Music/Playlists`, which an app may create files in without
     * asking for anything — see [com.metromusic.data.playlist.PlaylistFiles]. This is for somebody
     * who keeps their playlists somewhere else on purpose: on a card, in a synced folder, or beside
     * the music rather than in a folder of their own.
     */
    val playlistFolderUri: String? = null
) {
    /** Section order as the enum, filling in anything the saved list doesn't mention. */
    val sections: List<LibrarySection>
        get() {
            val saved = sectionOrder.mapNotNull { name ->
                LibrarySection.entries.firstOrNull { it.name == name }
            }
            return saved + LibrarySection.entries.filterNot { it in saved }
        }

    /**
     * Each arrangement as its enum. An unknown name — a file written by a later build, or one that has
     * been edited by hand — reads as the alphabet rather than throwing the settings away.
     */
    val songSort: SongSort
        get() = SongSort.entries.firstOrNull { it.name == songSortName } ?: SongSort.Name

    val albumSort: AlbumSort
        get() = AlbumSort.entries.firstOrNull { it.name == albumSortName } ?: AlbumSort.Name

    val artistSort: ArtistSort
        get() = ArtistSort.entries.firstOrNull { it.name == artistSortName } ?: ArtistSort.Name

    val artistAlbumOrder: ArtistAlbumOrder
        get() = ArtistAlbumOrder.entries.firstOrNull { it.name == artistAlbumOrderName }
            ?: ArtistAlbumOrder.Year

    /** The player's face; an unknown name reads as the cover rather than throwing settings away. */
    val playerFace: PlayerFace
        get() = PlayerFace.entries.firstOrNull { it.name == playerFaceName } ?: PlayerFace.Cover

    /** The chosen service; an unknown name reads as the default rather than throwing settings away. */
    val lyricsSource: LyricsSource
        get() = LyricsSource.entries.firstOrNull { it.name == lyricsSourceName } ?: LyricsSource.LrcLib

    companion object {
        /** WP8 cyan — the framework's own default accent. */
        const val DefaultAccent: Int = 0xFF1BA1E2.toInt()
    }
}

class SettingsStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "settings.json"),
        serializer = Settings.serializer(),
        defaultValue = Settings(),
        scope = scope
    )

    val settings: StateFlow<Settings> = store.state

    fun setAccent(argb: Int) = store.update { it.copy(accentArgb = argb) }

    fun setDark(dark: Boolean) = store.update { it.copy(dark = dark) }

    fun setFollowSystemTheme(follow: Boolean) = store.update { it.copy(followSystemTheme = follow) }

    fun setBackground(argb: Int?) = store.update { it.copy(backgroundArgb = argb) }

    fun setArtworkBackground(on: Boolean) = store.update { it.copy(artworkBackground = on) }

    fun setStripArtwork(on: Boolean) = store.update { it.copy(stripArtwork = on) }

    fun setLetterTiles(on: Boolean) = store.update { it.copy(letterTiles = on) }

    fun setCollapseTitle(on: Boolean) = store.update { it.copy(collapseTitle = on) }

    fun setFullScreen(on: Boolean) = store.update { it.copy(fullScreen = on) }

    fun setPlayerFace(face: PlayerFace) = store.update { it.copy(playerFaceName = face.name) }

    fun setOnlineArtwork(on: Boolean) = store.update { it.copy(onlineArtwork = on) }

    fun setFixGenreDoubling(on: Boolean) = store.update { it.copy(fixGenreDoubling = on) }

    fun setSplitArtistCredits(on: Boolean) = store.update { it.copy(splitArtistCredits = on) }

    /**
     * Believing the album artist tag is what [Settings.artistsFromAlbumArtist] is built on, so
     * switching it off switches that off too rather than leaving a switch that says "on" and does
     * nothing. Both rows are on the same page, next to each other, so the second toggle is seen
     * rather than merely done.
     */
    fun setUseAlbumArtist(on: Boolean) = store.update {
        it.copy(
            useAlbumArtist = on,
            artistsFromAlbumArtist = it.artistsFromAlbumArtist && on,
            preferKnownArtist = it.preferKnownArtist && on
        )
    }

    /**
     * Turns [Settings.useAlbumArtist] on with it, for the reason on [setUseAlbumArtist] — and takes
     * [Settings.preferKnownArtist] down with it, which chooses between names this one stops handing
     * out.
     */
    fun setArtistsFromAlbumArtist(on: Boolean) = store.update {
        it.copy(
            artistsFromAlbumArtist = on,
            useAlbumArtist = it.useAlbumArtist || on,
            preferKnownArtist = it.preferKnownArtist && on
        )
    }

    /** The whole chain on at once: it chooses between the names the two settings above hand it. */
    fun setPreferKnownArtist(on: Boolean) = store.update {
        it.copy(
            preferKnownArtist = on,
            artistsFromAlbumArtist = it.artistsFromAlbumArtist || on,
            useAlbumArtist = it.useAlbumArtist || on
        )
    }

    fun setFixArtistDoubling(on: Boolean) = store.update { it.copy(fixArtistDoubling = on) }

    fun setVolumeNormalization(on: Boolean) = store.update { it.copy(volumeNormalization = on) }

    fun setGestureStripSwipe(on: Boolean) = store.update { it.copy(gestureStripSwipe = on) }

    fun setGestureStripUp(on: Boolean) = store.update { it.copy(gestureStripUp = on) }

    fun setGesturePlayerSwipe(on: Boolean) = store.update { it.copy(gesturePlayerSwipe = on) }

    fun setGesturePlayerDown(on: Boolean) = store.update { it.copy(gesturePlayerDown = on) }

    fun setGesturePlayerUp(on: Boolean) = store.update { it.copy(gesturePlayerUp = on) }

    fun setGestureQueueDown(on: Boolean) = store.update { it.copy(gestureQueueDown = on) }

    fun setGestureQueueRemove(on: Boolean) = store.update { it.copy(gestureQueueRemove = on) }

    fun setGestureEdgeScroll(on: Boolean) = store.update { it.copy(gestureEdgeScroll = on) }

    fun setSongSort(sort: SongSort) = store.update { it.copy(songSortName = sort.name) }

    fun setAlbumSort(sort: AlbumSort) = store.update { it.copy(albumSortName = sort.name) }

    fun setArtistSort(sort: ArtistSort) = store.update { it.copy(artistSortName = sort.name) }

    fun setArtistAlbumOrder(order: ArtistAlbumOrder) =
        store.update { it.copy(artistAlbumOrderName = order.name) }

    fun setSectionOrder(order: List<LibrarySection>) =
        store.update { it.copy(sectionOrder = order.map(LibrarySection::name)) }

    /** Moves one section one place along, which is all the reordering UI needs. */
    fun moveSection(section: LibrarySection, by: Int) = store.update { current ->
        val order = current.sections.toMutableList()
        val from = order.indexOf(section)
        val to = from + by
        if (from < 0 || to !in order.indices) {
            current
        } else {
            order.removeAt(from)
            order.add(to, section)
            current.copy(sectionOrder = order.map(LibrarySection::name))
        }
    }

    fun setMinTrackSeconds(seconds: Int) =
        store.update { it.copy(minTrackSeconds = seconds.coerceIn(0, 600)) }

    fun setSleepTimerMinutes(minutes: Int) =
        store.update { it.copy(sleepTimerMinutes = minutes.coerceAtLeast(0)) }

    fun setEqualizerEnabled(on: Boolean) = store.update { it.copy(equalizerEnabled = on) }

    /** Picking a preset drops the hand-set bands; they are two ways of saying the same thing. */
    fun setEqualizerPreset(preset: Int) =
        store.update { it.copy(equalizerPreset = preset, equalizerBands = emptyList()) }

    fun setEqualizerBands(bands: List<Int>) =
        store.update { it.copy(equalizerPreset = -1, equalizerBands = bands) }

    fun setBassBoost(strength: Int) =
        store.update { it.copy(bassBoost = strength.coerceIn(0, 1000)) }

    fun setLastfmSession(user: String?, sessionKey: String?) =
        store.update { it.copy(lastfmUser = user, lastfmSessionKey = sessionKey) }

    fun setScrobbleEnabled(on: Boolean) = store.update { it.copy(scrobbleEnabled = on) }

    fun setSyncLoves(on: Boolean) = store.update { it.copy(syncLoves = on) }

    fun setLastfmCredentials(key: String?, secret: String?) = store.update {
        it.copy(
            lastfmApiKey = key?.trim()?.takeIf(String::isNotEmpty),
            lastfmApiSecret = secret?.trim()?.takeIf(String::isNotEmpty)
        )
    }

    fun setLyricsEnabled(on: Boolean) = store.update { it.copy(lyricsEnabled = on) }

    fun setLyricsSource(source: LyricsSource) =
        store.update { it.copy(lyricsSourceName = source.name) }

    fun setLyricsSaveLrc(on: Boolean) = store.update { it.copy(lyricsSaveLrc = on) }

    fun setLyricsFolder(uri: String?) =
        store.update { it.copy(lyricsFolderUri = uri?.takeIf(String::isNotBlank)) }

    fun setPlaylistFolder(uri: String?) =
        store.update { it.copy(playlistFolderUri = uri?.takeIf(String::isNotBlank)) }

    suspend fun flush() = store.flush()
}
