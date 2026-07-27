package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The artists and albums the user has told the app to forget about.
 *
 * Kept by **name**, not by MediaStore id. Ids are handed out again after the media database is
 * rebuilt — a card remount, a factory reset of the media store, an OS update — and a hidden album
 * would either come back or, worse, take some other album's place with it. A name survives all of
 * that, and the one thing it costs is that two different albums with the same title by the same
 * artist hide together, which is a trade worth making.
 *
 * Entries are stored as the user saw them ("Daft Punk", "Daft Punk|Discovery") so the settings page
 * can list them without a lookup; matching ignores case and surrounding space.
 */
@Serializable
data class Hidden(
    val artists: Set<String> = emptySet(),
    val albums: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty()

    val size: Int get() = artists.size + albums.size

    companion object {
        /** How an album is named in [albums]: its artist, then its title. */
        fun albumKey(artist: String, album: String): String = "${artist.trim()}|${album.trim()}"

        /** Splits [albumKey] back into artist and title, for display. */
        fun readAlbumKey(key: String): Pair<String, String> {
            val separator = key.indexOf('|')
            return if (separator < 0) {
                "" to key
            } else {
                key.substring(0, separator) to key.substring(separator + 1)
            }
        }
    }
}

class HiddenStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "hidden.json"),
        serializer = Hidden.serializer(),
        defaultValue = Hidden(),
        scope = scope
    )

    val hidden: StateFlow<Hidden> = store.state

    fun hideArtist(name: String) = store.update { it.copy(artists = it.artists + name.trim()) }

    fun showArtist(name: String) = store.update {
        it.copy(artists = it.artists.filterNot { entry -> entry.equals(name, true) }.toSet())
    }

    fun hideAlbum(artist: String, title: String) = store.update {
        it.copy(albums = it.albums + Hidden.albumKey(artist, title))
    }

    fun showAlbum(key: String) = store.update {
        it.copy(albums = it.albums.filterNot { entry -> entry.equals(key, true) }.toSet())
    }

    fun showEverything() = store.update { Hidden() }

    suspend fun flush() = store.flush()
}
