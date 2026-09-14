package com.metromusic.data.model

import java.text.Normalizer

/**
 * The artists a single credit string names.
 *
 * A tag says `"Kongos"`, `"Daft Punk feat. Todd Edwards"`, `"Sia, David Guetta"` and
 * `"Modeselektor & Thom Yorke"` for what the library should show as four artists and not as four
 * *more* artists: filed under the string as it stands, one performer ends up with a section per
 * guest, none of which is the section you were looking for. So the string is split and the track is
 * filed under each name it mentions — a collaboration appears under everyone who played on it, which
 * is also what happens on the artist pages of every service that has one.
 *
 * **The separators** are the ones that mean "and another artist": a comma, a semicolon, an ampersand,
 * and the feature words (`feat.`, `ft.`, `featuring`, `vs.`). Deliberately *not* `and` or Russian `и`
 * — those are words in band names far too often to be worth the damage, and the tags that use them
 * are rare next to the tags that use the four above.
 *
 * **Where splitting would be wrong**, and what stops it:
 *
 *  - `"Hootie & the Blowfish"`, `"Florence & the Machine"`, `"Earth, Wind & Fire"` — a piece that
 *    begins with `the` is never an artist on its own, so it is glued back onto the one before it.
 *    That saves the first two whole and leaves the third as "Earth" and "Wind & Fire", which is the
 *    price of the rule and the reason it is a setting.
 *  - A piece of one character, or of none, is likewise not a name and is glued back — which is what
 *    keeps `"A. G. Cook"` and a trailing comma from inventing artists.
 *
 * The display credit is never rewritten: [Track.artist] stays exactly as the file says, so a row
 * still reads "Daft Punk feat. Todd Edwards". Only what the library files it under changes.
 */
fun splitArtists(raw: String): List<String> {
    val credit = raw.trim()
    if (credit.isEmpty()) return emptyList()

    val cuts = Separators.findAll(credit)
        // A separator at either end separates nothing; it is punctuation the tagger left behind.
        .filter { it.range.first > 0 && it.range.last < credit.lastIndex }
        .toList()
    if (cuts.isEmpty()) return listOf(credit)

    // Pieces as index ranges into the original, so gluing two back together yields the original
    // text — separator included — rather than something reassembled with a guessed "&".
    val pieces = mutableListOf<IntRange>()
    var start = 0
    for (cut in cuts) {
        pieces += start until cut.range.first
        start = cut.range.last + 1
    }
    pieces += start..credit.lastIndex

    val merged = mutableListOf<IntRange>()
    for (piece in pieces) {
        val text = credit.substring(piece).trim()
        val standalone = text.length > 1 && !text.startsWith(ArticleThe, ignoreCase = true)
        if (!standalone && merged.isNotEmpty()) {
            // Glue onto the piece before it, taking the separator along.
            val previous = merged.removeAt(merged.lastIndex)
            merged += previous.first..piece.last
        } else {
            merged += piece
        }
    }

    // A leading piece that could not stand alone has nothing before it to glue onto, so it swallows
    // the one after instead — "the Beatles, Tony Sheridan" must not start with a piece called "the".
    if (merged.size > 1) {
        val first = credit.substring(merged.first()).trim()
        if (first.length <= 1 || first.startsWith(ArticleThe, ignoreCase = true)) {
            val head = merged.removeAt(0)
            val next = merged.removeAt(0)
            merged.add(0, head.first..next.last)
        }
    }

    // Distinct by the same folding the ids use, so "Sia, SIA" is one artist and the first spelling
    // in the tag is the one that survives.
    val seen = mutableSetOf<String>()
    return merged.mapNotNull { range ->
        // A separator the tagger left dangling ("Sia, ") is punctuation, and an artist called "Sia,"
        // is a second artist for the rest of time.
        val name = credit.substring(range).trim().trim(*Dangling).trim()
        if (name.isEmpty() || !seen.add(fold(name))) null else name
    }
}

