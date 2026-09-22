package com.metromusic.data.playlist

/**
 * One line of a playlist: what it points at, and what the file claims that is.
 *
 * [target] is kept exactly as the file spells it — a path, a `file://` uri, a `content://` uri or a
 * URL — because resolving it is somebody else's job and because a line this app cannot resolve has
 * to survive being written back out. [title] and [durationSec] come from the `#EXTINF` above it and
 * are what a row can show when the library has never heard of the file.
 */
data class M3uEntry(
    val target: String,
    val title: String? = null,
    val durationSec: Long? = null
)

/** A parsed playlist file: the name it gives itself, if any, and its lines in order. */
data class M3uDocument(val title: String? = null, val entries: List<M3uEntry> = emptyList())

/**
 * The extended M3U format, which is the only thing about playlists that is standard.
 *
 * There is no specification — the format is whatever Winamp wrote in 1997 and everybody copied — so
 * this is deliberately generous on the way in and conservative on the way out:
 *
 * - **Anything beginning with `#` that is not an `#EXTINF` or a `#PLAYLIST` is ignored**, rather than
 *   treated as an error or as a path. That covers `#EXTM3U`, the comments people leave in
 *   hand-written lists, and the `#EXT-X-*` tags of an HLS manifest that arrived under the same
 *   extension.
 * - **An `#EXTINF` applies to the next line that is not a comment**, and is dropped if the file ends
 *   before one arrives. Two in a row are not an error either; the last one wins, which is what every
 *   other reader does.
 * - **Line endings are whatever the file has.** A playlist written on Windows is the normal case, not
 *   the exception, and a stray `\r` left on the end of a path is a path that matches nothing.
 *
 * Writing is always `#EXTM3U` with one `#EXTINF` per entry and LF endings. The `#EXTINF` is not
 * decoration: it is what lets another player show a row for a file it cannot find, and it is what
 * this app reads back for the same reason.
 */
object M3u {

    /** What a playlist file is called on disk. `.m3u8` is read too — see [PlaylistFiles]. */
    const val Extension = "m3u"

    fun parse(text: String): M3uDocument {
        var title: String? = null
        var pendingTitle: String? = null
        var pendingDuration: Long? = null
        val entries = mutableListOf<M3uEntry>()

        // The BOM is dropped rather than carried into the first line: a `#EXTM3U` behind one is not
        // recognised as a directive, and a *path* behind one matches no file on any device.
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(ExtInf, ignoreCase = true) -> {
                        val info = line.substring(ExtInf.length)
                        // "#EXTINF:<seconds>[ key="value" …],<title>" — the attributes are an HLS
                        // extension and are skipped with the duration, since the title is what
                        // follows the *first* comma either way.
                        val comma = info.indexOf(',')
                        val head = if (comma < 0) info else info.substring(0, comma)
                        pendingDuration = head.trim().substringBefore(' ')
                            .toLongOrNull()
                            ?.takeIf { it >= 0 }
                        pendingTitle = if (comma < 0) {
                            null
                        } else {
                            info.substring(comma + 1).trim().takeIf { it.isNotEmpty() }
                        }
                    }
                    line.startsWith(PlaylistName, ignoreCase = true) ->
                        title = line.substring(PlaylistName.length).trim().takeIf { it.isNotEmpty() }
                }
                continue
            }
            entries += M3uEntry(line, pendingTitle, pendingDuration)
            pendingTitle = null
            pendingDuration = null
        }
        return M3uDocument(title, entries)
    }

    /**
     * The file's text. [name] is written as `#PLAYLIST` so that a copy of the file carries what it
     * was called, which its own file name no longer says once somebody renames it.
     */
    fun render(entries: List<M3uEntry>, name: String? = null): String = buildString {
        append("#EXTM3U\n")
        if (!name.isNullOrBlank()) append("$PlaylistName${name.trim()}\n")
        for (entry in entries) {
            val duration = entry.durationSec ?: -1
            // A title with a newline in it would end the directive and turn the rest of somebody's
            // song into a path. Tags come out of files, so this is not hypothetical.
            val title = entry.title.orEmpty().replace('\n', ' ').replace('\r', ' ').trim()
            append("$ExtInf$duration,$title\n")
            append(entry.target)
            append('\n')
        }
    }

    private const val ExtInf = "#EXTINF:"
    private const val PlaylistName = "#PLAYLIST:"
}
