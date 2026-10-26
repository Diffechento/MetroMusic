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
import com.metromusic.data.model.mergingArtistSpellings
import com.metromusic.data.model.tracksByAlbumOrdered
import com.metromusic.data.model.tracksByArtistIndex
import com.metromusic.data.model.underOneArtist
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

    /**
     * The album artist column, on the platforms that publish one.
     *
     * `ALBUM_ARTIST` became public API at API 30, the same release as `GENRE`, and asking a provider
     * for a column it does not publish is an `IllegalArgumentException` rather than a null — so this
     * is gated exactly as the genre column is, and below it an album is filed under its first track's
     * credit as it always was.
     */
    private val hasAlbumArtistColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

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
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            if (hasGenreColumn) add(MediaStore.Audio.Media.GENRE)
            if (hasAlbumArtistColumn) add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }.toTypedArray()

    /** What the last scan saw and what it left out, so a missing album can be accounted for. */
    data class Report(
        val rows: Int = 0,
        val notMusic: Int = 0,
        val tooShort: Int = 0,
        /** Files read out of the library folders, MediaStore's own rows not counted again. */
        val fromFolders: Int = 0
    ) {
        val skipped: Int get() = notMusic + tooShort
    }

    var lastReport: Report = Report()
        private set

    /**
     * @param minDurationMs drops ringtones, notification blips and stray voice memos that are
     *   flagged as music. 30s is the usual cut-off.
     * @param splitCredits file a track under every artist its credit names — see
     *   [com.metromusic.data.model.splitArtists].
     * @param useAlbumArtist read the album artist tag and let it say who a record is by.
     * @param albumArtistOnly file a track under one artist out of its album artist tag rather than
     *   under everyone its credit names, so the guests on a track are not artists of their own — see
     *   [com.metromusic.data.store.Settings.artistsFromAlbumArtist].
     * @param preferKnownArtist which of those names wins: the one with the most records here rather
     *   than the one written first. Means nothing without [albumArtistOnly].
     * @param fixArtistDoubling treat `Blink 182` and `Blink-182` as one artist — see
     *   [mergingArtistSpellings].
     * @param extra what the library folders hold that the store does not — see [FolderScanner].
     *   Filed by the same rules and after the same duration cut, so an album split between the phone
     *   and a USB drive is one album.
     */
    internal suspend fun scan(
        minDurationMs: Long,
        splitCredits: Boolean,
        useAlbumArtist: Boolean,
        albumArtistOnly: Boolean,
        preferKnownArtist: Boolean = false,
        fixArtistDoubling: Boolean = true,
        extra: List<RawTrack> = emptyList()
    ): Library = withContext(Dispatchers.IO) {
        val rules = FilingRules(splitCredits, useAlbumArtist, albumArtistOnly)
        val undated = ArrayList<TrackYear.Request>()
        val stored = queryTracks(minDurationMs, rules, undated)
        val kept = extra.filter { it.durationMs <= 0L || it.durationMs >= minDurationMs }
        lastReport = lastReport.copy(
            tooShort = lastReport.tooShort + (extra.size - kept.size),
            fromFolders = kept.size
        )
        val queried = stored + kept.map { it.toTrack(rules) }
        if (queried.isEmpty()) return@withContext Library.Empty
        val scanned = if (undated.isEmpty()) {
            queried
        } else {
            val years = TrackYear.resolve(context, undated)
            queried.map { track -> years[track.id]?.let { track.copy(year = it) } ?: track }
        }
        // Spellings first, so the count behind [underOneArtist] sees one artist where the files
        // wrote two, and so the name that survives is chosen once for the whole library.
        val merged = if (fixArtistDoubling) scanned.mergingArtistSpellings() else scanned
        val tracks = if (albumArtistOnly) merged.underOneArtist(preferKnownArtist) else merged
        buildLibrary(tracks, albumArtistOnly)
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
    private fun queryTracks(
        minDurationMs: Long,
        rules: FilingRules,
        undated: MutableList<TrackYear.Request>
    ): List<Track> {
        val cursor: Cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        ) ?: return emptyList<Track>().also { lastReport = Report() }

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
            val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val genreCol = if (hasGenreColumn) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }
            val albumArtistCol = if (hasAlbumArtistColumn && rules.useAlbumArtist) {
                c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
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
                val mediaAlbumId = c.getLong(albumIdCol)
                // The store reads `YEAR` and not `DATE` out of a Vorbis comment, and not `TDRC` out
                // of ID3v2.4 — the standard field in both — so a FLAC library tagged by the book has
                // no years at all. Where it left 0 and the format is one of those, the tag header is
                // read after the cursor is closed, from a cache where it can be: see [TrackYear].
                val year = c.getInt(yearCol)
                if (year <= 0) {
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    if (TrackYear.handles(mime)) {
                        undated += TrackYear.Request(
                            id = id,
                            mime = mime!!,
                            modified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0L,
                            size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        )
                    }
                }
                result += RawTrack(
                    id = id,
                    title = c.getString(titleCol),
                    artist = c.getString(artistCol),
                    albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol) else null,
                    album = c.getString(albumCol),
                    mediaAlbumId = mediaAlbumId,
                    durationMs = duration,
                    trackNo = c.getInt(trackCol),
                    year = year,
                    dateAdded = c.getLong(addedCol),
                    genre = if (genreCol >= 0) c.getString(genreCol) else null
                ).toTrack(rules)
            }
            lastReport = Report(rows = c.count, notMusic = notMusic, tooShort = tooShort)
            result
        }
    }

    private fun buildLibrary(tracks: List<Track>, albumArtistOnly: Boolean = false): Library {
        val byTitle = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        // Album track lists read as a track listing — see AlbumTrackOrder, which every rebuild of
        // these indices has to use as well.
        val tracksByAlbum = tracksByAlbumOrdered(tracks)

        val albums = tracksByAlbum.map { (albumId, albumTracks) ->
            val first = albumTracks.first()
            // Who the record is by. The album artist where the files carry one — the commonest of
            // them, on the genre section's reasoning: one track of twelve tagged differently is a
            // mistake in that track, not a second album artist. Where they carry none this is the
            // first track's credit, which is what it has always been.
            val albumArtist = albumTracks.mapNotNull { it.albumArtist }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            val lead = albumTracks.firstOrNull { it.albumArtist == albumArtist } ?: first
            // The names the album artist splits into, or that track's own credit where the two say
            // the same thing (which is when `albumArtistNames` is left empty). Under `albumArtistOnly`
            // it is the one name the track was actually filed under, so a record does not turn up on
            // the page of a guest whose tracks it no longer shares.
            val leadNames = if (albumArtistOnly) {
                lead.artistNames
            } else {
                lead.albumArtistNames.ifEmpty { lead.artistNames }
            }
            Album(
                id = albumId,
                title = first.album,
                artist = albumArtist ?: first.artist,
                artistId = artistIdOf(leadNames.first()),
                // The album artist, then everyone its tracks credit in the order the record
                // introduces them — so an album with one guest track is on that guest's page too,
                // and a compilation is on all of theirs as well as on its own.
                artistNames = (leadNames + albumTracks.flatMap { it.artistNames }).distinct(),
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
}
