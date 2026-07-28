package com.metromusic.data.media

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.metromusic.data.model.Album
import com.metromusic.data.model.Library
import com.metromusic.data.model.Track
import com.metromusic.data.model.albumsByArtistIndex
import com.metromusic.data.model.artistIdOf
import com.metromusic.data.model.artistsOf
import com.metromusic.data.model.splitArtists
import com.metromusic.data.model.tracksByAlbumOrdered
import com.metromusic.data.model.tracksByArtistIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's music library out of MediaStore in a single query and folds it into
 * albums and artists on the way through.
 *
 * One cursor pass, no per-track follow-up queries — that keeps a few thousand tracks well
 * under a second and avoids the classic N+1 that makes scanners feel slow.
 */
class MediaStoreScanner(private val context: Context) {

    private val hasGenreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val projection: Array<String>
        get() = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.IS_MUSIC)
            if (hasGenreColumn) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

    /** What the last scan saw and what it left out, so a missing album can be accounted for. */
    data class Report(val rows: Int = 0, val notMusic: Int = 0, val tooShort: Int = 0) {
        val skipped: Int get() = notMusic + tooShort
    }

    var lastReport: Report = Report()
        private set

    /**
     * @param minDurationMs drops ringtones, notification blips and stray voice memos that are
     *   flagged as music. 30s is the usual cut-off.
     * @param splitCredits file a track under every artist its credit names — see [splitArtists].
     */
    suspend fun scan(minDurationMs: Long, splitCredits: Boolean): Library =
        withContext(Dispatchers.IO) {
            val tracks = queryTracks(minDurationMs, splitCredits)
            if (tracks.isEmpty()) return@withContext Library.Empty
            buildLibrary(tracks)
        }

    /**
     * Every audio row the store has, filtered in Kotlin rather than in SQL.
     *
     * The filter used to be a `WHERE is_music != 0 AND duration >= ?`, which is the same rule and not
     * the same behaviour: in SQL a comparison against NULL is NULL, so a row whose duration the store
     * never worked out is dropped by `duration >= 0` — silently, and for the whole album. That is one
     * of the ways a format the system indexes but does not fully understand (ALAC in an .m4a, on the
     * devices whose scanner leaves its duration empty) becomes music the app cannot see while every
     * folder-scanning player finds it. Unknown is not the same as zero: here a row is only dropped for
     * a duration it actually has, and only `is_music` that is explicitly 0 counts as "not music".
     *
     * The cost is reading the ringtones too — a few hundred rows — and the gain is [Report], which is
     * what the library page shows so a missing track can be accounted for instead of guessed at.
     */
    private fun queryTracks(minDurationMs: Long, splitCredits: Boolean): List<Track> {
        val cursor: Cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        ) ?: return emptyList()

        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val musicCol = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
            val genreCol = if (hasGenreColumn) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }

            var notMusic = 0
            var tooShort = 0
            val result = ArrayList<Track>(c.count)
            while (c.moveToNext()) {
                // Explicitly not music — a ringtone, an alarm, a notification. A row that says
                // nothing on the subject is kept: some devices leave the flag unset for formats
                // their scanner only half understands.
                if (musicCol >= 0 && !c.isNull(musicCol) && c.getInt(musicCol) == 0) {
                    notMusic++
                    continue
                }
                val id = c.getLong(idCol)
                // Zero or missing means "the store does not know", not "an empty file" — and where it
                // does not know, the container does: see [TrackDuration]. Without this an ALAC album
                // is a list of rows with a blank where every other row has a number, and it adds up
                // to less than it is. Only ever reached for the rows the store left empty.
                var duration = if (c.isNull(durationCol)) 0L else c.getLong(durationCol)
                if (duration <= 0L) duration = TrackDuration.measure(context, id)
                if (duration > 0L && duration < minDurationMs) {
                    tooShort++
                    continue
                }
                val credit = c.getString(artistCol)?.takeUnless { it == MediaStoreUnknown }
                    ?: UnknownArtist
                // Every artist the credit names, so a guest gets one section rather than the star
                // getting one per guest. Off, it is the credit as written and nothing else, which is
                // what a library of "Earth, Wind & Fire" wants.
                val artistNames = (if (splitCredits) splitArtists(credit) else listOf(credit))
                    .ifEmpty { listOf(credit) }
                result += Track(
                    id = id,
                    title = c.getString(titleCol) ?: UnknownTitle,
                    artist = credit,
                    artistId = artistIdOf(artistNames.first()),
                    artistNames = artistNames,
                    album = c.getString(albumCol)?.takeUnless { it == MediaStoreUnknown }
                        ?: UnknownAlbum,
                    albumId = c.getLong(albumIdCol),
                    durationMs = duration,
                    trackNo = c.getInt(trackCol),
                    year = c.getInt(yearCol),
                    dateAdded = c.getLong(addedCol),
                    genre = if (genreCol >= 0) c.getString(genreCol)?.takeIf { it.isNotBlank() } else null
                )
            }
            lastReport = Report(rows = c.count, notMusic = notMusic, tooShort = tooShort)
            result
        }
    }

    private fun buildLibrary(tracks: List<Track>): Library {
        val byTitle = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        // Album track lists read as a track listing — see AlbumTrackOrder, which every rebuild of
        // these indices has to use as well.
        val tracksByAlbum = tracksByAlbumOrdered(tracks)

        val albums = tracksByAlbum.map { (albumId, albumTracks) ->
            val first = albumTracks.first()
            Album(
                id = albumId,
                title = first.album,
                artist = first.artist,
                artistId = first.artistId,
                // Everyone its tracks credit, in the order the record introduces them, so an album
                // with one guest track is on that guest's page too — and a compilation is on all of
                // theirs, which is the only useful answer for a compilation.
                artistNames = albumTracks.flatMap { it.artistNames }.distinct(),
                year = albumTracks.maxOf { it.year },
                trackCount = albumTracks.size,
                dateAdded = albumTracks.maxOf { it.dateAdded },
                representativeTrackId = first.id
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        val tracksByArtist = tracksByArtistIndex(tracks)
        val albumsByArtist = albumsByArtistIndex(albums)
        val artists = artistsOf(tracksByArtist, albumsByArtist)

        val genres = tracks.mapNotNull { it.genre }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        return Library(
            tracks = byTitle,
            albums = albums,
            artists = artists,
            genres = genres,
            tracksById = tracks.associateBy { it.id },
            albumsById = albums.associateBy { it.id },
            tracksByAlbum = tracksByAlbum,
            tracksByArtist = tracksByArtist,
            albumsByArtist = albumsByArtist
        )
    }

    private companion object {
        /** MediaStore's literal placeholder for missing tags. */
        const val MediaStoreUnknown = "<unknown>"
        const val UnknownTitle = "unknown title"
        const val UnknownArtist = "unknown artist"
        const val UnknownAlbum = "unknown album"
    }
}
