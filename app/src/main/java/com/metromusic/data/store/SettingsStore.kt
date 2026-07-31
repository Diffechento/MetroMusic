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
 * How the songs section is arranged — picked by holding a group header, and remembered.
 *
 * Remembered because it is a way of *reading* the library rather than a one-off filter: someone who
 * sorts by play count is asking the app what they listen to, and being handed the alphabet again on
 * the next launch answers a question they did not ask. The search box is the opposite case and is
 * deliberately dropped when you leave.
 *
 * Persisted by name, for the reason [LibrarySection] is: adding an arrangement must not silently
 * change what an older settings file meant.
 */
enum class SongSort {
    Name, DateAdded, Duration, PlayCount
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
     * File a track under every artist its credit names, rather than under the credit as one string.
     *
     * On by default: "Artist", "Artist feat. Guest" and "Artist, Other" in the tags is one performer
     * and three sections otherwise, and the section you were looking for is whichever of them you did
     * not tap. A switch, because the separators it splits on are also punctuation inside band names —
     * "Earth, Wind & Fire" becomes two artists — and the only way to know that has happened to *your*
     * library is to be able to turn it off. See `splitArtists`.
     */
    val splitArtistCredits: Boolean = true,
    val sleepTimerMinutes: Int = 0,

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
    /** Home panorama section order; missing names are appended in their declared order. */
    val sectionOrder: List<String> = emptyList(),
    /** How the songs section is arranged, as a [SongSort] name; anything unknown reads as the default. */
    val songSortName: String = SongSort.Name.name,
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
    val lyricsEnabled: Boolean = true
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
     * The songs arrangement as the enum. An unknown name — a file written by a later build, or one
     * that has been edited by hand — reads as the alphabet rather than throwing the settings away.
     */
    val songSort: SongSort
        get() = SongSort.entries.firstOrNull { it.name == songSortName } ?: SongSort.Name

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

    fun setOnlineArtwork(on: Boolean) = store.update { it.copy(onlineArtwork = on) }

    fun setFixGenreDoubling(on: Boolean) = store.update { it.copy(fixGenreDoubling = on) }

    fun setSplitArtistCredits(on: Boolean) = store.update { it.copy(splitArtistCredits = on) }

    fun setGestureStripSwipe(on: Boolean) = store.update { it.copy(gestureStripSwipe = on) }

    fun setGestureStripUp(on: Boolean) = store.update { it.copy(gestureStripUp = on) }

    fun setGesturePlayerSwipe(on: Boolean) = store.update { it.copy(gesturePlayerSwipe = on) }

    fun setGesturePlayerDown(on: Boolean) = store.update { it.copy(gesturePlayerDown = on) }

    fun setSongSort(sort: SongSort) = store.update { it.copy(songSortName = sort.name) }

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

    suspend fun flush() = store.flush()
}
