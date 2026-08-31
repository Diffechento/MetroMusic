package com.metromusic.data.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import com.metromusic.data.store.normalizeGenre

/**
 * One audio file from MediaStore.
 *
 * The content [uri] is derived rather than stored — a Uri per track would be thousands of
 * objects held for the life of the app, and building one costs nothing.
 */
@Immutable
data class Track(
    val id: Long,
    val title: String,
    /** The credit exactly as the file spells it — what a row shows. */
    val artist: String,
    /**
     * The primary artist's id: [artistNames] first, through [artistIdOf].
     *
     * Not MediaStore's `ARTIST_ID` any more. Once a credit is split, most artists are not rows in the
     * media database at all, so the ids have to come from the names — see [splitArtists].
     */
    val artistId: Long,
    /**
     * Every artist this track is filed under: the ones its credit names, followed by the ones its
     * *album artist* tag names that the credit did not.
     *
     * The album artist is in here rather than only on the [Album] because the indices are built from
     * tracks: an album artist who plays on none of the tracks under their own name — "Various
     * Artists", or the band on a record whose every track is credited "band feat. somebody" — would
     * otherwise own albums and no songs, which `artistsOf` cannot even give a page to.
     */
    val artistNames: List<String>,
    /**
     * The album artist tag, as the file spells it, or null where there is none — see
     * [com.metromusic.data.store.Settings.useAlbumArtist] for why there may be none even when the
     * file has one.
     */
    val albumArtist: String? = null,
    /** [albumArtist] split the way [artist] is; empty when there is no album artist. */
    val albumArtistNames: List<String> = emptyList(),
    val album: String,
    /**
     * The album this track is filed under — [albumIdOf] over the tags, not MediaStore's `ALBUM_ID`.
     *
     * The store's id bakes the file's parent directory in whenever there is no album artist tag, so
     * it really means "this album, in this folder": one record split across two folders was two
     * albums, and every folder of untagged files was an "unknown album" of its own. Grouping follows
     * this id, so grouping follows the tags. A track with no album tag at all keeps the store's id —
     * there is nothing to hash, and the folder is the only signal left.
     */
    val albumId: Long,
    /**
     * MediaStore's own `ALBUM_ID`, kept for the one thing it is still right about: asking the store
     * itself, i.e. the legacy `albumart` URI. Never a grouping key.
     */
    val mediaAlbumId: Long,
    val durationMs: Long,
    /** Raw MediaStore value; may be encoded as disc * 1000 + track. */
    val trackNo: Int,
    val year: Int,
    val dateAdded: Long,
    val genre: String?
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Track number without the disc prefix, for display. */
    val displayTrackNo: Int get() = if (trackNo > 1000) trackNo % 1000 else trackNo

    val discNo: Int get() = if (trackNo > 1000) trackNo / 1000 else 1
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    /**
     * Who the record is by: the **album artist** where the files carry one, and the first track's
     * credit where they do not.
     *
     * The distinction is the whole point of reading that tag. A compilation's tracks each credit
     * somebody different, so filing the album under the first of them puts "Now That's What I Call
     * Music" under whoever happens to open it; and a record where every track says "band feat.
     * guest" is by the band, which is the one thing none of its tracks says on its own.
     */
    val artist: String,
    /** Primary artist, as on [Track.artistId] — the album artist's, where there is one. */
    val artistId: Long,
    /**
     * Everyone the record is filed under: the album artist first, then everyone its tracks credit.
     *
     * Both halves matter. Without the first, a compilation is on twenty artists' pages and not on
     * the one it is actually by; without the second, a record with one guest track stops appearing
     * on that guest's page.
     */
    val artistNames: List<String>,
    val year: Int,
    val trackCount: Int,
    val dateAdded: Long,
    /** Any track from this album — needed to ask MediaStore for a thumbnail on API 29+. */
    val representativeTrackId: Long
)

@Immutable
data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int
)

/**
 * The whole library plus the lookups the UI needs.
 *
 * The indices are built once per scan and hold references to the same [Track] objects, so
 * they cost pointers, not copies.
 */
@Immutable
data class Library(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<String> = emptyList(),
    val tracksById: Map<Long, Track> = emptyMap(),
    val albumsById: Map<Long, Album> = emptyMap(),
    val tracksByAlbum: Map<Long, List<Track>> = emptyMap(),
    val tracksByArtist: Map<Long, List<Track>> = emptyMap(),
    val albumsByArtist: Map<Long, List<Album>> = emptyMap()
) {
    val isEmpty: Boolean get() = tracks.isEmpty()

    fun track(id: Long): Track? = tracksById[id]

    fun album(id: Long): Album? = albumsById[id]

    fun tracksOf(album: Album): List<Track> = tracksByAlbum[album.id].orEmpty()

    fun tracksOfArtist(artistId: Long): List<Track> = tracksByArtist[artistId].orEmpty()

    fun albumsOfArtist(artistId: Long): List<Album> = albumsByArtist[artistId].orEmpty()

    fun tracksOfGenre(genre: String): List<Track> = tracks.filter { it.genre == genre }

    /** Resolves stored ids to tracks, silently dropping any that no longer exist. */
    fun resolve(ids: List<Long>): List<Track> = ids.mapNotNull { tracksById[it] }

    companion object {
        val Empty = Library()
    }
}

