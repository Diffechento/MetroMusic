package com.metromusic.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The path arithmetic, which is where a playlist quietly becomes a list of rows that resolve to
 * nothing. Both directions are checked against each other: whatever [Targets.toLine] writes for a
 * track has to come back as that track's own path through [Targets.toPath].
 */
class TargetsTest {

    private val playlists = "/storage/emulated/0/Music/Playlists"

    @Test
    fun `writes a relative line for a track under the same tree`() {
        assertEquals(
            "../Nightbus/01.mp3",
            Targets.toLine("/storage/emulated/0/Music/Nightbus/01.mp3", playlists)
        )
    }

    @Test
    fun `and reads it back to where it started`() {
        val path = "/storage/emulated/0/Music/Nightbus/01.mp3"
        assertEquals(path, Targets.toPath(Targets.toLine(path, playlists), playlists))
    }

    /** Past a few levels up, a chain of dots is less use to a human than the path itself. */
    @Test
    fun `writes an absolute line for a track far away`() {
        val far = "/storage/1234-5678/a/b/c/d/e/01.mp3"
        assertEquals(far, Targets.toLine(far, playlists))
    }

    @Test
    fun `reads an absolute line`() {
        assertEquals("/Music/a.mp3", Targets.toPath("/Music/a.mp3", playlists))
    }

    @Test
    fun `reads a relative line against the playlist's own folder`() {
        assertEquals(
            "/storage/emulated/0/Music/Nightbus/01.mp3",
            Targets.toPath("Nightbus/01.mp3", "/storage/emulated/0/Music")
        )
    }

    /** A relative line with nothing to be relative to is unresolvable, not a guess. */
    @Test
    fun `a relative line with no folder resolves to nothing`() {
        assertNull(Targets.toPath("Nightbus/01.mp3", null))
    }

    @Test
    fun `reads a file uri, escapes and all`() {
        assertEquals(
            "/storage/emulated/0/Music/Harbour Lamps/01 Signal.mp3",
            Targets.toPath("file:///storage/emulated/0/Music/Harbour%20Lamps/01%20Signal.mp3", null)
        )
    }

    /** A `+` in a file name is a `+`, which is why this is not `URLDecoder`. */
    @Test
    fun `a plus in a file uri is a plus`() {
        assertEquals("/Music/a+b.mp3", Targets.toPath("file:///Music/a+b.mp3", null))
    }

    @Test
    fun `reads cyrillic out of a file uri`() {
        assertEquals(
            "/Music/Комарово.mp3",
            Targets.toPath("file:///Music/%D0%9A%D0%BE%D0%BC%D0%B0%D1%80%D0%BE%D0%B2%D0%BE.mp3", null)
        )
    }

    @Test
    fun `a stream is not a file`() {
        assertNull(Targets.toPath("https://example.com/stream.mp3", playlists))
        assertNull(Targets.toPath("content://media/external/audio/media/42", playlists))
    }

    @Test
    fun `finds the media id in a content uri`() {
        assertEquals(42L, Targets.toMediaId("content://media/external/audio/media/42"))
        assertNull(Targets.toMediaId("content://media/external/images/media/42"))
        assertNull(Targets.toMediaId("/Music/a.mp3"))
    }

    /** A playlist made on a computer: the drive is meaningless, the tail is not. */
    @Test
    fun `reads a windows path as a path`() {
        assertEquals("D:/Music/Nightbus/01.mp3", Targets.toPath("D:\\Music\\Nightbus\\01.mp3", null))
    }

    @Test
    fun `resolves dots inside a line`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            Targets.toPath("/storage/emulated/0/Music/Playlists/.././a.mp3", null)
        )
    }
}
