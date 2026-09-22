package com.metromusic.data.lyrics

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.metromusic.data.media.AudioPaths
import com.metromusic.data.model.Track
import com.metromusic.data.store.SettingsStore
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * The `.lrc` files on the device: where they are, how they are read, and where a fetched set of
 * words is written so that something other than this app can find them again.
 *
 * **The sidecar works under scoped storage, and why it works is the load-bearing part.** A `.lrc`
 * traditionally sits beside the audio file under the same name. From Android 13 the storage
 * permissions are per media type and the one this app holds is `READ_MEDIA_AUDIO`, which covers the
 * audio files — so the expectation is that a text file next to them is refused. It is not, and the
 * mechanism is that MediaProvider classifies `.lrc` as a **subtitle**: `content query` over
 * `external/file` gives it `mime_type=application/lrc, media_type=5`, and subtitle files are gated
 * on the audio permission along with the media they belong to. Measured from inside the app on an
 * API 33 emulator rather than reasoned about, and the same probe settles the rest of the table:
 *
 * | | read | create |
 * |---|---|---|
 * | `.lrc` beside a track | yes | yes |
 * | `.txt` beside a track | `EACCES` | `EPERM` |
 * | `.lrc` another app wrote | yes | `EACCES` on overwrite |
 *
 * So a `.lrc` is readable and creatable where a `.txt` is neither — which is exactly the distinction
 * the extension buys, and the reason [save] writes a sidecar rather than asking anybody for a
 * folder. A `.txt` is still *tried* on the way in, because it costs one `exists()` and it works on
 * Android 12 and below, where the blanket read permission covers everything.
 *
 * The one thing the table denies is overwriting a `.lrc` this app did not create — scoped storage
 * gives an app its own files and no others. That is not a limitation in practice: the only reason to
 * write a file is that no readable one was found, so a file the user put there is read, not replaced.
 *
 * **The folder is the fallback, not the route.** That mime mapping is MediaProvider's and not a
 * contract; a device that files `.lrc` under documents instead would refuse the sidecar, and some
 * people keep their lyrics somewhere other than next to the music. So a folder can be handed over
 * through the system picker with its permission persisted, and is then read and written like any
 * other. It is offered rather than required, and on what was measured above nothing needs it.
 *
 * Everything in here blocks; call it from IO.
 */