/**
 * How an album's tracks read: disc, then track number, then title.
 *
 * Defined once and used by the scanner *and* by every transform that rebuilds the indices. It lives
 * here because forgetting it is invisible until you open an album: `groupBy` keeps the order of the
 * list it was given, which is the title-sorted one, so a rebuild silently re-alphabetises every album
 * while the track numbers next to the rows stay right and say so.
 */
val AlbumTrackOrder: Comparator<Track> = compareBy<Track> { it.discNo }
    .thenBy { it.displayTrackNo }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

/** Tracks grouped per album, each album in [AlbumTrackOrder]. */
fun tracksByAlbumOrdered(tracks: List<Track>): Map<Long, List<Track>> =
    tracks.groupBy { it.albumId }.mapValues { (_, list) -> list.sortedWith(AlbumTrackOrder) }

/**
 * Tracks per artist — under **every** artist their credit names, not just the first.
 *
 * `groupBy { it.artistId }` cannot express this: a track credited to two people belongs in two
 * lists, so the index is built by walking the names. Defined here, with [albumsByArtistIndex], for
 * the same reason [AlbumTrackOrder] is: the scanner builds these indices and so does every
 * transform that rebuilds the library ([mergingGenres], [hiding]), and a rebuild that quietly went
 * back to grouping by the primary id would empty every guest artist's page with nothing on screen
 * to say why.
 */
