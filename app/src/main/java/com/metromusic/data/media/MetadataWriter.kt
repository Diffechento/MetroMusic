package com.metromusic.data.media

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * What an edit is trying to say about a file. Null means "leave it alone".
 */
data class Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val genre: String? = null
) {
    val isEmpty: Boolean
        get() = listOf(title, artist, album, year, genre).all { it == null }
}

/**
 * Changes the metadata of audio files, by writing the tags **inside** them.
 *
 * This used to update MediaStore's own columns instead, which is the obvious thing to do and does not
 * work: from Android 10 the media database treats the metadata columns as derived from the file, and
 * an `update()` that sets `album` or `genre` is dropped — no exception, no rows-affected of zero, no
 * change either. Verified against the store directly (`content update --bind album:s:…` followed by a
 * query returns the old value), which is why "save" appeared to do nothing at all: everything the
 * page collected was being written into a column the provider ignores.
 *
 * So the tags are written, and the media database is asked to read the file again afterwards. The
 * edit then survives a rescan instead of being undone by one — the opposite of the old caveat.
 *
 * **The dance around scoped storage.** A tag write changes the length of the file, so it is not an
 * in-place patch: the file is copied into the cache, tagged there, and streamed back over the
 * original through a `"rwt"` descriptor. Android will not hand out that descriptor for media the app
 * does not own until the user has agreed once, which arrives as [Result.NeedsConsent] with a system
 * dialog to launch — `createWriteRequest` from API 30, a [RecoverableSecurityException] before it.
 * The same call again once they have agreed does the work.
 *
 * **Formats.** Whatever the tagging library understands: MP3, FLAC, Ogg, MP4/M4A, WMA, WAV. Anything
 * else is reported rather than silently skipped, because a file that cannot be tagged is exactly the
 * case where the user needs to be told the truth.
 */
class MetadataWriter(private val context: Context) {

    sealed interface Result {
        /** [written] files carry the new tags; [failures] describes the ones that did not. */
        data class Done(val written: Int, val failures: List<String>) : Result

        data class NeedsConsent(val request: IntentSender) : Result

        data class Failed(val reason: String?) : Result
    }

    private val resolver: ContentResolver get() = context.contentResolver

    fun write(uris: List<Uri>, tags: Tags): Result {
        if (uris.isEmpty() || tags.isEmpty) return Result.Done(0, emptyList())

        // Ask for write access to everything first, so the consent dialog appears once rather than
        // after the first file has already been rewritten.
        checkWritable(uris)?.let { return it }

        var written = 0
        val failures = mutableListOf<String>()
        val scanned = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
            runCatching { writeOne(uri, name, tags) }
                .onSuccess {
                    written++
                    pathOf(uri)?.let(scanned::add)
                }
                .onFailure { error ->
                    Log.w(Tag, "Cannot tag $name", error)
                    failures += "$name: ${error.message ?: error.javaClass.simpleName}"
                }
        }

