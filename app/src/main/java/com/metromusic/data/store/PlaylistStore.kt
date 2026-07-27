package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * A user playlist. Only track ids are stored — the library stays the single source of truth,
 * so a track that was deleted from the device simply stops resolving instead of leaving a
 * stale copy of its metadata behind.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<Long> = emptyList(),
    val createdAt: Long = 0L
)

@Serializable
data class PlaylistData(val items: List<Playlist> = emptyList())

class PlaylistStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "playlists.json"),
        serializer = PlaylistData.serializer(),
        defaultValue = PlaylistData(),
        scope = scope
    )

    val playlists: StateFlow<PlaylistData> = store.state

    fun create(name: String, trackIds: List<Long> = emptyList(), now: Long): String {
        val id = UUID.randomUUID().toString()
        store.update { data ->
            data.copy(items = data.items + Playlist(id, name, trackIds, now))
        }
        return id
    }

    fun rename(id: String, name: String) = mutate(id) { it.copy(name = name) }

    fun delete(id: String) {
        store.update { data -> data.copy(items = data.items.filterNot { it.id == id }) }
    }

    /** Appends, skipping tracks already in the playlist so double-taps can't duplicate them. */
    fun add(id: String, trackIds: List<Long>) = mutate(id) { playlist ->
        val existing = playlist.trackIds.toSet()
        playlist.copy(trackIds = playlist.trackIds + trackIds.filterNot { it in existing })
    }

    fun removeAt(id: String, index: Int) = mutate(id) { playlist ->
        if (index !in playlist.trackIds.indices) playlist
        else playlist.copy(trackIds = playlist.trackIds.toMutableList().apply { removeAt(index) })
    }

    fun move(id: String, from: Int, to: Int) = mutate(id) { playlist ->
        val ids = playlist.trackIds
        if (from !in ids.indices || to !in ids.indices || from == to) return@mutate playlist
        playlist.copy(trackIds = ids.toMutableList().apply { add(to, removeAt(from)) })
    }

    private inline fun mutate(id: String, crossinline transform: (Playlist) -> Playlist) {
        store.update { data ->
            data.copy(items = data.items.map { if (it.id == id) transform(it) else it })
        }
    }

    suspend fun flush() = store.flush()
}
