package com.metromusic.data.media

import android.net.Uri
import com.metromusic.data.model.Track
import com.metromusic.data.model.albumIdOf
import com.metromusic.data.model.artistIdOf
import com.metromusic.data.model.splitArtists

/**
 * One file's tags as they were read, before the library's rules about artists and albums are
 * applied to them.
 *
 * The two places music comes from — a MediaStore row and a file in a library folder — read their
 * tags in different ways and must then be filed by exactly the same rules, or one album split across
 * the phone and a USB drive becomes two albums and its artist two artists. So both produce this, and
 * [toTrack] is the one place those rules live.
 *
 * Strings are as the source gave them: null or blank where the tag is missing.
 */
internal data class RawTrack(
    val id: Long,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    /** MediaStore's `ALBUM_ID`, or 0 for a file the store does not know. */
    val mediaAlbumId: Long,
    val durationMs: Long,
    /** Disc * 1000 + track, the way MediaStore encodes it, or the track alone. */
    val trackNo: Int,
    val year: Int,
    val dateAdded: Long,
    val genre: String?,
    val source: Uri? = null,
    val folderCover: Uri? = null,
    /** What to call the album when the file has no album tag: the folder it is in. */
    val folderName: String? = null
)

/** How a scan files tracks; see [MediaStoreScanner.scan] for what each one means. */
internal data class FilingRules(
    val splitCredits: Boolean,
    val useAlbumArtist: Boolean,
    val albumArtistOnly: Boolean
)

internal fun RawTrack.toTrack(rules: FilingRules): Track {
    val credit = artist?.trim()?.takeUnless { it.isEmpty() || it == MediaStoreUnknown }
        ?: UnknownArtist
    // Every artist the credit names, so a guest gets one section rather than the star getting one
    // per guest. Off, it is the credit as written and nothing else, which is what a library of
    // "Earth, Wind & Fire" wants.
    val creditNames = (if (rules.splitCredits) splitArtists(credit) else listOf(credit))
        .ifEmpty { listOf(credit) }
    // The album artist, where the tag has one and it says something the credit does not. A file
    // whose album artist repeats its artist — which is most of them — is left exactly as it was, so
    // the common library gains nothing to hold and nothing to draw.
    val albumArtist = if (rules.useAlbumArtist) {
        albumArtist?.trim()?.takeUnless { it.isEmpty() || it == MediaStoreUnknown }
    } else {
        null
    }
    val albumArtistNames = if (albumArtist != null && albumArtist != credit) {
        (if (rules.splitCredits) splitArtists(albumArtist) else listOf(albumArtist))
            .ifEmpty { listOf(albumArtist) }
    } else {
        emptyList()
    }
    // Who the track is filed under.
    //
    // Normally both: the credit's names first — so `artistNames.first()` is the performer the row
    // shows and [Track.artistId] means what it always meant — and then the album artist's, which is
    // how a band that plays on none of its own tracks under its own name still gets a page.
    //
    // With `albumArtistOnly` these are the *candidates* rather than the answer: the album artist
    // tag's names, or the credit's where there is no such tag. Which one of them the track ends up
    // under is [underOneArtist]'s to say, once the whole library has been read — the choice needs
    // counts across all of it.
    //
    // (An album artist that merely repeats the credit leaves `albumArtistNames` empty, for the memory
    // the common library would otherwise hold, and reaches the same names by the same route. And
    // with `splitCredits` off there is only ever one name, so this reads as the tag itself — which is
    // what that switch asks for.)
    val artistNames = when {
        rules.albumArtistOnly -> albumArtistNames.ifEmpty { creditNames }
        albumArtistNames.isEmpty() -> creditNames
        else -> (creditNames + albumArtistNames).distinct()
    }
    val albumTitle = album?.trim()?.takeUnless { it.isEmpty() || it == MediaStoreUnknown }
        // MediaStore fills an untagged file's album with its folder's name, and a file it does
        // not know should not be filed any differently for that.
        ?: folderName?.takeIf { it.isNotBlank() }
    return Track(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: UnknownTitle,
        artist = credit,
        artistId = artistIdOf(artistNames.first()),
        artistNames = artistNames,
        albumArtist = albumArtist,
        albumArtistNames = albumArtistNames,
        album = albumTitle ?: UnknownAlbum,
        // The tags are the album, not the store's row: MediaStore's ALBUM_ID hashes the file's
        // parent directory in whenever there is no album artist tag, which is what made a record
        // split across two folders show as two albums. See [albumIdOf].
        albumId = if (albumTitle != null) albumIdOf(albumTitle, albumArtist) else mediaAlbumId,
        mediaAlbumId = mediaAlbumId,
        durationMs = durationMs,
        trackNo = trackNo,
        year = year,
        dateAdded = dateAdded,
        genre = genre?.takeIf { it.isNotBlank() },
        source = source,
        folderCover = folderCover
    )
}

/** MediaStore's literal placeholder for missing tags. */
internal const val MediaStoreUnknown = "<unknown>"
private const val UnknownTitle = "unknown title"
private const val UnknownArtist = "unknown artist"
private const val UnknownAlbum = "unknown album"
