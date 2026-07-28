package com.metromusic.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The player's renderers, plus the ones the platform doesn't have.
 *
 * Only one addition so far: [AlacAudioRenderer], appended after the platform audio renderer so a
 * device with a real ALAC decoder keeps using it and everything else stops playing silence. Any
 * other format Android indexes but cannot decode would join it here.
 */
@UnstableApi
class MetroRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        // Same sink as the platform renderer's: the audio session the equalizer is attached to, the
        // volume and audio focus are all the sink's business, and only one renderer is ever enabled.
        out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