fun tracksByArtistIndex(tracks: List<Track>): Map<Long, List<Track>> {
    val index = mutableMapOf<Long, MutableList<Track>>()
    for (track in tracks) {
        for (name in track.artistNames) {
            index.getOrPut(artistIdOf(name)) { mutableListOf() } += track
        }
    }
    return index.mapValues { (_, list) ->
        list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

/** Albums per artist, newest first, likewise under every artist the album's credit names. */
fun albumsByArtistIndex(albums: List<Album>): Map<Long, List<Album>> {
    val index = mutableMapOf<Long, MutableList<Album>>()
    for (album in albums) {
        for (name in album.artistNames) {
            index.getOrPut(artistIdOf(name)) { mutableListOf() } += album
        }
    }
    return index.mapValues { (_, list) -> list.sortedByDescending { it.year } }
}

/**
 * The artists in a library, one per name any credit mentions, with their counts.
 *
 * The name is taken from the credit of the first track filed under the id, which is the spelling the
 * folding in [artistIdOf] collapsed everything else into.
 */
fun artistsOf(
    tracksByArtist: Map<Long, List<Track>>,
    albumsByArtist: Map<Long, List<Album>>
): List<Artist> = tracksByArtist.mapNotNull { (id, tracks) ->
    val name = tracks.firstNotNullOfOrNull { track ->
        track.artistNames.firstOrNull { artistIdOf(it) == id }
    } ?: return@mapNotNull null
    Artist(
        id = id,
        name = name,
        albumCount = albumsByArtist[id]?.size ?: 0,
        trackCount = tracks.size
    )
}.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/**
 * The same library with genres that are only typed differently treated as one.
 *
 * Real files carry "Electro", "electro" and "electro." and mean the same thing once; a library built
 * straight from the tags shows three sections' worth of the same music. [fixDoubling] folds together
 * everything whose spelling matches once case and edge punctuation are ignored, and [aliases] carries
 * the merges the user made by hand, for the pairs no rule could guess ("Electro" into "Electronic").
 *
 * The surviving name is the *most common* original spelling rather than the first or the prettiest: if
 * forty tracks say "Electronic" and two say "electronic", the library should say what forty of them
 * say. A hand-made merge overrides that, because it is an instruction rather than a guess.
 *
 * Tracks are rewritten rather than mapped at the point of display, so `tracksOfGenre` and the section
 * list agree without either of them knowing this happened.
 */
fun Library.mergingGenres(fixDoubling: Boolean, aliases: Map<String, String>): Library {
    if (!fixDoubling && aliases.isEmpty()) return this

    fun key(genre: String): String =
        if (fixDoubling || aliases.isNotEmpty()) normalizeGenre(genre) else genre

    // How often each spelling occurs, so the winner can be the one people actually typed.
    val counts = mutableMapOf<String, MutableMap<String, Int>>()
    for (track in tracks) {
        val genre = track.genre?.trim().orEmpty()
        if (genre.isEmpty()) continue
        val group = if (fixDoubling) key(genre) else genre
        counts.getOrPut(group) { mutableMapOf() }.merge(genre, 1, Int::plus)
    }

    // The name each group ends up under, following aliases as far as they go.
    val canonical = mutableMapOf<String, String>()
    for ((group, spellings) in counts) {
        val commonest = spellings.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
        var name = commonest
        var hops = 0
        while (hops < AliasHopLimit) {
            val next = aliases[normalizeGenre(name)] ?: break
            if (normalizeGenre(next) == normalizeGenre(name)) break
            name = next
            hops++
        }
        canonical[group] = name
    }

    val retagged = tracks.map { track ->
        val genre = track.genre?.trim().orEmpty()
        if (genre.isEmpty()) return@map track
        val group = if (fixDoubling) key(genre) else genre
        val name = canonical[group] ?: genre
        if (name == track.genre) track else track.copy(genre = name)
    }

    return copy(
        tracks = retagged,
        genres = retagged.mapNotNull { it.genre }.distinct().sorted(),
        tracksById = retagged.associateBy { it.id },
        tracksByAlbum = tracksByAlbumOrdered(retagged),
        tracksByArtist = tracksByArtistIndex(retagged)
    )
}

/** Enough to follow a chain of merges, and few enough that a cycle in the file cannot hang a scan. */
private const val AliasHopLimit = 8

/**
 * The same library with the hidden artists and albums taken out of it, indices and counts rebuilt.
 *
 * Filtering here rather than in each list is what makes hiding mean something: a hidden artist is
 * gone from the artists section, from the albums section, from songs, from search, and from what a
 * playlist resolves to — one rule, applied once, instead of six lists each remembering to check.
 *
 * Two consequences fall out of doing it on the tracks and rebuilding upward, rather than by deleting
 * rows from each list:
 *
 *  - hiding an artist hides their albums and their songs, because none of those have any tracks left;
 *  - an artist whose albums have *all* been hidden one by one disappears too, for the same reason,
 *    without that having to be a rule of its own.
 *
 * Counts are recomputed, so an artist with one of three albums hidden says "2 albums" rather than
 * carrying the number MediaStore gave for the whole discography.
 */
fun Library.hiding(hiddenArtists: Set<String>, hiddenAlbums: Set<String>): Library {
    if (hiddenArtists.isEmpty() && hiddenAlbums.isEmpty()) return this

    // Normalised once; the alternative is a case-insensitive scan of the hidden list per track.
    val artistKeys = hiddenArtists.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val albumKeys = hiddenAlbums.mapTo(mutableSetOf()) { it.trim().lowercase() }

    fun hiddenArtist(name: String): Boolean = name.trim().lowercase() in artistKeys

    // The whole credit *and* the primary name: what the user hid is a name off a list, and a track's
    // credit is often "that name feat. somebody". A hidden *guest* does not take the track with them
    // — see the artists filter at the end.
    fun hidden(artist: String, primary: String?, album: String): Boolean =
        hiddenArtist(artist) ||
            (primary != null && hiddenArtist(primary)) ||
            "${artist.trim()}|${album.trim()}".lowercase() in albumKeys

    val keptTracks = tracks.filterNot {
        hidden(it.artist, it.artistNames.firstOrNull(), it.album)
    }
    // Artists hidden by name whose tracks survive under somebody else — the featured ones.
    val hiddenIds = artists.filter { hiddenArtist(it.name) }.mapTo(mutableSetOf()) { it.id }
    if (keptTracks.size == tracks.size && hiddenIds.isEmpty()) return this

    val trackAlbumIds = keptTracks.mapTo(mutableSetOf()) { it.albumId }
    val tracksByAlbum = tracksByAlbumOrdered(keptTracks)
    val tracksByArtist = tracksByArtistIndex(keptTracks).filterKeys { it !in hiddenIds }

    val keptAlbums = albums
        .filter {
            it.id in trackAlbumIds && !hidden(it.artist, it.artistNames.firstOrNull(), it.title)
        }
        .map { it.copy(trackCount = tracksByAlbum[it.id]?.size ?: 0) }
    val albumsByArtist = albumsByArtistIndex(keptAlbums).filterKeys { it !in hiddenIds }

    // Rebuilt from the surviving tracks with the hidden ids already gone from the indices: hiding a
    // featured artist removes *them* — their section, their page, what a search finds — and leaves the
    // track where it also belongs, under the artist whose record it is.
    val keptArtists = artistsOf(tracksByArtist, albumsByArtist)

    return Library(
        tracks = keptTracks,
        albums = keptAlbums,
        artists = keptArtists,
        genres = keptTracks.mapNotNull { it.genre }.distinct().sorted(),
        tracksById = keptTracks.associateBy { it.id },
        albumsById = keptAlbums.associateBy { it.id },
        tracksByAlbum = tracksByAlbum,
        tracksByArtist = tracksByArtist,
        albumsByArtist = albumsByArtist
    )
}
