package com.metromusic.playback

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import com.metromusic.data.store.Settings
import com.metromusic.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The equalizer and bass boost, and the audio session they hang off.
 *
 * The session id is generated **here**, in the app process, and handed to the player in
 * [PlaybackService] — not read back off the player afterwards. An ExoPlayer's own session id is
 * unset until it has prepared something, so a settings screen opened before anything played would
 * have nothing to attach to and no way to tell you what the bands are. Owning the id instead means
 * the effects exist from the moment the app starts and the UI can ask them about themselves.
 *
 * Every call into `android.media.audiofx` is guarded. These effects are optional platform features:
 * they are missing entirely on some devices, throw on others when a call is routed to a Bluetooth
 * codec that does not support them, and can fail on the ninth band of ten. Treat all of that as
 * "no equalizer" rather than as a crash — [available] says which it is.
 */
class AudioEffects(
    context: Context,
    scope: CoroutineScope,
    private val settings: SettingsStore
) {
    /** What the player must use as its audio session for any of this to be heard. */
    val sessionId: Int = context.getSystemService(AudioManager::class.java)
        ?.generateAudioSessionId()
        ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** The device's bands and presets, or null when it has no equalizer to speak of. */
    val capabilities: Capabilities?

    val available: Boolean get() = capabilities != null

    data class Capabilities(
        /** Centre frequency of each band, in Hz. */
        val bandFrequencies: List<Int>,
        /** Gain range in millibels, as the platform reports it. */
        val minLevel: Int,
        val maxLevel: Int,
        val presets: List<String>
    )

    init {
        capabilities = runCatching {
            val eq = Equalizer(EffectPriority, sessionId)
            equalizer = eq
            val bands = (0 until eq.numberOfBands).map { band ->
                // Reported in millihertz; nobody thinks in those.
                eq.getCenterFreq(band.toShort()) / 1000
            }
            val range = eq.bandLevelRange
            Capabilities(
                bandFrequencies = bands,
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            )
        }.onFailure { Log.w(Tag, "No equalizer on this device", it) }.getOrNull()

        bassBoost = runCatching { BassBoost(EffectPriority, sessionId) }
            .onFailure { Log.w(Tag, "No bass boost on this device", it) }
            .getOrNull()

        // Settings are the single source of truth; the effects just follow them.
        scope.launch {
            settings.settings.collect { apply(it) }
        }
    }

    /**
     * The gains actually in force, in the device's band order — what a slider should start from.
     *
     * Falls back to the stored values, then to flat, because a preset sets the hardware without
     * telling settings what it did: after "Rock", the sliders should show Rock's curve.
     */
    fun currentBands(): List<Int> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
        }.getOrDefault(settings.settings.value.equalizerBands)
    }

    private fun apply(values: Settings) {
        val eq = equalizer
        if (eq != null) {
            runCatching {
                eq.enabled = values.equalizerEnabled
                if (values.equalizerEnabled) {
                    val presets = capabilities?.presets?.indices
                    if (values.equalizerPreset >= 0 && presets?.contains(values.equalizerPreset) == true) {
                        eq.usePreset(values.equalizerPreset.toShort())
                    } else if (values.equalizerBands.isNotEmpty()) {
                        values.equalizerBands.forEachIndexed { band, level ->
                            if (band < eq.numberOfBands) {
                                eq.setBandLevel(band.toShort(), level.toShort())
                            }
                        }
                    }
                }
            }.onFailure { Log.w(Tag, "Could not apply equalizer settings", it) }
        }

        val boost = bassBoost
        if (boost != null) {
            runCatching {
                val strength = values.bassBoost.coerceIn(0, 1000)
                boost.enabled = values.equalizerEnabled && strength > 0
                if (boost.enabled && boost.strengthSupported) {
                    boost.setStrength(strength.toShort())
                }
            }.onFailure { Log.w(Tag, "Could not apply bass boost", it) }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }

    private companion object {
        const val Tag = "AudioEffects"

        /**
         * Zero: the value the platform documents for an application acting on its own audio. A
         * higher priority does not make an effect louder, it makes it win a fight with other apps,
         * which is not this app's business.
         */
        const val EffectPriority = 0
    }
}
