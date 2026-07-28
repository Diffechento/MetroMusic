package com.metromusic.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * How long a track is, when MediaStore won't say.
 *
 * The store leaves `DURATION` empty for a format it indexes but does not fully understand — ALAC in
 * an `.m4a` on a Galaxy S23, which is the case this exists for. The row is there, the title and the
 * artist are there, and the length is missing, so the list shows nothing where every other row has a
 * number and the album adds up to less than it is.
 *
 * The length is in the container, not in the codec: `mvhd` carries a timescale and a duration, and
 * reading them takes the file's header rather than a decoder. That is also why
 * `MediaMetadataRetriever` is *not* the fallback here — it is the same platform extractor that left
 * the column empty in the first place, so it would answer the same nothing at a much higher price.
 *
 * Only mp4 is handled. It is the one container this has been seen with, and a wrong guess is worse
 * than no number: 0 keeps the app's existing "unknown length" behaviour.
 */
object TrackDuration {

    /** Milliseconds, or 0 when the file cannot be measured — same meaning as MediaStore's own 0. */
    fun measure(context: Context, trackId: Long): Long {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        return try {
            context.contentResolver.openInputStream(uri)?.use { readMp4DurationMs(it) } ?: 0L
        } catch (e: Exception) {
            // A file that has gone, a permission that was revoked, a container we mis-walked.
            Log.w(Tag, "Could not measure $uri", e)
            0L
        }
    }

    private fun readMp4DurationMs(stream: InputStream): Long {
        val input = DataInputStream(BufferedInputStream(stream, 8 * 1024))
        return walk(input, Long.MAX_VALUE)
    }

    /**
     * Walks boxes until a duration turns up, descending into the ones that hold others.
     *
     * `moov` is usually near the front but is allowed to be at the very back, behind the whole of
     * `mdat` — hence skipping rather than assuming, which on a local file is a seek.
     */
    private fun walk(input: DataInputStream, limit: Long): Long {
        var remaining = limit
        while (remaining > HeaderSize) {
            var size = (input.readInt().toLong() and 0xffffffffL)
            val type = ByteArray(4).also { input.readFully(it) }.decodeToString()
            var header = HeaderSize
            if (size == 1L) {
                // 64-bit size, for the boxes that can hold a whole album.
                size = input.readLong()
                header += 8
            } else if (size == 0L) {
                // "To the end of the file", which is only ever the last box.
                size = remaining
            }
            if (size < header) return 0L
            val body = size - header
            remaining -= size

            when (type) {
                "moov", "trak", "mdia" -> {
                    val found = walk(input, body)
                    if (found > 0L) return found
                }

                "mvhd", "mdhd" -> return duration(input, body)

                else -> skip(input, body)
            }
        }
        return 0L
    }

    /** `mvhd` and `mdhd` agree on the shape of what this needs: a timescale and a duration. */
    private fun duration(input: DataInputStream, body: Long): Long {
        if (body < 20) {
            skip(input, body)
            return 0L
        }
        val version = input.readUnsignedByte()
        input.skipBytes(3) // flags
        val timescale: Long
        val duration: Long
        val read: Long
        if (version == 1) {
            input.skipBytes(16) // creation and modification time, 64-bit each
            timescale = input.readInt().toLong() and 0xffffffffL
            duration = input.readLong()
            read = 4 + 16 + 4 + 8
        } else {
            input.skipBytes(8) // creation and modification time
            timescale = input.readInt().toLong() and 0xffffffffL
            duration = input.readInt().toLong() and 0xffffffffL
            read = 4 + 8 + 4 + 4
        }
        skip(input, body - read)
        // A fragmented file writes 0 here and puts the length in its fragments; not our case, and
        // 0 is the right answer rather than a fabricated one.
        if (timescale <= 0L || duration <= 0L) return 0L
        return duration * 1000L / timescale
    }

    private fun skip(input: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                // Not seekable and not readable — treat it as the end of the walk.
                if (input.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private const val HeaderSize = 8L
    private const val Tag = "TrackDuration"
}
