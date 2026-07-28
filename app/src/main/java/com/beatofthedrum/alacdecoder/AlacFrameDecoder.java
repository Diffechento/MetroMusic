/*
 * Part of MetroMusic, not of the vendored decoder — see LICENSE.txt for the files around it.
 *
 * It lives in this package because the decoder's own state class (AlacFile) and its setinfo_*
 * fields are package-private, and this is the one thing the app needs that the public API does not
 * offer: configuration from a bare ALACSpecificConfig. The upstream alac_set_info() reads the
 * QuickTime magic cookie *with* its atom wrappers (size/frma/alac/size/alac/0), while media3's mp4
 * extractor hands over the 24-byte config on its own. Same 24 fields, 24 bytes earlier.
 *
 * Everything else here is glue: one frame in, interleaved samples out.
 */
package com.beatofthedrum.alacdecoder;

public final class AlacFrameDecoder {

    /** The largest frame the vendored decoder's fixed buffers can hold. */
    private static final int MaxFrameLength = 16384;

    /** Bytes of ALACSpecificConfig, as stored in the mp4 `alac` atom after its full header. */
    public static final int ConfigSize = 24;

    /** Slack after the last byte of a frame, for the bit reader's three-byte look-ahead. */
    private static final int Padding = 8;

    /** Slack for the entries the decoder writes past the end of a mono frame — see {@link #decode}. */
    private static final int Slack = 8;

    private final AlacFile alac;
    private final int[] raw;
    private byte[] scratch;

    public final int frameLength;
    public final int bitDepth;
    public final int channelCount;
    public final int sampleRate;

    public AlacFrameDecoder(byte[] config) throws IllegalArgumentException {
        if (config == null || config.length < ConfigSize) {
            throw new IllegalArgumentException("ALAC config is " +
                    (config == null ? "missing" : config.length + " bytes, need " + ConfigSize));
        }
        frameLength = readInt(config, 0);
        bitDepth = config[5] & 0xff;
        channelCount = config[9] & 0xff;
        sampleRate = readInt(config, 20);

        if (frameLength <= 0 || frameLength > MaxFrameLength) {
            throw new IllegalArgumentException("ALAC frame length " + frameLength + " out of range");
        }
        if (channelCount < 1 || channelCount > 2) {
            // The vendored decoder handles mono and stereo elements only.
            throw new IllegalArgumentException("ALAC channel count " + channelCount);
        }
        if (bitDepth != 16 && bitDepth != 24) {
            // 20 and 32 are in the format but not in the decoder, and silence is not an answer.
            throw new IllegalArgumentException("ALAC bit depth " + bitDepth);
        }

        alac = AlacDecodeUtils.create_alac(bitDepth, channelCount);
        alac.setinfo_max_samples_per_frame = frameLength;
        alac.setinfo_7a = config[4] & 0xff;              // compatibleVersion
        alac.setinfo_sample_size = bitDepth;
        alac.setinfo_rice_historymult = config[6] & 0xff; // pb
        alac.setinfo_rice_initialhistory = config[7] & 0xff; // mb
        alac.setinfo_rice_kmodifier = config[8] & 0xff;  // kb
        alac.setinfo_7f = channelCount;
        alac.setinfo_80 = ((config[10] & 0xff) << 8) | (config[11] & 0xff); // maxRun
        alac.setinfo_82 = readInt(config, 12);           // maxFrameBytes
        alac.setinfo_86 = readInt(config, 16);           // avgBitRate
        alac.setinfo_8a_rate = sampleRate;

        // One int per sample at 16 bits, three at 24 — plus the mono overrun.
        int perSample = bitDepth == 16 ? 1 : 3;
        raw = new int[frameLength * channelCount * perSample + Slack];
    }

    /** Ints needed by {@link #decode}: one per sample, all channels interleaved. */
    public int outputBufferInts() {
        return frameLength * channelCount;
    }

    /**
     * Decodes one frame into [out] as interleaved samples, one signed value per int, and returns how
     * many were written. A frame's sample count is not fixed — the last one of a file is short.
     *
     * The two bit depths do not agree on what the decoder's own output array holds, which is why
     * nothing outside this class is allowed to see it: at 16 bits it is one signed sample per int,
     * at 24 it is one *byte* per int, little-endian, three to a sample. Both also write a few
     * entries past the last sample of a mono frame — the decoder covers the case of a mono file
     * whose header claims two channels by filling in a silent second channel — so the array it is
     * handed is its own, with slack, and never the caller's.
     */
    public int decode(byte[] frame, int frameSize, int[] out) {
        // Copied into a padded buffer rather than read in place: the bit reader takes three bytes at
        // a time and steps past the last one, and the buffer is reused because this runs per frame.
        if (scratch == null || scratch.length < frameSize + Padding) {
            scratch = new byte[frameSize + Padding];
        }
        System.arraycopy(frame, 0, scratch, 0, frameSize);

        int bytes = AlacDecodeUtils.decode_frame(alac, scratch, raw, 0);
        int samples = bytes / (bitDepth / 8);
        if (samples <= 0 || samples > out.length) return 0;

        if (bitDepth == 16) {
            System.arraycopy(raw, 0, out, 0, samples);
        } else {
            for (int i = 0, at = 0; i < samples; i++, at += 3) {
                int value = (raw[at] & 0xff) | ((raw[at + 1] & 0xff) << 8) | ((raw[at + 2] & 0xff) << 16);
                // Written out as three unsigned bytes, so the sign has to be put back.
                out[i] = (value << 8) >> 8;
            }
        }
        return samples;
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 24)
                | ((b[offset + 1] & 0xff) << 16)
                | ((b[offset + 2] & 0xff) << 8)
                | (b[offset + 3] & 0xff);
    }
}
