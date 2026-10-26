package com.metromusic.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import com.metromusic.data.model.folderTrackIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The music in the **library folders**: a USB drive on an OTG cable, or any folder the media database
 * does not index. Asked for in issue #9, against foobar2000, and built the way foobar2000 does it.
 *
 * Android does not index every drive. A USB stick is mounted as a volume the media database may never
 * look at, so a player that only reads MediaStore — which this one did — cannot see it, while the
 * system's own file picker can. So the user names folders, and they are read here, by one of two
 * routes, chosen per folder on every scan:
 *
 * - **By path**, through `java.io.File`, wherever the app may read the volume directly: with All Files
 *   Access (Android 11+), or with the plain storage permission below Android 10. Fast — a listing is a
 *   syscall — and a track read this way has a real path, so a `.lrc` beside it, a `cover.jpg` beside
 *   it, tag editing and playlist lines all work exactly as they do for any other track.
 * - **Through the Storage Access Framework**, a document tree the user picked in the system's folder
 *   picker. Needs no special permission and reaches every drive the picker shows, which is why
 *   foobar2000 had to bring its picker back after dropping it: some phones allow no other way onto an
 *   SD card at all. Slower, since every folder is a query to another process.
 *
 * A folder is saved as a tree uri (picked) or a path (a drive added in one tap with All Files Access),
 * and a tree on the system's storage provider is read by path whenever the path is readable, so
 * granting All Files Access later speeds up the folders already there rather than asking for them
 * again. Track ids come from the path either way — see [folderTrackIdOf] — so nothing about a track
 * changes when the route does.
 *
 * Files MediaStore already has are left out: picking `Music` on a phone that indexes it must not list
 * every song twice. That is decided by path, before any tags are read.
 *
 * **Tags are read once per file** with [MediaMetadataRetriever] and kept in `folder-tags.json`, keyed
 * by id, size and modification time. A thousand files cost a minute or so the first time and a
 * directory walk afterwards; a drive unplugged and plugged back in is not read again.
 */
class FolderScanner(private val context: Context) {

    enum class State {
        /** Read this time. */
        Ready,

        /** The drive is not there — unplugged, unmounted, or not readable right now. */
        Unavailable,

        /** The folder was saved, but the permission to read it has gone. Pick it again. */
        NoAccess
    }

    /** How one library folder fared, for the settings page to show. */
    data class Folder(
        val entry: String,
        val label: String,
        val state: State,
        /** Music files found there, the ones MediaStore also has included. */
        val songs: Int = 0,
        /** Read by path rather than through the document provider. */
        val direct: Boolean = false
    )

    internal class Result(
        val tracks: List<RawTrack>,
        /** The path of every track that has one, for [AudioPaths] — lyrics sidecars, playlist lines. */
        val paths: Map<Long, String>,
        val folders: List<Folder>
    )

    /** A file found by a walk, before its tags are read. */
    private class Found(
        val id: Long,
        val uri: Uri,
        val path: String?,
        val name: String,
        val modified: Long,
        val size: Long,
        val cover: Uri?,
        val folderName: String
    )

    @Serializable
    private data class Cached(
        val id: Long,
        val modified: Long,
        val size: Long,
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0,
        val trackNo: Int = 0,
        val year: Int = 0,
        val genre: String? = null
    )