/**
 * The id an artist is filed under: a hash of the name rather than MediaStore's `ARTIST_ID`.
 *
 * It has to be derived from the name once artists are split, because half of them are not rows in
 * the media database at all — a guest artist exists only inside somebody else's credit string. A
 * name hash also survives what MediaStore's own ids do not: the ids are handed out again when the
 * media database is rebuilt, so a saved navigation target would come back pointing at a different
 * artist, exactly as [com.metromusic.data.store.HiddenStore] keys by name for the same reason.
 *
 * FNV-1a over the folded name, kept positive. Sixty-four bits over a few thousand artists makes a
 * collision (two artists sharing a page) something that does not happen in practice.
 */
fun artistIdOf(name: String): Long {
    var hash = FnvOffset
    for (char in fold(name)) {
        hash = hash xor char.code.toLong()
        hash *= FnvPrime
    }
    return hash and Long.MAX_VALUE
}

/**
 * The id an album is filed under: a hash of what the tags say, not MediaStore's `ALBUM_ID`.
 *
 * The store's id is not the album. It is computed with the file's *parent directory* as a
 * disambiguator whenever there is no album artist tag — so it really means "this album, in this
 * folder": one record split across two folders came out as two albums, and untagged files made one
 * "unknown album" per folder. Hashing the tags instead makes the folder irrelevant, and — like
 * [artistIdOf], and for the same reason [com.metromusic.data.store.HiddenStore] keys by name —
 * survives the media database being rebuilt and its ids handed out again.
 *
 * The album artist is part of the identity where there is one, so two records that merely share a
 * title ("Greatest Hits") stay apart; where there is none the title stands alone, which is what
 * keeps a compilation whose tracks credit twelve people as one album. The two halves are separated
 * by a NUL so no spelling of one can collide with the other. (Tracks with no album *title* never
 * come here at all — with nothing to hash, the store's per-folder id is the only signal there is,
 * and the scanner keeps it.)
 *
 * [SyntheticAlbumBit] is set on the result so an id built here can never equal a real MediaStore
 * row id, which is what those untagged tracks keep using.
 */
fun albumIdOf(title: String, albumArtist: String?): Long {
    var hash = FnvOffset
    for (char in fold(title) + "\u0000" + fold(albumArtist.orEmpty())) {
        hash = hash xor char.code.toLong()
        hash *= FnvPrime
    }
    return (hash and Long.MAX_VALUE) or SyntheticAlbumBit
}

/**
 * Case, whitespace and how the letters are *encoded* are not part of an artist's identity.
 *
 * The normalisation is the part that is easy to leave out and impossible to see afterwards. One of
 * the owner's files spells `Молодой Платон` with a precomposed `й` (U+0439) and another with `и`
 * followed by a combining breve (U+0438 U+0306); the two strings are not equal, so the library
 * carried two artists with the same name, the same look and one track each. NFC is the form the rest
 * of the world writes, so everything is brought to it before anything is compared.
 */
