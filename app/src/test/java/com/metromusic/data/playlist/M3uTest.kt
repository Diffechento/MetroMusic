package com.metromusic.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What real `.m3u` files look like, and what this app must make of them.
 *
 * Every case here is something a writer in the wild actually does — there is no specification for
 * the format, so the only honest definition of "correct" is "copes with what is out there". The
 * ones that matter most are the ones that lose data silently: a `\r` left on the end of a path
 * matches no file, and a header that is eaten takes a song with it.
 */
class M3uTest {

    @Test
    fun `reads an extended playlist`() {
        val parsed = M3u.parse(
            """
            #EXTM3U
            #PLAYLIST:Night bus
            #EXTINF:241,Nightbus Quartet - Harbour Signal
            ../Nightbus/01.mp3
            #EXTINF:198,Nightbus Quartet - Last Stop
            ../Nightbus/02.mp3
            """.trimIndent()
        )
        assertEquals("Night bus", parsed.title)
        assertEquals(2, parsed.entries.size)
        assertEquals("../Nightbus/01.mp3", parsed.entries[0].target)
        assertEquals("Nightbus Quartet - Harbour Signal", parsed.entries[0].title)
        assertEquals(241L, parsed.entries[0].durationSec)
    }

    @Test
    fun `reads a bare list of paths`() {
        val parsed = M3u.parse("/Music/a.mp3\n/Music/b.mp3\n")
        assertEquals(listOf("/Music/a.mp3", "/Music/b.mp3"), parsed.entries.map { it.target })
        assertNull(parsed.entries[0].title)
        assertNull(parsed.entries[0].durationSec)
    }

    /** Windows endings are the normal case, not the exception; a path with a `\r` on it is dead. */
    @Test
    fun `strips carriage returns and the byte order mark`() {
        val parsed = M3u.parse("\uFEFF#EXTM3U\r\n#EXTINF:12,A\r\n/Music/a.mp3\r\n")
        assertEquals(1, parsed.entries.size)
        assertEquals("/Music/a.mp3", parsed.entries[0].target)
        assertEquals("A", parsed.entries[0].title)
    }

    @Test
    fun `ignores comments and blank lines but keeps the song after them`() {
        val parsed = M3u.parse("# just a note\n\n#EXT-X-VERSION:3\n/Music/a.mp3\n")
        assertEquals(listOf("/Music/a.mp3"), parsed.entries.map { it.target })
    }

    /** `-1` is what a writer puts when it does not know, and is not a duration of minus a second. */
    @Test
    fun `an unknown duration is no duration`() {
        val parsed = M3u.parse("#EXTINF:-1,A song\n/Music/a.mp3\n")
        assertNull(parsed.entries[0].durationSec)
        assertEquals("A song", parsed.entries[0].title)
    }

    /** The HLS-style attributes some writers put after the duration are not part of the title. */
    @Test
    fun `reads an EXTINF with attributes`() {
        val parsed = M3u.parse("""#EXTINF:12 tvg-id="x",A song""" + "\n/Music/a.mp3\n")
        assertEquals(12L, parsed.entries[0].durationSec)
        assertEquals("A song", parsed.entries[0].title)
    }

    /** A title with a comma in it is common and the comma is not a second field. */
    @Test
    fun `keeps everything after the first comma as the title`() {
        val parsed = M3u.parse("#EXTINF:12,Earth, Wind & Fire - September\n/Music/a.mp3\n")
        assertEquals("Earth, Wind & Fire - September", parsed.entries[0].title)
    }

    @Test
    fun `an EXTINF with nothing after it is dropped rather than inherited`() {
        val parsed = M3u.parse("#EXTINF:12,A song\n")
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun `writes what it reads`() {
        val text = M3u.render(
            listOf(
                M3uEntry("../Nightbus/01.mp3", "Nightbus Quartet - Harbour Signal", 241),
                M3uEntry("/Music/b.mp3", null, null)
            ),
            "Night bus"
        )
        val parsed = M3u.parse(text)
        assertEquals("Night bus", parsed.title)
        assertEquals(
            listOf("../Nightbus/01.mp3", "/Music/b.mp3"),
            parsed.entries.map { it.target }
        )
        assertEquals(241L, parsed.entries[0].durationSec)
        assertNull(parsed.entries[1].durationSec)
        assertNull(parsed.entries[1].title)
    }

    /** A tag is whatever is in the file, and a newline in one would end the directive early. */
    @Test
    fun `a title with a newline in it cannot break the file`() {
        val text = M3u.render(listOf(M3uEntry("/Music/a.mp3", "two\nlines", 5)))
        val parsed = M3u.parse(text)
        assertEquals(1, parsed.entries.size)
        assertEquals("/Music/a.mp3", parsed.entries[0].target)
        assertEquals("two lines", parsed.entries[0].title)
    }

    /** The lines this app cannot resolve are exactly the ones it must not lose. */
    @Test
    fun `carries a foreign line through unchanged`() {
        val original = "#EXTM3U\n#EXTINF:100,Somebody\nD:\\Music\\Somebody\\01.mp3\n"
        val parsed = M3u.parse(original)
        val again = M3u.parse(M3u.render(parsed.entries, "x"))
        assertEquals("D:\\Music\\Somebody\\01.mp3", again.entries[0].target)
    }
}
