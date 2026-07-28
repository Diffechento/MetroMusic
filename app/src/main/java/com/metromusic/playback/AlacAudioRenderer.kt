package com.metromusic.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import com.beatofthedrum.alacdecoder.AlacFrameDecoder

/**
 * The renderer that puts [AlacDecoder] in the player's pipeline.
 *
 * Added *after* the platform's own audio renderer, the way media3's official extensions are: a
 * device that does have an ALAC decoder keeps using it — it is hardware-backed and someone else's
 * battery problem — and this one is what answers when nothing else will. The track selector picks
 * per track, comparing what each renderer says it supports.
 *
 * It shares the player's [AudioSink], so the equalizer's audio session, the volume, audio focus and
 * the notification's transport all behave exactly as they do for everything else.
 */
@UnstableApi
class AlacAudioRenderer(
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink
) : DecoderAudioRenderer<AlacDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "AlacAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (!MimeTypes.AUDIO_ALAC.equals(format.sampleMimeType, ignoreCase = true)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val config = format.initializationData.firstOrNull()
        if (config == null || config.size < AlacFrameDecoder.ConfigSize) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        val bitDepth = config[BitDepthOffset].toInt() and 0xff
        val encoding = when (bitDepth) {
            16 -> C.ENCODING_PCM_16BIT
            24 -> C.ENCODING_PCM_32BIT
            // 20 and 32 bits are in the format and not in the decoder.
            else -> return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        val channelCount = config[ChannelCountOffset].toInt() and 0xff
        if (channelCount !in 1..2) return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (!sinkSupportsFormat(Util.getPcmFormat(encoding, channelCount, format.sampleRate))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacDecoder =
        AlacDecoder(format)

    override fun getOutputFormat(decoder: AlacDecoder): Format =
        Util.getPcmFormat(decoder.pcmEncoding, decoder.channelCount, decoder.sampleRate)

    private companion object {
        /** Offsets into ALACSpecificConfig; the whole layout is in [AlacFrameDecoder]. */
        const val BitDepthOffset = 5
        const val ChannelCountOffset = 9
    }
}
