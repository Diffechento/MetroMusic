package com.metromusic.playback

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player

/**
 * Everything the UI needs to draw the transport, minus the playback position.
 *
 * Position is deliberately absent: it changes 60 times a second and would invalidate every
 * screen holding this state. It is a separate flow that only ticks while something collects
 * it — see [PlayerController.positionFlow].
 */
@Immutable
data class PlayerState(
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = -1L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
    /**
     * Where in the queue this is, and what lies either side of it.
     *
     * The player's swipe lays the neighbouring tracks out beside the current one and drags all three
     * together, so it needs to *draw* them — a name and a cover, not just whether they exist. The
     * indices are media3's own `previousMediaItemIndex` / `nextMediaItemIndex`, which is the important
     * part: they already account for shuffle and for repeat, so the track shown coming is the track
     * that will actually play, and on the last track of a repeating queue the next index is the first
     * one rather than a number past the end.
     */
    val queueIndex: Int = -1,
    val previousIndex: Int? = null,
    val nextIndex: Int? = null,
    val previous: TrackFace? = null,
    val next: TrackFace? = null
) {
    val hasTrack: Boolean get() = trackId != null

    /** This track as a face, so the three slots of the player's swipe are all the same shape. */
    val face: TrackFace
        get() = TrackFace(
            trackId = trackId ?: -1L,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId
        )

    companion object {
        val Empty = PlayerState()
    }
}

/**
 * As much of a track as the player draws: what a swipe has to be able to show for a neighbour it has
 * not reached yet. Deliberately not the whole [com.metromusic.data.model.Track] — a queue entry's
 * metadata is what media3 holds, and nothing here needs a file path or a duration.
 */
@Immutable
data class TrackFace(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long
)

/**
 * One thing having just been added to the queue, for the banner that says so.
 *
 * [sequence] is why this is not just a string: queueing the same album twice is a thing people do,
 * and a banner keyed on the text alone would not know the second tap had happened — it would sit
 * there from the first one, timing out mid-way through the second.
 */
@Immutable
data class QueueNotice(val label: String, val count: Int, val sequence: Long)
