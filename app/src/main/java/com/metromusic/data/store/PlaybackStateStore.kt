package com.metromusic.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The queue as it stood when the app last had it, so closing the app is not the same as stopping.
 *
 * Only **track ids** are kept, in queue order. The tracks themselves come from MediaStore and are
 * never persisted anywhere in this app, so the restore goes through [com.metromusic.data.model.Library.resolve]
 * like a playlist does — which also means a track that has since been deleted, hidden or moved off
 * the card simply isn't in the restored queue instead of failing to load when it is reached.
 *
 * [index] is a position in [trackIds] rather than a track id because a queue may hold the same
 * track twice; the id at that index is what the restore actually matches on, so a queue that lost a
 * few tracks still comes back on the right one.
 *
 * Two things are deliberately *not* stored. Whether it was playing — restoring the queue puts the
 * strip back with the track you left on it, while resuming playback is something nobody asked the
 * app to do by launching it. And, as a rule, the position within the track: coming back to the
 * middle of a song is not what anyone wants from it, so a restored track starts at its beginning.
 *
 * The exception is [positionMs], for tracks long enough that starting over *is* the loss — a
 * podcast, an audiobook. It is written only for a current track at least
 * [com.metromusic.data.store.Settings.resumePositionMinutes] long, and 0 otherwise.
 */
@Serializable
data class SavedQueue(
    val trackIds: List<Long> = emptyList(),
    val index: Int = 0,
    val shuffle: Boolean = false,
    /**
     * The order [trackIds] was in before it was shuffled, so the toggle is still reversible after a
     * relaunch. Null whenever there is nothing to go back to — see
     * [com.metromusic.playback.PlayerController], where shuffling means rearranging the queue rather
     * than reading it out of order.
     */
    val unshuffledIds: List<Long>? = null,
    val repeatMode: Int = 0,
    /**
     * Where in the track at [index] playback was, in milliseconds. 0 means "from the top", which is
     * what every track shorter than the resume threshold gets — see the class comment.
     */
    val positionMs: Long = 0L
) {
    val isEmpty: Boolean get() = trackIds.isEmpty()

    companion object {
        val Empty = SavedQueue()
    }
}

class PlaybackStateStore(context: Context, scope: CoroutineScope) {

    private val store = JsonStore(
        file = File(context.filesDir, "playback.json"),
        serializer = SavedQueue.serializer(),
        defaultValue = SavedQueue.Empty,
        scope = scope,
        // Longer than the other stores': this one is rewritten on every player event, and a skip
        // or a pause is not worth its own trip to disk.
        debounceMs = 2_000
    )

    val saved: StateFlow<SavedQueue> = store.state

    /** The stored queue once the file has been read — see [JsonStore.awaitLoaded]. */
    suspend fun awaitLoaded(): SavedQueue = store.awaitLoaded()

    fun save(queue: SavedQueue) = store.update { queue }

    fun clear() = store.update { SavedQueue.Empty }

    suspend fun flush() = store.flush()
}
