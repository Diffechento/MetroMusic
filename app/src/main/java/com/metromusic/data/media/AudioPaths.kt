package com.metromusic.data.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Where every track in the media database actually lives on disk, both ways round.
 *
 * `DATA` is deprecated and is still the only column that answers "which file is this" — which is the
 * question a `.lrc` sidecar and an `.m3u` line are both an answer to. It is deliberately *not* carried
 * on [com.metromusic.data.model.Track]: a scan holds thousands of those and only these two features
 * need the path. So it is read for the whole volume in **one** cursor and kept here, because the
 * alternative — a query per song — is exactly the N+1 the scanner exists to avoid, and a playlist of
 * two hundred lines would pay it two hundred times.
 *
 * Built lazily on the first question and thrown away by [refresh] when the library is rescanned, so
 * the two callers can both invalidate it without coordinating.
 *
 * Everything here blocks on a cursor; call it from IO.
 */
class AudioPaths(private val context: Context) {

    private class Index(
        val byId: Map<Long, String>,
        /** Lower-cased full path: the filesystem is case-sensitive, a foreign playlist is not. */
        val byPath: Map<String, Long>,
        /** Lower-cased "album-folder/file.mp3" — what survives a move to another device. */
        val byTail: Map<String, Long>,
        /** Lower-cased file name alone, the last resort. */
        val byName: Map<String, Long>
    )

    @Volatile
    private var index: Index? = null

    /** Drops what was read, so the next question reads it again. Called once per scan. */
    fun refresh() {
        index = null
    }

    fun pathOf(id: Long): String? =
        current().byId[id]?.takeIf { it.isNotBlank() } ?: queryPath(id)

    /**
     * The track a playlist line points at, matched from the most specific reading to the least.
     *
     * The three steps are the three ways one library ends up written down twice. An exact path is a
     * playlist this device made. The **tail** — the album folder and the file name — is a playlist
     * made on a computer against the same collection, where everything above the album folder is
     * somebody else's drive letter. The file name alone is the end of the line: it is ambiguous
     * across albums (every rip has a `01.mp3`), so it is only ever reached when the two readings
     * above have already failed, and it is what makes a hand-written list work at all.
     */
    fun idOf(path: String): Long? {
        val cleaned = normalise(path)
        if (cleaned.isEmpty()) return null
        val index = current()
        index.byPath[cleaned]?.let { return it }
        index.byTail[tailOf(cleaned)]?.let { return it }
        return index.byName[cleaned.substringAfterLast('/')]
    }

    private fun current(): Index = index ?: read().also { index = it }

    @Suppress("DEPRECATION")
    private fun read(): Index {
        val byId = mutableMapOf<Long, String>()
        val byPath = mutableMapOf<String, Long>()
        val byTail = mutableMapOf<String, Long>()
        val byName = mutableMapOf<String, Long>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val path = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    byId[id] = path
                    val key = normalise(path)
                    byPath[key] = id
                    // `putIfAbsent` for the vaguer keys: where two files answer to one of them the
                    // first row wins rather than the last, so the answer at least does not depend on
                    // the order MediaStore happened to hand out.
                    byTail.putIfAbsent(tailOf(key), id)
                    byName.putIfAbsent(key.substringAfterLast('/'), id)
                }
            }
        }.onFailure { Log.w(Tag, "Cannot read the file paths", it) }
        return Index(byId, byPath, byTail, byName)
    }

    @Suppress("DEPRECATION")
    private fun queryPath(id: Long): String? = runCatching {
        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val Tag = "AudioPaths"

        /** A path as a key: one separator, no trailing slash, case folded. */
        fun normalise(path: String): String =
            path.replace('\\', '/').trimEnd('/').lowercase()

        fun tailOf(path: String): String {
            val file = path.substringAfterLast('/')
            val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
            return if (folder.isEmpty()) file else "$folder/$file"
        }
    }
}
