package com.metromusic.data.playlist

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.metromusic.data.store.SettingsStore
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One playlist file, wherever it happens to live.
 *
 * [id] is what everything else holds on to — a back stack entry, a row's key — so it has to survive
 * the process: an absolute path for a file, a document uri for one inside a folder the user handed
 * over. Which of the two it is, is the string's own business ([PlaylistFiles.refOf]).
 */
sealed interface PlaylistRef {
    val id: String

    data class OnDisk(val file: File) : PlaylistRef {
        override val id: String get() = file.absolutePath
    }

    data class InTree(val tree: Uri, val document: Uri) : PlaylistRef {
        override val id: String get() = document.toString()
    }
}

/** What happened to a write. The middle one is the whole reason this is not a boolean. */
sealed interface PlaylistWrite {
    data object Ok : PlaylistWrite

    /**
     * The file belongs to another app, and the platform will hand over write access for it once the
     * user has said so. The same operation again after they have agreed does the work.
     */
    data class NeedsConsent(val request: IntentSender) : PlaylistWrite

    data class Failed(val reason: String?) : PlaylistWrite
}

/** A playlist file as the folder has it, before anything has been read out of it. */
data class StoredPlaylist(
    val ref: PlaylistRef,
    val name: String,
    val writable: Boolean,
    val modifiedAt: Long
)

/**
 * Where playlists live on the device, and the only place that knows it.
 *
 * **The point of the format is that something else can read it**, so the files go somewhere else can
 * see: `Music/Playlists`, one plain `.m3u` per playlist. That is allowed under scoped storage
 * without asking for anything — an app may create files in the shared media directories, and it owns
 * what it creates — and it is the same bargain the `.lrc` sidecar already strikes in
 * [com.metromusic.data.lyrics.LyricsFiles]: the file outlives this app, and every other player
 * already looks for it.
 *
 * Three homes are tried in order, and which one answered is shown in settings rather than guessed at:
 *
 *  1. **A folder the user picked**, if they picked one. Somebody who keeps their playlists on a card
 *     or in a synced folder has said where, and that beats any default.
 *  2. **`Music/Playlists`**, which is the answer on every device this has been tried on.
 *  3. **The app's own directory**, if the second is refused. A playlist nothing else can read is a
 *     poor thing, and it is still better than a playlist feature that does not work at all — but the
 *     settings page says which one is in force, so it is never a silent downgrade.
 *
 * **`.m3u` and not `.m3u8`, deliberately**, even though the files are UTF-8 and `.m3u8` is the
 * extension that says so. MediaProvider maps `.m3u` to `audio/x-mpegurl`, which is a *playlist* and
 * therefore something an app may create in `Music/`; `.m3u8` maps to Apple's HLS type, which is not,
 * and creating one there can be refused for the mime type alone. Both are read.
 *
 * Everything here blocks; call it from IO.
 */
class PlaylistFiles(private val context: Context, private val settings: SettingsStore) {

    private val resolver get() = context.contentResolver

    /** Where the last [home] call landed, for the settings page to show. */
    @Volatile
    var homeLabel: String = ""
        private set

    /** True when playlists are in the app's private directory, i.e. nothing else can see them. */
    @Volatile
    var homeIsPrivate: Boolean = false
        private set

    // ---- where ----

    private fun treeUri(): Uri? =
        settings.settings.value.playlistFolderUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /**
     * Takes the permission the picker granted and remembers the folder. The grant has to be taken as
     * *persistable* or it lasts until the process dies, which reads as the feature having worked
     * once.
     */
    fun rememberFolder(uri: Uri?) {
        if (uri == null) {
            settings.setPlaylistFolder(null)
            return
        }
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { Log.w(Tag, "Cannot hold on to " + uri, it) }
        settings.setPlaylistFolder(uri.toString())
    }

    /**
     * `Music/Playlists`, if this app can really write there.
     *
     * **Probed rather than asked.** `canWrite()` on a FUSE path answers about the directory and not
     * about what the media provider will allow inside it — the rule there is per *file*, on the
     * extension and the mime type it maps to — so the only honest question is whether a `.m3u` can
     * be created. It is asked once per process with a file that is removed immediately, and the
     * answer decides between this folder and the app's own, which is a choice the settings page
     * shows rather than makes silently.
     */
    private fun publicFolder(): File? {
        @Suppress("DEPRECATION")
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val folder = File(music, FolderName)
        when (publicUsable) {
            false -> return null
            true -> return folder
            null -> Unit
        }
        val usable = runCatching {
            if (!folder.isDirectory) folder.mkdirs()
            if (!folder.isDirectory) return@runCatching false
            val probe = File(folder, ProbeName)
            probe.writeText(M3u.render(emptyList()))
            probe.delete()
            true
        }.onFailure { Log.i(Tag, "The shared playlist folder will not take files", it) }
            .getOrDefault(false)
        publicUsable = usable
        return folder.takeIf { usable }
    }

