package com.metromusic.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * What year a track is from, when MediaStore won't say.
 *
 * The store fills `YEAR` from a fixed list of tag names, and the list is missing the one the
 * standard actually uses in two of the commonest formats. Measured on an API 33 emulator, one file
 * per row:
 *
 * | file | tag | `YEAR` |
 * |---|---|---|
 * | FLAC | `DATE=1997` | NULL |
 * | FLAC | `DATE=1999-05-12` | NULL |
 * | FLAC | `YEAR=1998` | 1998 |
 * | Ogg Vorbis, Opus | `DATE=2001` | NULL |
 * | MP3, ID3v2.4 | `TDRC 2002` | NULL |
 * | MP3, ID3v2.3 | `TYER 2000` | 2000 |
 * | M4A | `©day 2004` | 2004 |
 *
 * `DATE` is *the* Vorbis comment for this, and `TDRC` is what ID3v2.4 replaced `TYER` with, so
 * every tagger that follows its format — foobar2000, Picard, Mp3tag on FLAC, every rip — produces
 * files whose year the store never reads. Sorting an artist's albums by year then files the whole
 * library under "year unknown", which is how it was reported.
 *
 * Only the tag header is read, never the audio, and only for the rows the store left at 0 in the
 * formats above, so a library the store understands pays nothing. The same reasoning as
 * [TrackDuration]: `MediaMetadataRetriever` is the platform extractor that left the column empty,
 * and it has no year to give for these files either.
 *
 * **Opening a file is the cost, not reading it**: 10–60ms a file through the content resolver on the
 * emulator, which is half a minute on every launch for a thousand FLACs. So the answers are kept in
 * `track-years.txt`, keyed by the row and the file's modification time and size — a retag changes
 * both, and an unchanged file is never opened twice — and the files that do have to be opened are
 * opened several at a time. A file that says nothing is remembered as 0 too, or a library with no
 * dates in it would pay the whole price on every scan for ever.
 */
object TrackYear {

    /** One row the store left without a year. */
    data class Request(val id: Long, val mime: String, val modified: Long, val size: Long)

    /** Whether [read] can say anything about a file of this type. */
    fun handles(mime: String?): Boolean = mime != null && reader(mime) != null

