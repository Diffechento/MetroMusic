package com.metromusic.playback

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The music stream's volume, and whether the app is currently showing it.
 *
 * Android gives no way to suppress its own volume panel, so the only way to have a WP8-looking
 * volume indicator is to take the key events before the system acts on them: an activity that
 * consumes `KEYCODE_VOLUME_UP`/`DOWN` moves the stream itself and no system panel appears. That is
 * what [nudge] is for. The limit is honest and worth knowing — inside the app you get this, and from
 * the lock screen or another app you get Android's, because those events never reach us.
 *
 * The level is read from the system every time rather than cached: the notification shade, a
 * headset's own buttons and Bluetooth all move it behind our back.
 */
class VolumeController(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    data class VolumeState(
        val step: Int = 0,
        val steps: Int = 15,
        val showing: Boolean = false
    ) {
        /** 0..1, for a bar. */
        val level: Float get() = if (steps <= 0) 0f else step.toFloat() / steps

        val muted: Boolean get() = step == 0
    }

    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state.asStateFlow()

    /** Moves the stream one step and shows the banner. [direction] is +1 or -1. */
    fun nudge(direction: Int) {
        val manager = audio ?: return
        manager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            // No system UI: this app is drawing it.
            0
        )
        publish(showing = true)
    }

    /** Sets the stream from a 0..1 fraction — for dragging the bar in the banner. */
    fun setLevel(level: Float) {
        val manager = audio ?: return
        val steps = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (level.coerceIn(0f, 1f) * steps).toInt(),
            0
        )
        publish(showing = true)
    }

    fun hide() = _state.update { it.copy(showing = false) }

    private fun publish(showing: Boolean) {
        val manager = audio ?: return
        _state.value = VolumeState(
            step = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
            steps = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
            showing = showing
        )
    }
}