    /** The probe's answer, which cannot change while the app is running. */
    @Volatile
    private var publicUsable: Boolean? = null

    private fun privateFolder(): File =
        File(context.filesDir, "playlists").apply { if (!isDirectory) mkdirs() }

    private sealed interface Home {
        data class Folder(val dir: File) : Home
        data class Tree(val uri: Uri) : Home
    }

    private fun home(): Home {
        val tree = treeUri()
        if (tree != null) {
            // A grant can be revoked from system settings, and a tree that answers nothing is worse
            // than the default: the playlists would simply be gone.
            if (canUse(tree)) {
                homeLabel = playlistFolderName(tree) ?: tree.toString()
                homeIsPrivate = false
                return Home.Tree(tree)
            }
            Log.w(Tag, "The chosen playlist folder is no longer writable")
        }
        val public = publicFolder()
        if (public != null) {
            homeLabel = Environment.DIRECTORY_MUSIC + "/" + FolderName
            homeIsPrivate = false
            return Home.Folder(public)
        }
        homeLabel = PrivateLabel
        homeIsPrivate = true
        return Home.Folder(privateFolder())
    }

    private fun canUse(tree: Uri): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }.getOrDefault(false)

    // ---- what is there ----

    /** Every playlist in the home folder. */
    fun list(): List<StoredPlaylist> = when (val home = home()) {
        is Home.Folder -> home.dir.listFiles().orEmpty()
            .filter { it.isFile && isPlaylistName(it.name) }
            .map {
                StoredPlaylist(
                    ref = PlaylistRef.OnDisk(it),
                    name = displayName(it.name),
                    // Not a guess: a file another app created is readable and refused on write, and
                    // that difference is what decides whether an edit needs the consent dialog.
                    writable = it.canWrite(),
                    modifiedAt = it.lastModified()
                )
            }

        is Home.Tree -> listTree(home.uri)
    }

    private fun listTree(tree: Uri): List<StoredPlaylist> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val found = mutableListOf<StoredPlaylist>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (!isPlaylistName(name)) continue
                found += StoredPlaylist(
                    ref = PlaylistRef.InTree(
                        tree,
                        DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    ),
                    name = displayName(name),
                    // The tree was granted read *and* write, so everything in it is ours to edit.
                    writable = true,
                    modifiedAt = cursor.getLong(2)
                )
            }
        }
        found
    }.onFailure { Log.w(Tag, "Cannot list the playlist folder", it) }.getOrDefault(emptyList())

    fun refOf(id: String): PlaylistRef =
        if (id.startsWith("content://")) {
            val document = Uri.parse(id)
            PlaylistRef.InTree(treeUri() ?: document, document)
        } else {
            PlaylistRef.OnDisk(File(id))
        }

    /**
     * The directory a playlist's relative lines are relative *to*, as a real path, or null where
     * there is no telling.
     *
     * A document uri on the primary volume spells its own path in its id
     * (`primary:Music/Playlists/x.m3u`), which is worth unpicking: it is what lets a playlist in a
     * folder the user picked still be written with portable relative paths instead of absolute ones.
     */
    fun directoryOf(ref: PlaylistRef): String? = when (ref) {
        is PlaylistRef.OnDisk -> ref.file.parent
        is PlaylistRef.InTree -> documentPath(ref.document)
            ?.substringBeforeLast('/', "")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun documentPath(document: Uri): String? = runCatching {
        val id = DocumentsContract.getDocumentId(document)
        val volume = id.substringBefore(':', "")
        val relative = id.substringAfter(':', "")
        if (relative.isEmpty()) return null
        @Suppress("DEPRECATION")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()?.absolutePath
        } else {
            "/storage/" + volume
        }
        root?.let { it + "/" + relative }
    }.getOrNull()

    // ---- reading ----

    fun read(ref: PlaylistRef): String? = when (ref) {
        is PlaylistRef.OnDisk -> runCatching { ref.file.readText() }.getOrNull()
            // A file another app wrote is readable through the store even where the direct read is
            // refused, which is the same split the lyrics sidecar documents.
            ?: mediaUriFor(ref.file)?.let { readStream(it) }

        is PlaylistRef.InTree -> readStream(ref.document)
    }

    private fun readStream(uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { it.reader().readText() }
    }.onFailure { Log.d(Tag, "Cannot read " + uri, it) }.getOrNull()

    // ---- writing ----

    fun write(ref: PlaylistRef, text: String): PlaylistWrite = when (ref) {
        is PlaylistRef.OnDisk -> writeFile(ref.file, text)
        is PlaylistRef.InTree -> runCatching {
            // "wt" truncates: without it, a long playlist replaced by a short one keeps the tail of
            // the old one, which reads as rows coming back from the dead.
            val stream = resolver.openOutputStream(ref.document, "wt")
                ?: return PlaylistWrite.Failed(null)
            stream.use { it.write(text.toByteArray()) }
            PlaylistWrite.Ok
        }.getOrElse { PlaylistWrite.Failed(it.message) }
    }

    private fun writeFile(file: File, text: String): PlaylistWrite {
        val direct = runCatching {
            file.writeText(text)
            scan(file)
        }
        if (direct.isSuccess) return PlaylistWrite.Ok
        Log.d(Tag, "No direct write to " + file.name, direct.exceptionOrNull())

        // Refused, so this is a file the app does not own. The store hands over a writable
        // descriptor for it once the user has agreed, and that agreement is per file and lasts, so
        // the dialog appears once rather than once per edit.
        val media = mediaUriFor(file) ?: return PlaylistWrite.Failed(null)
        return runCatching {
            val stream = resolver.openOutputStream(media, "wt")
                ?: return PlaylistWrite.Failed(null)
            stream.use { it.write(text.toByteArray()) }
            scan(file)
            PlaylistWrite.Ok
        }.getOrElse { error ->
            consentFor(listOf(media), error)?.let { PlaylistWrite.NeedsConsent(it) }
                ?: PlaylistWrite.Failed(error.message)
        }
    }

    /**
     * Makes an empty playlist called [name] and says where it landed.
     *
     * The name is made unique against what is already there rather than overwriting it: two
     * playlists called "gym" is the user's business, silently replacing the first with the second is
     * not.
     */
    fun create(name: String): PlaylistRef? = when (val home = home()) {
        is Home.Folder -> {
            val taken = home.dir.listFiles().orEmpty().map { it.name.lowercase() }.toSet()
            val file = File(home.dir, uniqueName(name, taken))
            runCatching {
                file.writeText(M3u.render(emptyList(), name))
                scan(file)
                PlaylistRef.OnDisk(file) as PlaylistRef
            }.onFailure { Log.w(Tag, "Cannot create " + file.name, it) }.getOrNull()
        }

        is Home.Tree -> runCatching {
            val taken = listTree(home.uri).map { fileNameOf(it) }.toSet()
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                home.uri,
                DocumentsContract.getTreeDocumentId(home.uri)
            )
            // `audio/x-mpegurl` and not `text/plain`: a provider forces the extension to match the
            // type it was given, so a document asked for as "gym.m3u" with the wrong type is created
            // as "gym.m3u.txt" — the trap the lyrics writer documents, one type further on.
            val document = DocumentsContract.createDocument(
                resolver,
                parent,
                PlaylistMime,
                uniqueName(name, taken)
            )
            if (document == null) {
                null
            } else {
                resolver.openOutputStream(document, "wt")
                    ?.use { it.write(M3u.render(emptyList(), name).toByteArray()) }
                PlaylistRef.InTree(home.uri, document) as PlaylistRef
            }
        }.onFailure { Log.w(Tag, "Cannot create a playlist in the chosen folder", it) }.getOrNull()
    }

    /** The outcome of a rename, and where the playlist is now — the id moves with the file name. */
    data class Renamed(val result: PlaylistWrite, val ref: PlaylistRef)

    /** Renames the file itself, so its name means the same thing here and everywhere else. */
    fun rename(ref: PlaylistRef, name: String): Renamed = when (ref) {
        is PlaylistRef.OnDisk -> {
            val taken = ref.file.parentFile?.listFiles().orEmpty()
                .filterNot { it == ref.file }
                .map { it.name.lowercase() }
                .toSet()
            val target = File(ref.file.parentFile, uniqueName(name, taken))
            if (runCatching { ref.file.renameTo(target) }.getOrDefault(false)) {
                scan(ref.file)
                scan(target)
                Renamed(PlaylistWrite.Ok, PlaylistRef.OnDisk(target))
            } else {
                renameThroughStore(ref.file, target.name)
            }
        }

        is PlaylistRef.InTree -> runCatching {
            val moved = DocumentsContract.renameDocument(
                resolver,
                ref.document,
                uniqueName(name, emptySet())
            )
            Renamed(
                PlaylistWrite.Ok,
                if (moved == null) ref else PlaylistRef.InTree(ref.tree, moved)
            )
        }.getOrElse { Renamed(PlaylistWrite.Failed(it.message), ref) }
    }

    private fun renameThroughStore(file: File, name: String): Renamed {
        val media = mediaUriFor(file)
            ?: return Renamed(PlaylistWrite.Failed(null), PlaylistRef.OnDisk(file))
        return runCatching {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            resolver.update(media, values, null, null)
            val renamed = File(file.parentFile, name)
            scan(file)
            scan(renamed)
            Renamed(PlaylistWrite.Ok, PlaylistRef.OnDisk(renamed))
        }.getOrElse { error ->
            val consent = consentFor(listOf(media), error)
            Renamed(
                if (consent != null) {
                    PlaylistWrite.NeedsConsent(consent)
                } else {
                    PlaylistWrite.Failed(error.message)
                },
                PlaylistRef.OnDisk(file)
            )
        }
    }

    fun delete(ref: PlaylistRef): PlaylistWrite = when (ref) {
        is PlaylistRef.OnDisk -> deleteFile(ref.file)

        is PlaylistRef.InTree -> runCatching {
            if (DocumentsContract.deleteDocument(resolver, ref.document)) {
                PlaylistWrite.Ok
            } else {
                PlaylistWrite.Failed(null)
            }
        }.getOrElse { PlaylistWrite.Failed(it.message) }
    }

    private fun deleteFile(file: File): PlaylistWrite {
        if (runCatching { file.delete() }.getOrDefault(false)) {
            scan(file)
            return PlaylistWrite.Ok
        }
        val media = mediaUriFor(file) ?: return PlaylistWrite.Failed(null)
        return runCatching {
            resolver.delete(media, null, null)
            scan(file)
            PlaylistWrite.Ok
        }.getOrElse { error ->
            // Deleting has a request of its own, and it is the one whose dialog says "delete"
            // rather than "allow to modify" — which is the truth about what follows it.
            deleteConsentFor(listOf(media), error)?.let { PlaylistWrite.NeedsConsent(it) }
                ?: PlaylistWrite.Failed(error.message)
        }
    }

    // ---- in and out ----

    /** Copies a playlist file from anywhere on the device into the home folder, under its own name. */
    fun import(source: Uri): PlaylistRef? {
        val text = readStream(source) ?: return null
        val parsed = M3u.parse(text)
        val fallback = displayName(documentDisplayName(source) ?: "playlist")
        val name = parsed.title?.takeIf { it.isNotBlank() } ?: fallback
        val ref = create(name) ?: return null
        // The name the folder *gave* it, which is not always the one that was asked for: a second
        // copy of one playlist is "night bus (2)", and the `#PLAYLIST` line inside has to say the
        // same thing as the row on screen, which takes its name from the file.
        val landed = nameOf(ref) ?: name
        // Written back out through the renderer rather than copied byte for byte: what lands is a
        // file this app can read again with no surprises — one encoding, LF endings, an `#EXTINF`
        // per line — while the lines themselves are carried over exactly as they were written.
        return if (write(ref, M3u.render(parsed.entries, landed)) is PlaylistWrite.Ok) ref else null
    }

    /** Writes a playlist out to a document the user picked — the other half of [import]. */
    fun export(text: String, target: Uri): Boolean = runCatching {
        val stream = resolver.openOutputStream(target, "wt") ?: return false
        stream.use { it.write(text.toByteArray()) }
        true
    }.onFailure { Log.w(Tag, "Cannot export to " + target, it) }.getOrDefault(false)

    private fun documentDisplayName(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---- the store, for the files this app does not own ----

    /**
     * The store's own uri for a file, which is the only handle a consent dialog can be asked about.
     *
     * **Asking the store is not enough, and that is the whole reason this has two halves.** A query
     * over the files collection only returns rows the caller may already see, and a *playlist* row
     * belonging to another app is not one of them under `READ_MEDIA_AUDIO` — so the file this app
     * can read perfectly well through the filesystem comes back as "no such row", and there is
     * nothing to put in front of the user. Measured on an API 33 emulator against a `.m3u` pushed
     * by the shell: `content query` from a shell that can see everything finds it with
     * `owner_package_name=com.android.shell`, and the same query from inside the app finds nothing.
     *
     * So the fallback is to have the file *scanned*. The media scanner is a system component and
     * answers with the row's uri whoever owns it, which is exactly the handle needed — and for a
     * file that really is unknown to the store it inserts it, which is also the right answer.
     */
    private fun mediaUriFor(file: File): Uri? = queryMediaUri(file) ?: scanForUri(file)

    @Suppress("DEPRECATION")
    private fun queryMediaUri(file: File): Uri? = runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DATA + "=?",
            arrayOf(file.absolutePath),
            null
        )?.use {
            if (it.moveToFirst()) Uri.withAppendedPath(collection, it.getLong(0).toString()) else null
        }
    }.getOrNull()

    /** Blocks on the scanner's callback, which is why everything in this class says to call it from IO. */
    private fun scanForUri(file: File): Uri? = runCatching {
        val answered = CountDownLatch(1)
        val found = AtomicReference<Uri?>(null)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.path),
            arrayOf(PlaylistMime)
        ) { _, uri ->
            found.set(uri)
            answered.countDown()
        }
        answered.await(ScanTimeoutSeconds, TimeUnit.SECONDS)
        found.get()
    }.onFailure { Log.d(Tag, "The scanner did not answer for " + file.name, it) }.getOrNull()

    private fun consentFor(uris: List<Uri>, error: Throwable): IntentSender? = when {
        error !is SecurityException && error !is FileNotFoundException -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            runCatching { MediaStore.createWriteRequest(resolver, uris).intentSender }.getOrNull()

        else -> null
    }

    private fun deleteConsentFor(uris: List<Uri>, error: Throwable): IntentSender? = when {
        error !is SecurityException && error !is FileNotFoundException -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            runCatching { MediaStore.createDeleteRequest(resolver, uris).intentSender }.getOrNull()

        else -> null
    }

    /**
     * Tells the rest of the device about a file this app just wrote or removed. Without it the
     * playlist is on the disk and in no index, so the other player on the phone does not see it
     * until something happens to rescan — the same nudge the lyrics writer ends on.
     */
    private fun scan(file: File) {
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) }
    }

    // ---- names ----

    /** What a playlist in [ref] is called, which is its file name without the extension. */
    private fun nameOf(ref: PlaylistRef): String? = when (ref) {
        is PlaylistRef.OnDisk -> displayName(ref.file.name)
        is PlaylistRef.InTree -> documentDisplayName(ref.document)?.let(::displayName)
    }

    private fun fileNameOf(stored: StoredPlaylist): String = when (val ref = stored.ref) {
        is PlaylistRef.OnDisk -> ref.file.name.lowercase()
        is PlaylistRef.InTree -> (stored.name + "." + M3u.Extension).lowercase()
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        val base = safe(name).ifBlank { "playlist" }
        var candidate = base + "." + M3u.Extension
        var n = 2
        while (candidate.lowercase() in taken) {
            candidate = base + " (" + n + ")." + M3u.Extension
            n++
        }
        return candidate
    }

    private companion object {
        const val Tag = "PlaylistFiles"
        const val FolderName = "Playlists"
        const val PlaylistMime = "audio/x-mpegurl"
        const val PrivateLabel = "the app's own folder"

        /** The scanner runs out of process; this is longer than it has ever taken to answer. */
        const val ScanTimeoutSeconds = 5L

        /** Removed as soon as it is written — see [publicFolder]. */
        const val ProbeName = ".metromusic-probe.m3u"

        fun isPlaylistName(name: String): Boolean =
            name.endsWith(".m3u", true) || name.endsWith(".m3u8", true)

        fun displayName(fileName: String): String =
            fileName.substringBeforeLast('.', fileName).trim().ifBlank { fileName }

        /** A file name a filesystem will take, which a playlist's name is not obliged to be. */
        fun safe(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120)
    }
}

/**
 * The name of a chosen folder, for a settings row to show. A free function for the reason its
 * lyrics counterpart is: the row has the *setting* and not this object's cached view of it.
 */
fun playlistFolderName(uri: Uri?): String? {
    if (uri == null) return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return id.substringAfterLast(':').takeIf { it.isNotBlank() } ?: id
}
