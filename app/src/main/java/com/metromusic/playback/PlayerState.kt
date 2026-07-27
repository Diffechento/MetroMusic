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
    val queueSize: Int = 0
) {
    val hasTrack: Boolean get() = trackId != null

    companion object {
        val Empty = PlayerState()
    }
}

/**
 * One thing having just been added to the queue, for the banner that says so.
 *
 * [sequence] is why this is not just a string: queueing the same album twice is a thing people do,
 * and a banner keyed on the text alone would not know the second tap had happened — it would sit
 * there from the first one, timing out mid-way through the second.
 */
@Immutable
data class QueueNotice(val label: String, val count: Int, val sequence: Long)
