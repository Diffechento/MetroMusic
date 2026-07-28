package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Listening history and favorites — what feeds the panorama's "history" section and the
 * heart button.
 *
 * [recent] is most-recent-first and capped, so it can't grow without bound over years of use.
 */
@Serializable
data class Stats(
    val favorites: Set<Long> = emptySet(),
    val playCounts: Map<Long, Int> = emptyMap(),
    val recentTrackIds: List<Long> = emptyList(),
    val recentAlbumIds: List<Long> = emptyList()
)

class StatsStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "stats.json"),
        serializer = Stats.serializer(),
        defaultValue = Stats(),
        scope = scope
    )

    val stats: StateFlow<Stats> = store.state

    fun isFavorite(trackId: Long): Boolean = trackId in store.state.value.favorites

    fun toggleFavorite(trackId: Long) = store.update { stats ->
        val favorites = stats.favorites
        stats.copy(
            favorites = if (trackId in favorites) favorites - trackId else favorites + trackId
        )
    }

    /**
     * Sets a favourite to a known value, rather than flipping whatever is there.
     *
     * For the Last.fm loves sync, which knows what the answer should be: a toggle applied to a state
     * that has changed since it was read does the opposite of what was intended, and the two ends of a
     * sync would then chase each other.
     */
    fun setFavorite(trackId: Long, favorite: Boolean) = store.update { stats ->
        val favorites = stats.favorites
        if (favorite == trackId in favorites) {
            stats
        } else {
            stats.copy(
                favorites = if (favorite) favorites + trackId else favorites - trackId
            )
        }
    }

    /** Called when a track actually starts playing, not when it is merely queued. */
    fun recordPlay(trackId: Long, albumId: Long) = store.update { stats ->
        stats.copy(
            playCounts = stats.playCounts + (trackId to (stats.playCounts[trackId] ?: 0) + 1),
            recentTrackIds = (listOf(trackId) + stats.recentTrackIds.filterNot { it == trackId })
                .take(RecentLimit),
            recentAlbumIds = (listOf(albumId) + stats.recentAlbumIds.filterNot { it == albumId })
                .take(RecentLimit)
        )
    }

    fun clearHistory() = store.update {
        it.copy(recentTrackIds = emptyList(), recentAlbumIds = emptyList(), playCounts = emptyMap())
    }

    suspend fun flush() = store.flush()

    private companion object {
        const val RecentLimit = 50
    }
}
