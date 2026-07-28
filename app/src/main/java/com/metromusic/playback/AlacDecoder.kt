package com.metromusic.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.beatofthedrum.alacdecoder.AlacFrameDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What playback fails with when a frame cannot be decoded. */
@UnstableApi
class AlacDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/**
 * Apple Lossless, decoded in the app.
 *
 * Android has no ALAC decoder of its own: AOSP ships none, some vendors used to and no longer do,
 * and where there is none media3 reports the track as `NO_UNSUPPORTED_TYPE`, selects nothing, and
 * plays the file as silence with the position running against the clock — which reads as the player
 * being broken rather than as the format being unsupported. The decoding itself is
 * [com.beatofthedrum.alacdecoder]'s (BSD, see the licence beside it); this is the media3 side of it.
 *
 * Output is 16-bit PCM for 16-bit files and 32-bit PCM for 24-bit ones — the sink resamples what the
 * device cannot take directly, and going to 16 bits here would throw away exactly what the format
 * exists to keep. Every ALAC frame stands on its own, so a seek needs no flush.
 */
@UnstableApi
class AlacDecoder(format: Format) : SimpleDecoder<
    DecoderInputBuffer,
    SimpleDecoderOutputBuffer,
    AlacDecoderException
    >(arrayOfNulls(NumBuffers), arrayOfNulls(NumBuffers)) {

    private val alac = try {
        AlacFrameDecoder(format.initializationData.firstOrNull())
    } catch (e: IllegalArgumentException) {
        throw AlacDecoderException("Not a decodable ALAC track", e)
    }

    /** One int per sample, all channels interleaved — how the decoder hands a frame over. */
    private val samples = IntArray(alac.outputBufferInts())
    private var frame = ByteArray(0)

    val channelCount: Int get() = alac.channelCount
    val sampleRate: Int get() = alac.sampleRate

    /** 24-bit files are widened rather than cut down to 16; see the class comment. */
    val pcmEncoding: Int =
        if (alac.bitDepth == 16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_32BIT

    private val bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_16BIT) 2 else 4

    init {
        setInitialInputBufferSize(InitialInputBufferSize)
    }

    override fun getName(): String = "metro-alac"

    override fun createInputBuffer(): DecoderInputBuffer =
        // Heap buffers, not direct ones: the decoder is Java and wants a byte array.
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable): AlacDecoderException =
        AlacDecoderException("Unexpected ALAC decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): AlacDecoderException? {
        val data = inputBuffer.data ?: return null
        val size = data.limit()
        if (frame.size < size) frame = ByteArray(size)
        data.position(0)
        data.get(frame, 0, size)

        val written = try {
            alac.decode(frame, size, samples)
        } catch (e: RuntimeException) {
            // A truncated or malformed frame indexes past the decoder's buffers. Failing playback
            // with a reason beats taking the process down.
            return AlacDecoderException("Corrupt ALAC frame", e)
        }
        if (written <= 0) return AlacDecoderException("ALAC frame decoded to nothing")

        val out = outputBuffer.init(inputBuffer.timeUs, written * bytesPerSample)
        out.order(ByteOrder.LITTLE_ENDIAN)
        write(out, written)
        out.flip()
        return null
    }

    private fun write(out: ByteBuffer, count: Int) {
        if (pcmEncoding == C.ENCODING_PCM_16BIT) {
            for (i in 0 until count) out.putShort(samples[i].toShort())
        } else {
            // 24 bits held in the top three bytes of a 32-bit sample, which is what PCM_32BIT means.
            for (i in 0 until count) out.putInt(samples[i] shl 8)
        }
    }

    private companion object {
        const val NumBuffers = 16

        /** A 4096-sample stereo frame of 24-bit audio, before the decoder grows it on demand. */
        const val InitialInputBufferSize = 4096 * 3 * 2
    }
}
