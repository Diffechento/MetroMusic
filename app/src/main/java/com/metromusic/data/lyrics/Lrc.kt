package com.metromusic.data.lyrics

import androidx.compose.runtime.Immutable
import com.metromusic.data.model.Track

/**
 * One line of a song's words, and where in the track it is sung.
 *
 * [timeMs] is null for a line that carries no timestamp. That is not an error state: a `.lrc` is
 * allowed to hold plain lines, and everything Genius hands over is plain, so the same type has to
 * express both or every consumer of it would need two paths.
 */
@Immutable
data class LyricLine(val timeMs: Long?, val text: String)

/**
 * Where a set of words came from, which is what the credit at the foot of the page reads.
 *
 * [Tag] is separate from [Lrc] because the page has to be able to say *which file* — "from the tags
 * inside song.flac" and "from song.lrc" are two different things to go and edit.
 */
enum class LyricsOrigin { Lrc, Tag, LrcLib, Genius }

/**
 * A song's words, timed where the file said so.
 *
 * The point of the type is that [synced] is a property of the *file* rather than a setting: a track
 * with an `.lrc` beside it that carries timestamps follows the music, and the same track with a
 * plain text file beside it is a page you scroll. Nothing upstream has to decide which it is.
 */
@Immutable
data class Lyrics(
    val lines: List<LyricLine>,
    val origin: LyricsOrigin,
    /** Which file this came out of, for the page's footer. Null when it came off the network. */
    val sourceName: String? = null
) {
    /** Line starts in order, for the lines that have one: the index into [lines] and its time. */
    private val timed: List<Pair<Int, Long>> =
        lines.mapIndexedNotNull { index, line -> line.timeMs?.let { index to it } }

    val synced: Boolean get() = timed.isNotEmpty()

    val plainText: String get() = lines.joinToString("\n") { it.text }

    /**
     * The index into [lines] of the line being sung at [positionMs], or -1 before the first one.
     *
     * A binary search rather than a scan because it is read on every position tick while the page is
     * open, and a long `.lrc` is a few hundred lines.
     */
    fun lineAt(positionMs: Long): Int {
        if (timed.isEmpty()) return -1
        var low = 0
        var high = timed.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (timed[mid].second <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found < 0) -1 else timed[found].first
    }

    /**
     * Which line the emphasis belongs to at [positionMs], and what the screen needs to move it.
     *
     * **It is not simply the line being sung.** A handover takes time, and a line that only starts
     * growing when its first word arrives is at its full size a third of a second after it — the
     * emphasis spends the whole song chasing the music. The timings say when the next line begins,
     * so the handover is started *early enough to land on it*: [leadMs] before the line's own
     * timestamp, and at that timestamp the line is exactly where it was going.
     *
     * The lead is cut to **half the gap** where there is not that much room, which is what keeps the
     * anticipation honest in a fast song: it can never run more than half a line ahead of the voice,
     * and two lines 300ms apart hand over in 150ms rather than overlapping each other. That clamped
     * value comes back as [LeadingLine.handoverMs] so the growth and the scroll can take exactly as
     * long as the lead they were given — a fixed duration against a clamped lead would finish late
     * again, which is the whole thing being fixed.
     */
    fun leadingAt(positionMs: Long, leadMs: Long): LeadingLine {
        if (timed.isEmpty()) return LeadingLine(-1, leadMs, null)
        val entry = leadingEntryAt(positionMs, leadMs)
        val next = entry + 1
        val nextAt = if (next < timed.size) boundaryOf(next, leadMs) else null
        if (entry < 0) return LeadingLine(-1, leadMs, nextAt)
        return LeadingLine(
            index = timed[entry].first,
            handoverMs = timed[entry].second - boundaryOf(entry, leadMs),
            nextAtMs = nextAt
        )
    }

    /**
     * When the emphasis should move on to [entry] — before its own first word, by the lead.
     *
     * Strictly increasing, which is what makes the search below sound: the clamp to half the gap
     * puts every boundary past the previous line's start.
     */
    private fun boundaryOf(entry: Int, leadMs: Long): Long {
        val start = timed[entry].second
        val previous = if (entry == 0) 0L else timed[entry - 1].second
        return start - minOf(leadMs, (start - previous) / 2)
    }

    /** The last entry whose boundary has been passed, or -1 before the first. */
    private fun leadingEntryAt(positionMs: Long, leadMs: Long): Int {
        var low = 0
        var high = timed.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (boundaryOf(mid, leadMs) <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Where the line at [index] starts, for seeking to a line that was tapped. */
    fun startOf(index: Int): Long? = lines.getOrNull(index)?.timeMs
}

/**
 * Which line is lit at a moment, how long its handover was given, and when the next one begins.
 *
 * The third field is what lets a caller **sleep until exactly the right moment** instead of noticing
 * on its next poll: a quarter of a second of quantisation is a quarter of a second of the emphasis
 * arriving late, and it is free to avoid when the timings are sitting right there.
 */
@Immutable
data class LeadingLine(val index: Int, val handoverMs: Long, val nextAtMs: Long?)

/**
 * Reading and writing `.lrc`.
 *
 * The format is one line of text per line of song, each prefixed with `[mm:ss.xx]`, plus a handful
 * of `[tag:value]` headers. It is thirty years old and every part of it is optional, so the parser
 * is deliberately forgiving: a file with no timestamps at all is plain lyrics, a file with nothing
 * but headers is nothing at all, and anything it cannot make sense of is dropped rather than thrown.
 */
object Lrc {

    /**
     * `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` — and `[mm:ss:xx]`, which taggers do write.
     *
     * Minutes are allowed three digits: a `.lrc` for a DJ set really does reach `[102:14.00]`, and
     * a timestamp is the one thing here that must not be mistaken for a header.
     */
    private val TimeTag = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")

    /**
     * `[ar:...]`, `[offset:-500]`, and the rest of the headers — **named, not guessed at**.
     *
     * This used to be `[a-zA-Z#]{2,12}` followed by a colon, on the reasoning that a header is a short
     * word and a colon. It is, and so is a section marker: Genius writes `[Chorus: Somebody]` when it
     * says who sings a part, and that pattern swallowed the line whole — "Chorus" is six letters and a
     * colon, so the parser filed it as an unknown header and dropped it. `[Verse 1: ...]` survived only
     * by accident, its space and digit failing the character class, which is exactly the kind of luck
     * that hides a bug: the markers that vanished were `[Chorus:]`, `[Intro:]`, `[Outro:]`, `[Hook:]`
     * and `[Bridge:]`, and only when the singer was credited.
     *
     * The format has a closed set of headers, so it is written out. Anything else in brackets is words
     * — which is the safe way round: an unrecognised header shows up as one odd line at the top, while
     * a guess that is too eager silently deletes song text.
     */
    private val MetaTag =
        Regex("""^\[(ti|ar|al|au|lr|by|re|ve|id|offset|length|tool|encoding|#):(.*)]$""", RegexOption.IGNORE_CASE)

    /**
     * Word-level timings, which "enhanced" `.lrc` puts *inside* a line.
     *
     * Dropped rather than honoured. Colouring a word at a time is a different feature from following
     * a song a line at a time, and a file that carries both must not show its own markup.
     */
    private val WordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * Parses [text], or returns null when there is nothing in it worth showing.
     *
     * Null rather than an empty [Lyrics] on purpose: the caller's next move is to try the next
     * source, and "the file is there and blank" has to fall through exactly the way "there is no
     * file" does.
     */
    fun parse(
        text: String,
        origin: LyricsOrigin = LyricsOrigin.Lrc,
        sourceName: String? = null
    ): Lyrics? {
        var offsetMs = 0L
        val lines = mutableListOf<LyricLine>()

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                // A blank line between verses is meaningful in a plain file and meaningless in a
                // timed one, where the gaps are the timestamps. Kept either way; the page draws a
                // blank line as space, which is what it looks like in the file.
                lines += LyricLine(null, "")
                continue
            }

            // Every timestamp at the *start* of the line. There can be several: a chorus that
            // repeats is written once with one tag per repeat, and each of them is a line of song.
            val stamps = mutableListOf<Long>()
            var cursor = 0
            while (true) {
                val match = TimeTag.find(line, cursor)?.takeIf { it.range.first == cursor } ?: break
                stamps += timeOf(match)
                cursor = match.range.last + 1
            }

            val body = WordTag.replace(line.substring(cursor), "").trim()

            if (stamps.isEmpty()) {
                val meta = MetaTag.find(line)
                if (meta != null) {
                    if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                        offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                    }
                    // Every other header — the title, the artist, the tagger's own name — is not
                    // song text and is dropped. The page already knows which track it is showing.
                    continue
                }
                lines += LyricLine(null, body)
            } else {
                stamps.forEach { lines += LyricLine(it, body) }
            }
        }

        // `[offset:+n]` is the original format's "the words come n milliseconds *earlier*", which is
        // why it is subtracted. Implementations genuinely disagree about the sign; offsets are rare
        // and small, and a file that reads this the other way reads wrong by a fraction of a second.
        val shifted =
            if (offsetMs == 0L) lines
            else lines.map { it.copy(timeMs = it.timeMs?.minus(offsetMs)?.coerceAtLeast(0L)) }

        // Sorted, because a repeated chorus is written once with its tags in the order the lines
        // appear in the file rather than in the order they are sung. Only a timed file is sorted:
        // an untimed line has nowhere to be sorted to, and one stray timestamp in a plain file must
        // not throw the rest of it into a new order.
        val ordered =
            if (shifted.none { it.timeMs != null }) shifted
            else shifted.filter { it.timeMs != null }.sortedBy { it.timeMs }

        val trimmed = ordered.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        if (trimmed.none { it.text.isNotBlank() }) return null
        return Lyrics(trimmed, origin, sourceName)
    }

    private fun timeOf(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> 0L
        }
        return (minutes * 60 + seconds) * 1000 + millis
    }

    /**
     * Renders [lyrics] as a `.lrc` file for [track].
     *
     * The headers go in even for words that carry no timings, because they are what makes the file
     * identifiable as this song's when it is sitting in a folder next to two hundred others — and
     * because every other player reads them.
     */
    fun render(track: Track, lyrics: Lyrics): String = buildString {
        appendLine("[ti:" + sanitize(track.title) + "]")
        appendLine("[ar:" + sanitize(track.artist) + "]")
        if (track.album.isNotBlank()) appendLine("[al:" + sanitize(track.album) + "]")
        appendLine("[re:MetroMusic]")
        if (track.durationMs > 0) appendLine("[length:" + clock(track.durationMs) + "]")
        appendLine()
        for (line in lyrics.lines) {
            if (line.timeMs != null) appendLine(stamp(line.timeMs) + line.text) else appendLine(line.text)
        }
    }

    /** `[mm:ss.xx]`, the form every reader understands. */
    private fun stamp(timeMs: Long): String = "[" + clock(timeMs) + "]"

    private fun clock(timeMs: Long): String {
        val total = timeMs.coerceAtLeast(0L)
        val minutes = total / 60_000
        val seconds = (total % 60_000) / 1000
        val hundredths = (total % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }

    /** A header value cannot carry the bracket that would end it. */
    private fun sanitize(value: String): String = value.replace('[', '(').replace(']', ')').trim()
}