        // The store re-reads a file it handed out a writable descriptor for, but not always
        // immediately, and the app's own lists come from the store. Asking explicitly makes the
        // change appear on the page the user is looking at rather than a minute later.
        if (scanned.isNotEmpty()) rescan(scanned)
        return Result.Done(written, failures)
    }

    /**
     * The write itself: copy out, tag the copy, copy back over the original.
     *
     * Not tagged in place through the descriptor because the library needs a seekable file it can
     * grow, and a content Uri is not one. The copy lives in the cache directory and is deleted
     * whatever happens; audio files are a few megabytes, and this is the price of being allowed to
     * touch them at all.
     */
    private fun writeOne(uri: Uri, name: String, tags: Tags) {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension.isNotEmpty()) { "no file extension" }

        val temp = File.createTempFile("tagging", ".$extension", context.cacheDir)
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot read the file" }
                temp.outputStream().use { input.copyTo(it) }
            }

            val audio = AudioFileIO.read(temp)
            val tag = audio.tagOrCreateAndSetDefault
            tags.title?.let { tag.put(FieldKey.TITLE, it) }
            tags.artist?.let {
                tag.put(FieldKey.ARTIST, it)
                // Album artist too: a compilation edited by album would otherwise keep grouping by
                // whatever the old artist was in players that read that field first.
                tag.put(FieldKey.ALBUM_ARTIST, it)
            }
            tags.album?.let { tag.put(FieldKey.ALBUM, it) }
            tags.year?.let { tag.put(FieldKey.YEAR, it) }
            tags.genre?.let { tag.put(FieldKey.GENRE, it) }
            audio.commit()

            // "rwt" truncates, which matters: the tagged file is a different length, and writing a
            // shorter one over a longer one without truncating leaves the tail of the old file behind.
            resolver.openFileDescriptor(uri, "rwt").use { descriptor ->
                requireNotNull(descriptor) { "cannot open the file for writing" }
                java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                    temp.inputStream().use { it.copyTo(output) }
                }
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * Writes one field, and takes the file's *other* spellings of that same field with it.
     *
     * A tagging library writes each field under one canonical key — for a Vorbis comment (FLAC, Ogg)
     * the album artist is `ALBUMARTIST` — and leaves alone any other key the file happens to carry.
     * Real files carry plenty: taggers have written `ALBUM ARTIST` and `ALBUM_ARTIST` for as long as
     * there have been taggers, and a file that has been through two of them has both. Nothing is
     * wrong with that until something writes one of them.
     *
     * **And the stale one wins.** MediaStore walks the comment list and lets each recognised key
     * overwrite the last, so whichever spelling sits later in the file is the one the media database
     * ends up with. Measured on an API 33 emulator, one file per row, the canonical key written
     * first:
     *
     * | also in the file | what the store reports |
     * |---|---|
     * | `ENSEMBLE` | the canonical one — `ENSEMBLE` is not read at all |
     * | `ALBUM ARTIST` | **the stale one** |
     * | `ALBUM_ARTIST` | **the stale one** |
     * | `YEAR` beside `DATE` | **the stale one** |
     *
     * So an edit of such a file wrote the right value into the right key and *nothing on screen
     * changed* — reported from a real library as "the album's metadata will not edit, the changes
     * simply are not saved", against a FLAC rip whose files carried `ENSEMBLE`, `ALBUM ARTIST` and
     * `ALBUMARTIST` at once. The artist line changed (`ARTIST` has no rival spelling) while the album
     * artist did not, which is the giveaway.
     *
     * The rivals are therefore deleted rather than left to disagree: they are the same field under an
     * older spelling, and the value they held is the one being replaced. Only spellings that are
     * unambiguously the same field are listed — `ENSEMBLE` is a Vorbis field of its own (an orchestra
     * is not an album artist), and it is not read by the store anyway, so it is left where it is.
     *
     * Matching ignores case because Vorbis comment keys do, and the delete is by the id as the file
     * spells it. A tag format with no such key — every ID3 and MP4 field is addressed by a four-byte
     * id — simply has nothing to match.
     */
    private fun org.jaudiotagger.tag.Tag.put(key: FieldKey, value: String) {
        setField(key, value)
        val rivals = Shadowing[key] ?: return
        // Collected before anything is deleted: the iterator is over the tag's own field list.
        val doomed = buildSet {
            val fields = getFields()
            while (fields.hasNext()) {
                val id = fields.next().id
                if (rivals.any { it.equals(id, ignoreCase = true) }) add(id)
            }
        }
        doomed.forEach { runCatching { deleteField(it) } }
    }

    /**
     * Returns non-null when the caller has something to do before any writing can happen — the
     * consent dialog, or a plain failure.
     */
    private fun checkWritable(uris: List<Uri>): Result? = try {
        uris.forEach { uri ->
            resolver.openFileDescriptor(uri, "rw")?.close()
                ?: return Result.Failed("cannot open the file for writing")
        }
        null
    } catch (e: SecurityException) {
        consentFor(uris, e)?.let { Result.NeedsConsent(it) }
            ?: Result.Failed(e.message).also { Log.w(Tag, "No write access", e) }
    } catch (e: java.io.IOException) {
        Log.w(Tag, "No write access", e)
        Result.Failed(e.message)
    }

    private fun consentFor(uris: List<Uri>, error: SecurityException): IntentSender? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            MediaStore.createWriteRequest(resolver, uris).intentSender
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
        // Below Android 10 there is no dialog: the write needs WRITE_EXTERNAL_STORAGE, which the app
        // asks for at launch on those versions precisely so this page can work.
        else -> null
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun pathOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /**
     * Asks the media scanner to read these files again, so the store carries the new tags.
     *
     * The old [MediaScannerConnection] rather than `MediaStore.scanFile`, which is not public API in
     * every platform version the app supports. Per-file, so nothing else on the device is re-indexed:
     * a full volume scan for a four-track EP would be rude.
     */
    private fun rescan(paths: List<String>) {
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    private companion object {
        const val Tag = "MetadataWriter"

        /** Other spellings of a field that would otherwise outlive — and outrank — the one written. */
        val Shadowing: Map<FieldKey, List<String>> = mapOf(
            FieldKey.ALBUM_ARTIST to listOf("ALBUM ARTIST", "ALBUM_ARTIST"),
            FieldKey.YEAR to listOf("YEAR")
        )
    }
}
