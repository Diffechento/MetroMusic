package com.metromusic.ui

/**
 * The icon set, such as it is: plain characters rendered in the Metro typeface, the same way
 * the framework's own tiles and app-bar buttons work. No vector drawables, nothing to keep
 * in sync with a theme.
 *
 * Symbols that Unicode also defines an emoji presentation for get U+FE0E appended. Without
 * it Android hands them to the color emoji font, and a glossy red heart is not the look.
 */
object Glyphs {
    const val Play = "▶"
    const val Pause = "❚❚"
    const val Previous = "⏮︎"
    const val Next = "⏭︎"
    const val Shuffle = "⇄"
    const val Repeat = "↻"

    /**
     * Repeat-one. The player used to spell the mode out in the button's caption; without captions
     * the glyph has to carry it, and the superscript one is the WP8 badge idea in a single
     * character — U+1F502 would be the literal icon but arrives from the color emoji font.
     */
    const val RepeatOne = "↻¹"
    const val Heart = "♥︎"
    const val Note = "♪"

    const val Add = "+"
    const val Grip = "≡"

    /** Ticking every row of a list, and clearing the lot. */
    const val SelectAll = "✓"
    const val SelectNone = "✕"

    /** Bringing a file in from elsewhere on the device, and sending one out. */
    const val Import = "↓"
    const val Export = "↑"
}
