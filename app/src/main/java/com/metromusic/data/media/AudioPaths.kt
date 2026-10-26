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
        val byName: Map<String, Long>,
        /** Lower-cased paths of the store's own rows, without the library folders' files. */
        val inStore: Set<String>
    )

    @Volatile
    private var index: Index? = null

    /**
     * The paths of the tracks read out of the library folders — see [FolderScanner]. The store has no
     * rows for them, and without these a `.lrc` beside a song on a USB drive or a playlist line
     * naming it would have nothing to be matched against.
     */
    @Volatile
    private var folderPaths: Map<Long, String> = emptyMap()

    /** Replaces the library folders' paths. Called once per scan. */
    fun setFolderPaths(paths: Map<Long, String>) {
        folderPaths = paths
        index = null
    }

    /**
     * Whether MediaStore itself has a row for the file at [path] — asked by the folder scan, so a
     * folder the store also indexes does not list every song in it twice. Reads the store afresh
     * after [refresh].
     */
    fun isInStore(path: String): Boolean = normalise(path) in current().inStore

    /** Drops what was read, so the next question reads it again. Called once per scan. */
    fun refresh() {
        index = null
    }

    fun pathOf(id: Long): String? =
        current().byId[id]?.takeIf { it.isNotBlank() }
            // A library folder's track that has no path (a document of some other provider) is
            // not a store row either, and asking the store about its id would only cost a query.
            ?: if (id in folderPaths || (id and FolderBit) != 0L) null else queryPath(id)

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
        val inStore = HashSet<String>()
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
                    inStore += key
                    // `putIfAbsent` for the vaguer keys: where two files answer to one of them the
                    // first row wins rather than the last, so the answer at least does not depend on
                    // the order MediaStore happened to hand out.
                    byTail.putIfAbsent(tailOf(key), id)
                    byName.putIfAbsent(key.substringAfterLast('/'), id)
                }
            }
        }.onFailure { Log.w(Tag, "Cannot read the file paths", it) }
        // After the store's rows and never over them: where a folder and the store both have a file,
        // the store's row is the track (the folder scan leaves such files out in the first place).
        for ((id, path) in folderPaths) {
            byId[id] = path
            val key = normalise(path)
            byPath.putIfAbsent(key, id)
            byTail.putIfAbsent(tailOf(key), id)
            byName.putIfAbsent(key.substringAfterLast('/'), id)
        }
        return Index(byId, byPath, byTail, byName, inStore)
    }

    @Suppress("DEPRECATION")
    private fun queryPath(id: Long): String? = runCatching {
        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val Tag = "AudioPaths"

        /** See [com.metromusic.data.model.folderTrackIdOf]. */
        const val FolderBit = 1L shl 61

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
