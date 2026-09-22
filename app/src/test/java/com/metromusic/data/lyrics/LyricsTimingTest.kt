package com.metromusic.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anticipation: which line is lit when, and how long it is given to get there.
 *
 * Worth a test rather than a look at a device because the whole thing is arithmetic over a list of
 * timestamps, and the one way it can go wrong — a boundary that is not strictly increasing — makes
 * the binary search behind it return the wrong line for a moment, which on screen is a flicker
 * somebody would have to catch in the act. Here it is a number.
 */
class LyricsTimingTest {

    private fun lyricsOf(vararg timesMs: Long) = Lyrics(
        lines = timesMs.mapIndexed { index, time -> LyricLine(time, "line $index") },
        origin = LyricsOrigin.Lrc
    )

    @Test
    fun `a handover starts a full lead before the line where there is room`() {
        val lyrics = lyricsOf(0L, 10_000L)
        // 560ms before the second line and not a millisecond sooner.
        assertEquals(0, lyrics.leadingAt(9_439L, 560L).index)
        assertEquals(1, lyrics.leadingAt(9_440L, 560L).index)
        assertEquals(560L, lyrics.leadingAt(9_440L, 560L).handoverMs)
    }

    @Test
    fun `a handover is cut to half the gap when the lines are close together`() {
        val lyrics = lyricsOf(0L, 300L)
        val leading = lyrics.leadingAt(150L, 560L)
        // Half of 300ms, so the emphasis can never be more than half a line ahead of the voice.
        assertEquals(1, leading.index)
        assertEquals(150L, leading.handoverMs)
        assertEquals(0, lyrics.leadingAt(149L, 560L).index)
    }

    @Test
    fun `boundaries are strictly increasing, which is what the search rests on`() {
        val lyrics = lyricsOf(0L, 300L, 700L, 5_000L, 5_100L, 20_000L)
        var previous = Long.MIN_VALUE
        var at = 0L
        var seen = 0
        while (true) {
            val leading = lyrics.leadingAt(at, 560L)
            val next = leading.nextAtMs ?: break
            assertTrue("boundary went backwards at $at", next > previous)
            previous = next
            at = next
            seen++
        }
        assertEquals(5, seen)
    }

    @Test
    fun `walking the boundaries lights every line in order, exactly once`() {
        val lyrics = lyricsOf(0L, 300L, 700L, 5_000L, 5_100L, 20_000L)
        val lit = mutableListOf<Int>()
        var at = lyrics.leadingAt(0L, 560L).let { lit += it.index; it.nextAtMs }
        while (at != null) {
            val leading = lyrics.leadingAt(at, 560L)
            lit += leading.index
            at = leading.nextAtMs
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), lit)
    }

    @Test
    fun `nothing is lit before the first line, and its handover is still known`() {
        val lyrics = lyricsOf(4_000L, 8_000L)
        val leading = lyrics.leadingAt(0L, 560L)
        assertEquals(-1, leading.index)
        assertEquals(4_000L - 560L, leading.nextAtMs)
    }

    @Test
    fun `the last line has nowhere to go next`() {
        val lyrics = lyricsOf(0L, 1_000L)
        assertNull(lyrics.leadingAt(2_000L, 560L).nextAtMs)
    }

    @Test
    fun `a set of words with no timings lights nothing at all`() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(null, "a"), LyricLine(null, "b")),
            origin = LyricsOrigin.Genius
        )
        val leading = lyrics.leadingAt(1_000L, 560L)
        assertEquals(-1, leading.index)
        assertNull(leading.nextAtMs)
    }

    @Test
    fun `a repeated chorus lights its own run and not the first`() {
        // The index that comes back is the index into `lines`, and a chorus written twice is two
        // lines — which is the property the page's binary search has always had and must keep.
        val lyrics = Lrc.parse("[00:10.00][00:40.00]hold the line\n[00:20.00]and the verse")!!
        assertEquals(0, lyrics.leadingAt(11_000L, 560L).index)
        assertEquals(1, lyrics.leadingAt(21_000L, 560L).index)
        assertEquals(2, lyrics.leadingAt(41_000L, 560L).index)
    }
}
