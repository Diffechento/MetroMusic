package com.metromusic.data.playlist

/**
 * The arithmetic between a line of a playlist file and a file on the device.
 *
 * A `.m3u` line is not a path — it is *whatever the program that wrote it felt like putting there*:
 * an absolute path, a path relative to the playlist, a `file://` uri from a program that thought in
 * uris, a `content://` uri from an Android app that had no path to give, or an http URL for a
 * stream. All five turn up in real files, so all five are read; only the first two are ever written.
 *
 * Kept apart from [PlaylistFiles], and deliberately free of `android.net.Uri` and of anything else
 * from the platform: this is the half of the feature that can be got wrong silently — a rule that
 * is one `..` out turns a playlist into a list of rows that resolve to nothing — and being plain
 * Kotlin is what lets it be tested on a machine instead of on a phone.
 */
internal object Targets {

    /** How far up a tree a relative line may climb before an absolute path is simply clearer. */
    private const val MaxClimb = 3

    /**
     * The absolute path a line points at, or null where it does not point at a file at all.
     *
     * [directory] is the playlist's own folder, which is what a relative line is relative to. A line
     * that is relative and has no folder to be relative to is unresolvable rather than guessed at.
     */
    fun toPath(target: String, directory: String?): String? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("file:", ignoreCase = true) -> {
                // "file:///a/b", "file://localhost/a/b" and the plain "file:/a/b" some writers emit.
                val rest = trimmed.substring("file:".length)
                    .trimStart('/')
                    .removePrefix("localhost/")
                normalise(decodePercent("/" + rest)).takeIf { it.length > 1 }
            }

            // A stream, not a file. Nothing here can play one, and pretending otherwise would put a
            // row in a list that never resolves to anything.
            trimmed.contains("://") -> null

            trimmed.startsWith('/') -> normalise(trimmed)

            // A bare Windows path ("D:\Music\…") from a playlist made on a computer. The drive is
            // meaningless here, but the tail of it is exactly what the file-name match is for.
            trimmed.length > 2 && trimmed[1] == ':' -> normalise(trimmed.replace('\\', '/'))

            directory != null -> normalise(directory.trimEnd('/') + "/" + trimmed.replace('\\', '/'))

            else -> null
        }
    }

    /** The MediaStore id a `content://…/audio/media/<id>` line names, if it is one. */
    fun toMediaId(target: String): Long? {
        val trimmed = target.trim()
        if (!trimmed.startsWith("content://", ignoreCase = true)) return null
        if (!trimmed.contains("/audio/", ignoreCase = true)) return null
        return trimmed.substringAfterLast('/').toLongOrNull()
    }

    /**
     * How a track's path is written into a playlist sitting in [directory].
     *
     * Relative where the two share a tree, absolute otherwise. This is the one decision in the
     * format that decides whether the file still means anything somewhere else: a playlist in
     * `Music/Playlists` holding `../Nightbus/01.mp3` survives the whole `Music` folder being copied
     * to a computer or another phone, while `/storage/emulated/0/…` is true of exactly one device.
     * It stops at [MaxClimb] levels up, past which a chain of `..` is less legible than the path.
     */
    fun toLine(path: String, directory: String?): String {
        if (directory.isNullOrEmpty()) return path
        val from = normalise(directory).trimEnd('/').split('/')
        val to = normalise(path).split('/')
        var shared = 0
        while (shared < from.size && shared < to.size && from[shared].equals(to[shared], true)) {
            shared++
        }
        // Nothing above the root in common (different volumes, say) — there is no relative path.
        if (shared <= 1 || shared == to.size) return path
        val climb = from.size - shared
        if (climb > MaxClimb) return path
        return buildList {
            repeat(climb) { add("..") }
            addAll(to.subList(shared, to.size))
        }.joinToString("/")
    }

    /** Resolves `.` and `..` and collapses repeated separators, without touching the disk. */
    private fun normalise(path: String): String {
        val absolute = path.startsWith('/')
        val parts = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") {
                    parts.removeAt(parts.lastIndex)
                } else if (!absolute) {
                    parts += segment
                }

                else -> parts += segment
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/" + joined else joined
    }

    /**
     * `%20` and friends, because a `file://` line is a uri and a path is not.
     *
     * Hand-rolled rather than `URLDecoder`, which is for form encoding and turns a `+` into a space
     * — and a `+` in a file name is a `+`. Anything that is not a well-formed escape is left as it
     * stands, since a lone `%` in a song title is likelier than a truncated escape.
     */
    private fun decodePercent(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val hex = if (char == '%' && index + 2 < value.length) {
                value.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                bytes.write(hex)
                index += 3
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
