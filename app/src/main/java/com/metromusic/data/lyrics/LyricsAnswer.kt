package com.metromusic.data.lyrics

import com.metromusic.data.model.Track
import com.metromusic.data.store.LyricsSource

/**
 * What asking an online service for a song's words can come back with.
 *
 * Three answers and not two, and the third is the one that matters: **"there are none" and "I could
 * not ask" are different facts and only the first is worth remembering.** A captive portal, a
 * Cloudflare challenge, a tunnel — all of those produce a failure that must not be written down as
 * "this song has no lyrics", because that verdict is permanent and would be applied to the whole
 * library on the strength of one bad morning. Every lookup in here is built around keeping those two
 * apart, and every client returns this rather than a nullable string for exactly that reason.
 */
sealed interface LyricsAnswer {
    /**
     * The words. [synced] says whether they carry timestamps, which only LRCLIB can supply.
     *
     * [complete] is the same distinction one level in, and it exists because a lookup is several
     * requests: flat words can be in hand while the request that would have found *timed* ones was
     * the one that failed. Reporting that as a plain answer makes the caller write "this song has no
     * timings" down for ever on the strength of a tunnel — the very thing [Unavailable] exists to
     * prevent, reappearing inside a success. False means "this is the best of what got through", and
     * the caller may show it but must not remember anything about it.
     */
    data class Found(
        val text: String,
        val synced: Boolean,
        val complete: Boolean = true
    ) : LyricsAnswer

    /** The service answered, and it does not have this song. Safe to remember. */
    data object NotFound : LyricsAnswer

    /** The question never got through. Remember nothing; ask again later. */
    data object Unavailable : LyricsAnswer
}

/**
 * Asks whichever service the user picked.
 *
 * The two are genuinely different products rather than mirrors of each other, which is why this is a
 * choice and not a fallback chain: LRCLIB is a community database of `.lrc` files and is the only one
 * of the two that can answer with *timings*, while Genius is an editorial lyrics site with deeper
 * coverage of obscure and non-English releases and no timings at all, ever. Someone who wants the
 * words to follow the music and someone who wants the words to exist are asking different questions,
 * and neither answer is a degraded version of the other.
 */
fun lookUpLyrics(source: LyricsSource, track: Track): LyricsAnswer = when (source) {
    LyricsSource.LrcLib -> LrcLibClient.lyrics(
        artist = track.artist,
        title = track.title,
        album = track.album,
        durationMs = track.durationMs
    )
    LyricsSource.Genius -> GeniusClient.lyrics(track.artist, track.title)
}