    private val resolver get() = context.contentResolver
    private val cacheFile get() = File(context.filesDir, CacheFile)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param inStore whether MediaStore already has the file at this path, in which case it is left to
     *   the store and not listed twice.
     */
    internal suspend fun scan(entries: List<String>, inStore: (String) -> Boolean): Result =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext Result(emptyList(), emptyMap(), emptyList())
            val direct = canReadByPath(context)
            val found = LinkedHashMap<Long, Found>()
            val folders = entries.map { entry ->
                val listed = ArrayList<Found>()
                val folder = runCatching { walk(entry, direct, listed) }
                    .getOrElse {
                        Log.w(Tag, "Cannot read $entry", it)
                        Folder(entry, labelOf(context, entry), State.Unavailable)
                    }
                // One file reachable through two folders (a drive and its Music folder) is one track.
                listed.forEach { found.putIfAbsent(it.id, it) }
                if (folder.state == State.Ready) folder.copy(songs = listed.size) else folder
            }
            val fresh = found.values.filter { it.path == null || !inStore(it.path) }
            val tags = readTags(fresh)
            Result(
                tracks = fresh.map { file ->
                    val t = tags.getValue(file.id)
                    RawTrack(
                        id = file.id,
                        title = t.title ?: file.name.substringBeforeLast('.'),
                        artist = t.artist,
                        albumArtist = t.albumArtist,
                        album = t.album,
                        mediaAlbumId = 0L,
                        durationMs = t.durationMs,
                        trackNo = t.trackNo,
                        year = t.year,
                        dateAdded = file.modified / 1000,
                        genre = t.genre,
                        source = file.uri,
                        folderCover = file.cover,
                        folderName = file.folderName
                    )
                },
                paths = fresh.mapNotNull { f -> f.path?.let { f.id to it } }.toMap(),
                folders = folders
            )
        }

    // ---- walking ----

    private fun walk(entry: String, direct: Boolean, out: MutableList<Found>): Folder {
        val label = labelOf(context, entry)
        if (entry.startsWith("/")) {
            // A path is only ever saved while it can be read that way, so failing to now means the
            // drive is gone or All Files Access was taken back — and only the second has a remedy.
            val root = File(entry)
            if (!direct) return Folder(entry, label, State.NoAccess)
            if (!root.isDirectory || root.listFiles() == null) {
                return Folder(entry, label, State.Unavailable)
            }
            walkFiles(root, out)
            return Folder(entry, label, State.Ready, direct = true)
        }

        val tree = Uri.parse(entry)
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return Folder(entry, label, State.Unavailable)
        val path = pathOfDocument(tree.authority, treeId)
        if (direct && path != null) {
            val root = File(path)
            if (root.isDirectory && root.listFiles() != null) {
                walkFiles(root, out)
                return Folder(entry, label, State.Ready, direct = true)
            }
        }
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!granted) return Folder(entry, label, State.NoAccess)
        return if (walkTree(tree, treeId, out)) {
            Folder(entry, label, State.Ready)
        } else {
            Folder(entry, label, State.Unavailable)
        }
    }

    private fun walkFiles(root: File, out: MutableList<Found>) {
        val pending = ArrayDeque<File>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val children = dir.listFiles() ?: continue
            val cover = coverAmong(children.filter { it.isFile }.map { it.name })
                ?.let { name -> Uri.fromFile(File(dir, name)) }
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    pending += child
                } else if (isAudio(child.name, null)) {
                    out += Found(
                        id = folderTrackIdOf(child.path),
                        uri = Uri.fromFile(child),
                        path = child.path,
                        name = child.name,
                        modified = child.lastModified(),
                        size = child.length(),
                        cover = cover,
                        folderName = dir.name
                    )
                }
            }
        }
    }

    /** False when the tree's root cannot be listed at all, which is a drive that is not there. */
    private fun walkTree(tree: Uri, rootId: String, out: MutableList<Found>): Boolean {
        val authority = tree.authority.orEmpty()
        val pending = ArrayDeque<Pair<String, String>>().apply {
            add(rootId to (rootId.substringAfterLast('/').substringAfterLast(':')))
        }
        var first = true
        while (pending.isNotEmpty()) {
            val (dirId, dirName) = pending.removeLast()
            val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val files = ArrayList<Found>()
            val names = ArrayList<Pair<String, String>>()
            val cursor = runCatching {
                resolver.query(listing, ChildProjection, null, null, null)
            }.getOrNull()
            if (cursor == null) {
                if (first) return false
                continue
            }
            first = false
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending += id to name
                        continue
                    }
                    names += name to id
                    if (!isAudio(name, mime)) continue
                    val path = pathOfDocument(authority, id)
                    files += Found(
                        id = folderTrackIdOf(path ?: "$authority:$id"),
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        path = path,
                        name = name,
                        modified = if (c.isNull(3)) 0L else c.getLong(3),
                        size = if (c.isNull(4)) 0L else c.getLong(4),
                        cover = null,
                        folderName = dirName
                    )
                }
            }
            val coverName = coverAmong(names.map { it.first })
            val cover = coverName?.let { name ->
                names.first { it.first == name }.second
                    .let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            }
            files.forEach { out += if (cover == null) it else it.withCover(cover) }
        }
        return true
    }

    private fun Found.withCover(cover: Uri) =
        Found(id, uri, path, name, modified, size, cover, folderName)

    // ---- tags ----

    private suspend fun readTags(files: List<Found>): Map<Long, Cached> {
        val cached = readCache()
        val answers = HashMap<Long, Cached>(files.size)
        val stale = ArrayList<Found>()
        for (file in files) {
            val hit = cached[file.id]
            if (hit != null && hit.modified == file.modified && hit.size == file.size) {
                answers[file.id] = hit
            } else {
                stale += file
            }
        }
        if (stale.isNotEmpty()) {
            val started = System.currentTimeMillis()
            // A few at once: a USB stick answers several readers about as fast as one, and a
            // retriever is a native object worth not having dozens of.
            val gate = Semaphore(Readers)
            coroutineScope {
                stale.map { file -> async { gate.withPermit { file.id to readOne(file) } } }
                    .awaitAll()
            }.forEach { (id, tags) -> answers[id] = tags }
            Log.d(Tag, "Read ${stale.size} files in ${System.currentTimeMillis() - started}ms")
            writeCache(cached.values.filter { it.id !in answers } + answers.values)
        }
        return answers
    }

    private fun readOne(file: Found): Cached {
        val retriever = MediaMetadataRetriever()
        return try {
            if (file.path != null && file.uri.scheme == "file") {
                retriever.setDataSource(file.path)
            } else {
                retriever.setDataSource(context, file.uri)
            }
            fun text(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val mime = mimeOf(file.name) ?: text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            var year = text(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let(TrackYear::yearIn) ?: 0
            if (year <= 0) {
                year = text(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(TrackYear::yearIn) ?: 0
            }
            // The retriever reads the same non-standard year keys MediaStore does, so a FLAC
            // tagged `DATE` comes back without one here too — see [TrackYear].
            if (year <= 0 && mime != null && TrackYear.handles(mime)) {
                year = TrackYear.yearOf(context, file.uri, mime)
            }
            var duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= 0L && file.name.substringAfterLast('.').lowercase() in Mp4Extensions) {
                duration = TrackDuration.measure(context, file.uri)
            }
            val track = leadingNumber(text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            val disc = leadingNumber(text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            Cached(
                id = file.id,
                modified = file.modified,
                size = file.size,
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = duration,
                // MediaStore's encoding, so [com.metromusic.data.model.Track.discNo] reads both.
                trackNo = if (disc > 0) disc * 1000 + track else track,
                year = year,
                genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE)
            )
        } catch (e: Exception) {
            // A file that will not open is still a file: it is listed under its name, and the
            // player says what it says about it. Remembered all the same, so a broken file does not
            // cost a retriever on every scan.
            Log.w(Tag, "Cannot read the tags of ${file.name}", e)
            Cached(file.id, file.modified, file.size)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readCache(): Map<Long, Cached> = runCatching {
        val file = cacheFile
        if (!file.exists()) {
            emptyMap()
        } else {
            json.decodeFromString<List<Cached>>(file.readText()).associateBy { it.id }
        }
    }.getOrElse {
        Log.w(Tag, "Cannot read $CacheFile", it)
        emptyMap()
    }

    private fun writeCache(entries: List<Cached>) {
        runCatching {
            val temp = File(context.filesDir, "$CacheFile.tmp")
            temp.writeText(json.encodeToString(entries))
            if (!temp.renameTo(cacheFile)) {
                cacheFile.delete()
                temp.renameTo(cacheFile)
            }
        }.onFailure { Log.w(Tag, "Cannot write $CacheFile", it) }
    }

    companion object {
        private const val Tag = "FolderScanner"
        private const val CacheFile = "folder-tags.json"
        private const val Readers = 4

        private const val ExternalStorageAuthority = "com.android.externalstorage.documents"

        private val ChildProjection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )

        /** What ExoPlayer, with this app's own ALAC decoder, can play. */
        private val AudioExtensions = setOf(
            "mp3", "mp2", "flac", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wav", "mka"
        )
        private val Mp4Extensions = setOf("m4a", "m4b", "mp4")

        /** The folder-cover conventions [ArtworkLoader] also looks for, in order of preference. */
        private val CoverNames = listOf("cover", "folder", "front", "albumart", "album")
            .flatMap { base -> listOf("jpg", "jpeg", "png", "webp").map { "$base.$it" } }

        private fun isAudio(name: String, mime: String?): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension in AudioExtensions) return true
            // A playlist is `audio/x-mpegurl` too, and is not a song.
            return extension.isNotEmpty() && mime != null && mime.startsWith("audio/") &&
                extension !in setOf("m3u", "m3u8", "pls", "cue", "wpl", "xspf")
        }

        private fun coverAmong(names: List<String>): String? {
            val byLower = names.associateBy { it.lowercase() }
            return CoverNames.firstNotNullOfOrNull { byLower[it] }
        }

        private fun mimeOf(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            else -> null
        }

        private fun leadingNumber(text: String?): Int =
            text?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

        /**
         * Whether a volume may be read through the filesystem: everything below Android 10, where
         * the storage permission covers it, and on 11+ only with All Files Access. Android 10 is
         * neither — scoped storage without the special permission — so it always goes through the
         * provider.
         */
        fun canReadByPath(context: Context): Boolean = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            else -> false
        }

        /**
         * The path behind a document of the system's own storage provider, whose ids are
         * `<volume>:<path inside it>` — `primary` for the phone's own storage and the volume's id
         * (`1A2B-3C4D`) for a card or a drive. Null for any other provider, which has no path.
         */
        fun pathOfDocument(authority: String?, documentId: String): String? {
            if (authority != ExternalStorageAuthority) return null
            val volume = documentId.substringBefore(':', "")
            if (volume.isEmpty()) return null
            val inside = documentId.substringAfter(':', "").trim('/')
            val root = if (volume == "primary") {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory().path
            } else {
                "/storage/$volume"
            }
            return if (inside.isEmpty()) root else "$root/$inside"
        }

        /**
         * What a folder is called on the settings page: the drive's own name where the system has
         * one for it ("SanDisk USB drive", "SD card"), and the folder inside it.
         */
        fun labelOf(context: Context, entry: String): String {
            val path = if (entry.startsWith("/")) {
                entry
            } else {
                val uri = Uri.parse(entry)
                val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                    ?: return entry
                pathOfDocument(uri.authority, id) ?: return id.substringAfterLast(':').ifBlank { id }
            }
            val volume = volumeRootOf(path)
            val inside = path.removePrefix(volume).trim('/')
            val name = runCatching {
                context.getSystemService(StorageManager::class.java)
                    ?.getStorageVolume(File(volume))
                    ?.getDescription(context)
            }.getOrNull() ?: volume.substringAfterLast('/')
            return if (inside.isEmpty()) name else "$name › $inside"
        }

        private fun volumeRootOf(path: String): String {
            @Suppress("DEPRECATION")
            val primary = Environment.getExternalStorageDirectory().path
            if (path == primary || path.startsWith("$primary/")) return primary
            val parts = path.trim('/').split('/')
            return if (parts.size >= 2 && parts[0] == "storage") "/storage/${parts[1]}" else path
        }
    }
}