private fun fold(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase().replace(Whitespace, " ")

/**
 * [fold], and the punctuation *between* the words is not part of the identity either.
 *
 * Only ever used to decide that two spellings are the same artist — never to build an id, because a
 * name has to survive being written down. A hyphen, a dash and a space are the same separator as far
 * as a tagger is concerned: the owner's library holds `Blink 182` (14 tracks), `Blink-182` (99, with
 * the ASCII hyphen) and `Blink‐182` (13, with U+2010) as three artists.
 *
 * Separators collapse to one space rather than to nothing, which is the conservative half of this:
 * `Dr. Dre` and `Dr Dre` become the same artist, `M.I.A.` and `MIA` do not.
 */
internal fun looseFold(name: String): String =
    fold(name).replace(Punctuation, " ").replace(Whitespace, " ").trim()

/**
 * The same artist written two ways is one artist, and the spelling most of the files use is the one
 * that survives.
 *
 * The genres section has had this since the beginning ([mergingGenres]) and artists want it for the
 * same reason and with the same rule: if ninety-nine tracks say `Blink-182` and fourteen say
 * `Blink 182`, the library should say what ninety-nine of them say. Names are rewritten on the
 * *tracks*, before any index is built, so `tracksByArtistIndex` and everything downstream agree
 * without knowing this happened — again as the genres do.
 *
 * The display credit is untouched, as ever: [Track.artist] still reads what the file says.
 */
fun List<Track>.mergingArtistSpellings(): List<Track> {
    // Per loose key, how many tracks each original spelling accounts for. A LinkedHashMap, so the
    // spelling seen first wins a tie rather than whichever one a hash happened to order first.
    val spellings = mutableMapOf<String, MutableMap<String, Int>>()
    for (track in this) {
        for (name in track.artistNames + track.albumArtistNames) {
            spellings.getOrPut(looseFold(name)) { LinkedHashMap() }.merge(name, 1, Int::plus)
        }
    }

    val canonical = mutableMapOf<String, String>()
    for (byName in spellings.values) {
        if (byName.size == 1) continue
        val winner = byName.maxByOrNull { it.value }?.key ?: continue
        for (name in byName.keys) if (name != winner) canonical[name] = winner
    }
    if (canonical.isEmpty()) return this

    fun canon(names: List<String>): List<String> =
        names.map { canonical[it] ?: it }.distinctBy { fold(it) }

    return map { track ->
        val names = canon(track.artistNames)
        val albumNames = canon(track.albumArtistNames)
        if (names == track.artistNames && albumNames == track.albumArtistNames) {
            track
        } else {
            track.copy(
                artistNames = names,
                albumArtistNames = albumNames,
                artistId = artistIdOf(names.first())
            )
        }
    }
}

/**
 * One artist per track, chosen out of the names the track is a candidate for.
 *
 * This is what [com.metromusic.data.store.Settings.artistsFromAlbumArtist] does once the scanner has
 * worked out who a track *could* be filed under. Two ways to choose, and the difference between them
 * is the whole of the second setting:
 *
 *  - **the first name**, which is what the tag says. A tag is written primary-first, so
 *    `"Gorillaz, National Orchestra for Arabic Music, Bashy, Kano"` is a Gorillaz record.
 *  - **[byCatalogue]: the name with the most records of its own**, counted over the whole library by
 *    the first rule. Real tags are not as disciplined as the first rule needs: a library where
 *    `angel vox` is the first name on 47 tracks also has ten tagged `CRASPORE; angel vox`,
 *    `niteboi; angel vox`, `Sibewest; angel vox` — collaborations written the other way round, which
 *    the first rule files under ten artists with one track each. Asking who the library is actually
 *    *about* sends all ten to angel vox. It is a guess on top of the tags rather than what they say,
 *    which is why it is a switch of its own and why it is off.
 *
 * Ties go to the earlier name, so with nothing to choose between them the tag's own order decides.
 */
fun List<Track>.underOneArtist(byCatalogue: Boolean): List<Track> {
    if (isEmpty()) return this
    val chosen: (Track) -> String = if (!byCatalogue) {
        { it.artistNames.first() }
    } else {
        // First pass: how many tracks each name is the *first* name of. Second pass reads it, so the
        // answer does not depend on the order the tracks happen to be in.
        val primaries = mutableMapOf<String, Int>()
        for (track in this) primaries.merge(fold(track.artistNames.first()), 1, Int::plus)
        ({ track -> track.artistNames.maxByOrNull { primaries[fold(it)] ?: 0 } ?: track.artistNames.first() })
    }
    return map { track ->
        val name = chosen(track)
        if (track.artistNames.size == 1 && track.artistNames.first() == name) {
            track
        } else {
            track.copy(artistNames = listOf(name), artistId = artistIdOf(name))
        }
    }
}

private val Separators = Regex(
    """\s*(?:[,;&]|\b(?:feat\.?|ft\.?|featuring|vs\.?)\s)\s*""",
    RegexOption.IGNORE_CASE
)

private val Whitespace = Regex("""\s+""")

/** What [looseFold] treats as one separator: the hyphens, the dashes, the underscore and the dot. */
private val Punctuation = Regex("[-\u2010-\u2015_.]+")

private val Dangling = charArrayOf(',', ';', '&')

private const val ArticleThe = "the "
private const val FnvOffset = -3750763034362895579L // 0xcbf29ce484222325
private const val FnvPrime = 1099511628211L

/** Set on every [albumIdOf] id: still positive, and MediaStore row ids never reach bit 62. */
private const val SyntheticAlbumBit = 1L shl 62