    /**
     * The year of every request that has one, by track id. Rows whose file says nothing are absent.
     *
     * The cache is rewritten with exactly the rows asked about, so a track that has gone from the
     * library takes its line with it.
     */
    suspend fun resolve(context: Context, requests: List<Request>): Map<Long, Int> =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CacheFile)
            val cached = readCache(file)
            val misses = requests.filter { cached[it.id]?.matches(it) != true }
            val gate = Semaphore(Parallel)
            val fresh = coroutineScope {
                misses.map { request ->
                    async { gate.withPermit { request.id to read(context, request.id, request.mime) } }
                }.awaitAll()
            }.toMap()
            val answers = requests.associate { it.id to (fresh[it.id] ?: cached.getValue(it.id).year) }
            if (misses.isNotEmpty() || cached.size != requests.size) {
                writeCache(file, requests, answers)
            }
            answers.filterValues { it > 0 }
        }

    private class Cached(val modified: Long, val size: Long, val year: Int) {
        fun matches(request: Request) = modified == request.modified && size == request.size
    }

    private fun readCache(file: File): Map<Long, Cached> = runCatching {
        if (!file.exists()) return emptyMap()
        buildMap {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@forEachLine
                val id = parts[0].toLongOrNull() ?: return@forEachLine
                val modified = parts[1].toLongOrNull() ?: return@forEachLine
                val size = parts[2].toLongOrNull() ?: return@forEachLine
                val year = parts[3].toIntOrNull() ?: return@forEachLine
                put(id, Cached(modified, size, year))
            }
        }
    }.getOrElse {
        Log.w(Tag, "Cannot read $file", it)
        emptyMap()
    }

    private fun writeCache(file: File, requests: List<Request>, answers: Map<Long, Int>) {
        runCatching {
            val temp = File(file.parentFile, "$CacheFile.tmp")
            temp.bufferedWriter().use { out ->
                for (r in requests) {
                    out.append("${r.id}\t${r.modified}\t${r.size}\t${answers[r.id] ?: 0}\n")
                }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Log.w(Tag, "Cannot write $file", it) }
    }

    /** The year, or 0 when the file does not say — the same meaning as MediaStore's own 0. */
    private fun read(context: Context, trackId: Long, mime: String): Int {
        val read = reader(mime) ?: return 0
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                read(BufferedInputStream(stream, 8 * 1024))
            } ?: 0
        } catch (e: Exception) {
            // A file that has gone, a permission that was revoked, a tag we mis-walked. A wrong
            // year is worse than none, so nothing half-read is ever returned.
            Log.w(Tag, "Could not read the year of $uri", e)
            0
        }
    }

    private fun reader(mime: String): ((InputStream) -> Int)? = when (mime.lowercase()) {
        "audio/flac", "audio/x-flac" -> ::flacYear
        "audio/ogg", "application/ogg", "audio/vorbis", "audio/opus", "audio/x-ogg" -> ::oggYear
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> ::id3Year
        else -> null
    }

    // FLAC: "fLaC", then metadata blocks, one of which is a Vorbis comment block.

    private fun flacYear(input: InputStream): Int {
        // An ID3 tag glued on the front is not in the format and some taggers do it anyway.
        val magic = input.readBytes(4)
        if (magic.decodeToString(0, 3) == "ID3") return 0
        if (magic.decodeToString() != "fLaC") return 0
        while (true) {
            val header = input.readBytes(4)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7f
            val length = ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            if (type == FlacVorbisComment) return vorbisCommentYear(input)
            // A PICTURE block can be megabytes; on a local file this is a seek.
            input.skipFully(length.toLong())
            if (last) return 0
        }
    }

    // Ogg: pages around packets. The first page holds the identification header alone (both
    // specifications require it), and the comment header is the packet that starts the second.

    private fun oggYear(input: InputStream): Int {
        val payload = OggPayload(input)
        payload.skipFirstPage()
        val first = payload.readBytes(7)
        when {
            first.contentEquals(VorbisCommentMagic) -> Unit
            first.decodeToString() == "OpusTag" && payload.readBytes(1)[0] == 's'.code.toByte() -> Unit
            else -> return 0
        }
        return vorbisCommentYear(payload)
    }

    /**
     * The body of a Vorbis comment block, which FLAC and Ogg share: a vendor string, a count, and
     * that many `KEY=value` strings, every length a little-endian u32.
     *
     * Values are never read whole. A cover in an Ogg file is a base64 comment of its own and can run
     * to megabytes, so only the key of each comment is looked at and the rest of anything
     * uninteresting is skipped.
     */
    private fun vorbisCommentYear(input: InputStream): Int {
        input.skipFully(input.readLe32())
        val count = input.readLe32()
        var best = 0
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val length = input.readLe32()
            val head = input.readBytes(minOf(length, CommentHead.toLong()).toInt())
            input.skipFully(length - head.size)
            val text = head.decodeToString()
            val eq = text.indexOf('=')
            if (eq <= 0) continue
            val rank = VorbisYearKeys.indexOf(text.substring(0, eq).uppercase())
            if (rank < 0 || rank >= bestRank) continue
            val year = yearIn(text.substring(eq + 1))
            if (year > 0) {
                best = year
                bestRank = rank
            }
        }
        return best
    }

    // ID3v2 at the front of an MP3. Only 2.3 and 2.4 — 2.2 is twenty-five years gone.

    private fun id3Year(input: InputStream): Int {
        val header = input.readBytes(10)
        if (header.decodeToString(0, 3) != "ID3") return 0
        val version = header[3].toInt()
        if (version != 3 && version != 4) return 0
        val flags = header[5].toInt()
        // Whole-tag unsynchronisation rewrites the bytes of every frame; not worth a decoder for a
        // year, and a guess out of rewritten bytes would be a wrong year.
        if (flags and 0x80 != 0) return 0
        var remaining = syncsafe(header, 6).toLong()
        if (flags and 0x40 != 0) {
            // Extended header: 2.4 counts itself in its size (syncsafe), 2.3 does not.
            val ext = input.readBytes(4)
            val size = if (version == 4) syncsafe(ext, 0).toLong() - 4 else be32(ext, 0)
            input.skipFully(size)
            remaining -= 4 + size
        }
        var best = 0
        var bestRank = Int.MAX_VALUE
        while (remaining >= 10) {
            val frame = input.readBytes(10)
            remaining -= 10
            if (frame[0].toInt() == 0) break // padding
            val id = frame.decodeToString(0, 4)
            val size = if (version == 4) syncsafe(frame, 4).toLong() else be32(frame, 4)
            if (size < 0 || size > remaining) break
            remaining -= size
            val rank = Id3YearFrames.indexOf(id)
            // Compressed, encrypted or unsynchronised frames are skipped, not decoded.
            val formatFlags = frame[9].toInt() and if (version == 4) 0x0f else 0xc0
            if (rank < 0 || rank >= bestRank || formatFlags != 0 || size > FrameLimit) {
                input.skipFully(size)
                continue
            }
            val body = input.readBytes(size.toInt())
            val year = if (body.isEmpty()) 0 else yearIn(id3Text(body))
            if (year > 0) {
                best = year
                bestRank = rank
            }
        }
        return best
    }

    private fun id3Text(body: ByteArray): String {
        val charset = when (body[0].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, 1, body.size - 1, charset)
    }

    /**
     * The year in a date as taggers write it — `1997`, `1997-05-12`, `1997/05`, `12.05.1997`,
     * `1997-05-12T00:00:00Z` — which is the first run of exactly four digits. A run of more digits
     * is not a year, and neither is one that could not be a release date.
     */
    internal fun yearIn(text: String): Int {
        val year = YearPattern.find(text)?.value?.toIntOrNull() ?: return 0
        return if (year in 1000..2999) year else 0
    }

    /** The concatenated payload of the first logical stream's pages, with the page headers removed. */
    private class OggPayload(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var serial: Int? = null

        fun skipFirstPage() {
            nextPage()
            skipFully(left)
        }

        private fun nextPage() {
            while (true) {
                val header = input.readBytes(27)
                if (header.decodeToString(0, 4) != "OggS") throw EOFException("Not an Ogg page")
                val pageSerial = le32(header, 14)
                val segments = header[26].toInt() and 0xff
                val table = input.readBytes(segments)
                val length = table.sumOf { it.toLong() and 0xff }
                if (serial == null) serial = pageSerial
                if (pageSerial == serial) {
                    left = length
                    return
                }
                // A page of another logical stream interleaved with ours.
                input.skipFully(length)
            }
        }

        override fun read(): Int {
            while (left == 0L) nextPage()
            val b = input.read()
            if (b < 0) return -1
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (left == 0L) nextPage()
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            while (left == 0L) nextPage()
            val skipped = input.skip(minOf(n, left))
            if (skipped > 0) left -= skipped
            return skipped
        }
    }

    private fun InputStream.readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = read(out, got, count - got)
            if (n < 0) throw EOFException()
            got += n
        }
        return out
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun InputStream.readLe32(): Long = le32(readBytes(4), 0).toLong() and 0xffffffffL

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private const val FlacVorbisComment = 4

    private val VorbisCommentMagic = byteArrayOf(3) + "vorbis".encodeToByteArray()

    /** Most wanted first: the release's own date, then the original release's. */
    private val VorbisYearKeys = listOf("DATE", "YEAR", "ORIGINALDATE", "ORIGINALYEAR")
    private val Id3YearFrames = listOf("TDRC", "TYER", "TDOR", "TORY")

    /** Enough of a comment to hold any key and a date after it; the rest is skipped unread. */
    private const val CommentHead = 128

    /** A date frame is a few bytes; one this large is not a date. */
    private const val FrameLimit = 256L

    private val YearPattern = Regex("(?<!\\d)\\d{4}(?!\\d)")

    private const val CacheFile = "track-years.txt"

    /** Files opened at once on a first scan; the cost is waiting on storage, not CPU. */
    private const val Parallel = 8

    private const val Tag = "TrackYear"
}