class LyricsFiles(
    private val context: Context,
    private val settings: SettingsStore,
    private val paths: AudioPaths
) {

    private val resolver get() = context.contentResolver

    /** The tree listing, rebuilt when the folder changes rather than per lookup. */
    private var indexedFolder: String? = null
    private var folderIndex: Map<String, Uri> = emptyMap()

    // ---- reading ----

    /**
     * The words for [track] from the device — a sidecar, the file's own tags, or the chosen folder.
     *
     * **A timed answer beats a near one.** The sources are tried in order of how specific they are to
     * this exact file, but a *synced* answer from any of them wins outright: somebody holding a
     * downloaded `.lrc` full of timestamps and a flat `LYRICS` tag inside the file wants the one that
     * follows the music, and "which folder was it in" is not the question being asked. The search
     * stops the moment a synced one turns up, so the common case — a synced sidecar — still costs a
     * single read.
     */
    fun read(track: Track): Lyrics? {
        var plain: Lyrics? = null
        for (source in listOf(::fromSidecar, ::fromTags, ::fromFolder)) {
            val found = source(track) ?: continue
            if (found.synced) return found
            if (plain == null) plain = found
        }
        return plain
    }

    private fun fromSidecar(track: Track): Lyrics? {
        val file = sidecar(track) ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return Lrc.parse(text, LyricsOrigin.Lrc, file.name)
    }

    /**
     * Whether there is a *file* of words for [track] — a sidecar or one in the folder.
     *
     * Deliberately not a parse and deliberately not [hasTags]: the probe asks this about every song
     * in the library and about most of them more than once, so it has to stay a `stat` and a lookup
     * in a map that was built once. A file that turns out to hold nothing usable falls through to the
     * next source when it is opened, so the worst this can be wrong by is one hopeful menu entry.
     */
    fun has(track: Track): Boolean =
        sidecar(track) != null ||
            folderIndex().let { index ->
                index.isNotEmpty() && candidateNames(track).any { fold(it) in index }
            }

    /**
     * Whether the audio file carries its own lyrics — the expensive half of the same question.
     *
     * Separate from [has] because of what it costs: this opens and parses the audio file's tags, and
     * **measured over a 123-track library on the emulator that is 3.9 seconds, about 32ms a track**,
     * against microseconds for the two cheap sources. Multiply that by a real library and it is a
     * minute and a half of disk on every launch, which is not a thing to spend to decide whether a
     * menu entry is grey.
     *
     * So the caller gets to choose, and [LyricsRepository.probe] asks this only about songs it has no
     * verdict on at all — once each, ever. The two can be treated differently because they change for
     * different reasons: a `.lrc` appears next to a song whenever its owner puts one there, while the
     * words inside a file change only when somebody edits its tags.
     */
    fun hasTags(track: Track): Boolean = fromTags(track) != null

    /** The `.lrc` (or `.txt`) sitting next to the audio file, if there is one this app can open. */
    private fun sidecar(track: Track): File? {
        val path = audioPath(track) ?: return null
        val base = path.substringBeforeLast('.', path)
        return sequenceOf("$base.lrc", "$path.lrc", "$base.txt")
            .map(::File)
            // `canRead` rather than `exists`: a `.txt` on Android 13 is *there* and refused, and
            // opening it would throw where falling through to the next source is what is wanted.
            .firstOrNull { runCatching { it.canRead() }.getOrDefault(false) }
    }

    /**
     * The file's own path, from the one column MediaStore is still right about — see [AudioPaths],
     * which reads the whole volume in one cursor and is shared with the playlist files for exactly
     * that reason.
     */
    private fun audioPath(track: Track): String? = paths.pathOf(track.id)

    /** Drops what was read off the volume, so the next lookup reads it again. Called per scan. */
    fun refresh() {
        paths.refresh()
        invalidate()
    }

    // ---- the words inside the file ----

    /**
     * Lyrics out of the audio file's own tags.
     *
     * This is where a FLAC keeps them: a Vorbis comment, spelled `LYRICS` by most taggers and
     * `UNSYNCEDLYRICS` or `SYNCEDLYRICS` by the rest. The same call reaches an MP3's `USLT` and an
     * MP4's lyrics atom, because jaudiotagger normalises those behind `FieldKey.LYRICS`. The value is
     * very often a whole `.lrc` — timestamps and all — pasted into the tag, which is why it goes
     * through the same parser as a file instead of being treated as flat text.
     *
     * Read straight off the path, rather than through a copy in the cache the way the tag *writer*
     * has to go: reading needs no seekable growable file, and the audio file itself is the one thing
     * `READ_MEDIA_AUDIO` is unambiguously for.
     */
    private fun fromTags(track: Track): Lyrics? {
        val path = audioPath(track) ?: return null
        val file = File(path)
        if (!runCatching { file.canRead() }.getOrDefault(false)) return null
        return runCatching {
            val tag = AudioFileIO.read(file).tag ?: return null
            val texts = buildList {
                // `getFirst(String)` reaches a Vorbis comment by its own name, which is the only way
                // to see the spellings that have no `FieldKey` of their own. Each is guarded
                // separately: some tag types throw on a field id they do not recognise rather than
                // answering with nothing.
                for (name in RawLyricFields) {
                    runCatching { tag.getFirst(name) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
                runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
            val parsed = texts.mapNotNull { Lrc.parse(it, LyricsOrigin.Tag, file.name) }
            parsed.firstOrNull { it.synced } ?: parsed.firstOrNull()
        }.onFailure { Log.d(Tag, "No readable tags in ${file.name}", it) }.getOrNull()
    }

    // ---- the folder the user picked ----

    /** Something readable to put under the settings row — the folder's own name, not its uri. */
    fun folderLabel(): String? = lyricsFolderName(folderUri())

    /**
     * Takes the permission the picker just granted and remembers the folder.
     *
     * The persistable grant is the whole point — without it the folder is readable until the process
     * dies and then silently is not, which reads as the feature working once.
     */
    fun rememberFolder(uri: Uri?) {
        if (uri == null) {
            settings.setLyricsFolder(null)
            invalidate()
            return
        }
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { Log.w(Tag, "Cannot hold on to $uri", it) }
        settings.setLyricsFolder(uri.toString())
        invalidate()
    }

    private fun folderUri(): Uri? =
        settings.settings.value.lyricsFolderUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    private fun invalidate() {
        indexedFolder = null
        folderIndex = emptyMap()
    }

    private fun fromFolder(track: Track): Lyrics? {
        val index = folderIndex().takeIf { it.isNotEmpty() } ?: return null
        for (name in candidateNames(track)) {
            val uri = index[fold(name)] ?: continue
            val text = runCatching {
                resolver.openInputStream(uri)?.use { it.reader().readText() }
            }.getOrNull() ?: continue
            Lrc.parse(text, LyricsOrigin.Lrc, name)?.let { return it }
        }
        return null
    }

    /**
     * The names a file of this track's words could plausibly have, best first.
     *
     * Three shapes, because three tools wrote them: a sidecar carries the audio file's own name, a
     * downloaded set carries "artist - title", and a hand-made one is often just the title.
     */
    private fun candidateNames(track: Track): List<String> {
        val audioName = audioPath(track)?.substringAfterLast('/')?.substringBeforeLast('.', "")
        return buildList {
            audioName?.takeIf { it.isNotBlank() }?.let { add("$it.lrc") }
            add("${track.artist} - ${track.title}.lrc")
            add("${track.title}.lrc")
            audioName?.takeIf { it.isNotBlank() }?.let { add("$it.txt") }
            add("${track.artist} - ${track.title}.txt")
        }
    }

    /**
     * Every file in the chosen folder, by folded name.
     *
     * Listed once and kept, rather than a query per track: a lookup per song against a provider is a
     * binder round trip each time, and the background probe walks the whole library. Subdirectories
     * are walked too — a music folder is a folder per album — with a cap, because the user is free to
     * point this at the root of the card.
     */
    private fun folderIndex(): Map<String, Uri> {
        val uri = folderUri()
        if (uri == null) {
            invalidate()
            return emptyMap()
        }
        val key = uri.toString()
        if (indexedFolder == key) return folderIndex

        val found = mutableMapOf<String, Uri>()
        runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            walk(uri, root, found, depth = 0)
        }.onFailure { Log.w(Tag, "Cannot list the lyrics folder", it) }

        indexedFolder = key
        folderIndex = found
        return found
    }

    private fun walk(tree: Uri, parent: Uri, into: MutableMap<String, Uri>, depth: Int) {
        if (depth > MaxDepth || into.size >= MaxFiles) return
        val directories = mutableListOf<Uri>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(parent)
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directories += child
                } else if (name.endsWith(".lrc", true) || name.endsWith(".txt", true)) {
                    if (into.size < MaxFiles) into.putIfAbsent(fold(name), child)
                }
            }
        }
        // Recursed outside the `use`, so a provider is never asked for a second cursor while one of
        // its own is still open. A music folder is deep enough for that to matter.
        directories.forEach { walk(tree, it, into, depth + 1) }
    }

    // ---- writing ----

    /**
     * Writes [lyrics] out as a `.lrc` for [track] and says where it landed, or null if nowhere.
     *
     * The sidecar first, because that is what the format is *for*: a file called the same thing as
     * the song, in the same folder, which every other player already looks for. Creating one works
     * under scoped storage — see the table on this class — and the media scanner is told about it
     * afterwards so the rest of the device can find it too.
     *
     * The chosen folder is the fallback, for a device that refuses and for somebody who keeps their
     * lyrics elsewhere. There is deliberately no third fallback into the app's own directory: the
     * words are already cached there, and a file nothing else can read would make this switch look
     * like it had done something it had not.
     */
    fun save(track: Track, lyrics: Lyrics): String? {
        val text = Lrc.render(track, lyrics)
        writeBesideTheTrack(track, text)?.let { return it }
        return writeToFolder(fileName(track), text)
    }

    private fun fileName(track: Track): String {
        val audioName = audioPath(track)?.substringAfterLast('/')?.substringBeforeLast('.', "")
        val base = audioName?.takeIf { it.isNotBlank() }
            ?: "${track.artist} - ${track.title}".ifBlank { track.id.toString() }
        return safe(base) + ".lrc"
    }

    private fun writeToFolder(name: String, text: String): String? {
        val tree = folderUri() ?: return null
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            // Overwrite rather than create a second one: a provider handed a name it already holds
            // answers with "name (1).lrc", and a folder that grows a copy per lookup is worse than
            // no feature at all.
            val existing = folderIndex()[fold(name)]
            // `application/octet-stream` and not `text/plain`, which is the trap here: the provider
            // forces the file's extension to match the mime type it is given, and the extension for
            // `text/plain` is `txt` — so a document asked for as `song.lrc` is created as
            // `song.lrc.txt`. Nothing maps to `.lrc`, and an unknown extension is exactly what the
            // octet-stream branch of that check lets through untouched.
            val target = existing ?: DocumentsContract.createDocument(
                resolver,
                parent,
                "application/octet-stream",
                name
            ) ?: return null
            // "wt" truncates. Without it, overwriting a long set of words with a short one leaves
            // the tail of the old file behind — the same trap the tag writer documents.
            resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
                ?: return null
            if (existing == null) invalidate()
            folderLabel()?.let { "$it/$name" } ?: name
        }.onFailure { Log.w(Tag, "Cannot write $name to the lyrics folder", it) }.getOrNull()
    }

    /** The sidecar: the same name as the audio file, in the same directory as it. */
    private fun writeBesideTheTrack(track: Track, text: String): String? {
        val path = audioPath(track) ?: return null
        val file = File(path.substringBeforeLast('.', path) + ".lrc")
        return runCatching {
            file.writeText(text)
            // Otherwise the file is on the disk and in no index, so nothing else on the device finds
            // it until something happens to rescan — the same nudge the tag writer ends on.
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
            }
            file.name
        }.onFailure {
            // Not a warning: a device that refuses this is the case the folder exists for.
            Log.d(Tag, "No sidecar beside ${file.name}", it)
        }.getOrNull()
    }

    // ---- names ----

    /** What two spellings of one file name have in common: letters and digits, folded. */
    private fun fold(name: String): String = name.lowercase().replace(FoldedAway, "")

    /** A file name a filesystem will take, which a song title is not obliged to be. */
    private fun safe(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120)

    private companion object {
        const val Tag = "LyricsFiles"

        /** A music folder is a folder per album, so two levels below the one picked is plenty. */
        const val MaxDepth = 4

        /** The folder could be the root of the card; this is the point at which that stops mattering. */
        const val MaxFiles = 20_000

        val FoldedAway = Regex("""[^\p{L}\p{Nd}.]+""")

        /**
         * Vorbis comment names taggers actually use for lyrics, the timed spelling first.
         *
         * `LYRICS` is also what jaudiotagger's own `FieldKey.LYRICS` maps to for a FLAC, so it is in
         * this list for the ordering rather than for reach — the two either side of it have no
         * `FieldKey` at all and would otherwise be invisible.
         */
        val RawLyricFields = listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS")
    }
}

/**
 * The name of a chosen lyrics folder, for a settings row to show.
 *
 * A free function because the settings page has the *setting* — a string it collects and
 * recomposes on — and not the repository's cached view of it: asking an object for a label would
 * leave the row showing the old folder until something else redrew it.
 */
fun lyricsFolderName(uri: Uri?): String? {
    if (uri == null) return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    // Tree ids are "primary:Music/Lyrics" and the like; the part after the volume is the folder.
    return id.substringAfterLast(':').takeIf { it.isNotBlank() } ?: id
}
