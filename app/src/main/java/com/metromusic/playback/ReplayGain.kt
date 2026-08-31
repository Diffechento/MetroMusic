package com.metromusic.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.metromusic.BuildConfig
import com.metromusic.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Evens out the loudness between tracks, from the gain the files were tagged with.
 *
 * A library ripped and downloaded over fifteen years is mastered ten decibels apart end to end, and
 * a shuffle through it is a thumb on the volume rocker. ReplayGain is the answer everything that
 * writes tags already agrees on: a number of decibels, measured against a reference loudness, saying
 * how much *this* recording has to be turned down to sit level with everything else.
 *
 * **It can only turn a track down.** A player's volume is a fraction of the output, so a track tagged
 * `+4 dB` — one mastered quieter than the reference — would need amplification this cannot give, and
 * is left where it is. That is the honest half of the deal and the reason the switch is off by
 * default: it flattens the loud, it does not raise the quiet, and a library of nothing but quiet
 * files will hear nothing happen. Amplifying would mean `LoudnessEnhancer` on the effects session,
 * which is a device-dependent effect that clips, and is not worth the two-decibel gain.
 *
 * **The tags are read out of the stream, not out of the file.** ExoPlayer already parses ID3 `TXXX`
 * frames, Vorbis comments, and MP4 freeform atoms on its way to playing anything, and hands them over
 * as [Metadata]; opening every file a second time with jaudiotagger to read four bytes would cost a
 * file open per track change and a path this app is not guaranteed to have (see `MetadataWriter`,
 * which copies a file to the cache to touch it at all). The cost here is nothing at all.
 *
 * This lives with the *player*, in the service, rather than with the controller: volume is what it
 * changes, one process holds the player, and the metadata callbacks only exist on that side of the
 * session. The one other place that writes the volume is the sleep timer's fade, which reads the
 * current value before it starts and puts it back at the end for exactly this reason.
 *
 * @param apply what to do with the volume the tags work out to — `player.volume` in practice, taken
 *   as a lambda so nothing here has to hold a player it is not allowed to touch otherwise.
 */
@OptIn(UnstableApi::class)
class ReplayGain(
    private val settings: SettingsStore,
    scope: CoroutineScope,
    private val apply: (Float) -> Unit
) : Player.Listener {

    /** What the current track's own tags say, in decibels; null where they say nothing. */
    private var trackGainDb: Float? = null

    /** And what its album's do, which is the fallback — see [gainDb]. */
    private var albumGainDb: Float? = null

    init {
        // Turning the switch on has to take effect on what is already playing, and turning it off has
        // to put the volume back — otherwise the setting reads as broken until the next track.
        //
        // On the main thread, which is the player's application thread: an ExoPlayer checks and
        // throws, and the app's own scope is `Dispatchers.Default`. Every other call into [apply]
        // comes from a listener callback and is already on it.
        scope.launch(Dispatchers.Main.immediate) {
            settings.settings
                .map { it.volumeNormalization }
                .distinctUntilChanged()
                .collect { applyVolume() }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new track until its own tags arrive, which they do a moment later and usually before the
        // first sample. Carrying the last track's gain into the next one would be worse than doing
        // nothing at all: the number would be wrong rather than absent.
        trackGainDb = null
        albumGainDb = null
        applyVolume()
    }

    /** ID3 as the metadata renderer delivers it — the usual route for an MP3. */
    override fun onMetadata(metadata: Metadata) {
        if (read(metadata)) applyVolume()
    }

    /**
     * And the container's own metadata, which is where a FLAC's or an Ogg's comments live: they
     * belong to the *format* rather than to a moment in the stream, so they never reach [onMetadata].
     */
    override fun onTracksChanged(tracks: Tracks) {
        var found = false
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                if (read(metadata)) found = true
            }
        }
        if (found) applyVolume()
    }

    /** Reads whatever gains [metadata] holds. Returns whether it held any. */
    private fun read(metadata: Metadata): Boolean {
        var found = false
        for (i in 0 until metadata.length()) {
            val pair = keyValue(metadata.get(i)) ?: continue
            val (key, value) = pair
            when (normalizeKey(key)) {
                "replaygain_track_gain" -> parseGainDb(value)?.let { trackGainDb = it; found = true }
                "replaygain_album_gain" -> parseGainDb(value)?.let { albumGainDb = it; found = true }
                // Opus and newer Vorbis write R128 instead: a fixed-point number of decibels
                // against EBU R128's -23 LUFS, where ReplayGain's reference is about five decibels
                // louder. Without that offset an Opus library plays five decibels below an MP3 one.
                "r128_track_gain" -> parseR128Db(value)?.let { trackGainDb = it; found = true }
                "r128_album_gain" -> parseR128Db(value)?.let { albumGainDb = it; found = true }
            }
        }
        return found
    }

    /** Track gain first: it is the one that makes two tracks in a row sit level, which is the ask. */
    private val gainDb: Float? get() = trackGainDb ?: albumGainDb

    private fun applyVolume() {
        val db = gainDb.takeIf { settings.settings.value.volumeNormalization }
        val volume = volumeFor(db)
        // What was read and what it came to. A volume is not visible from outside the app — the
        // symptom of getting this wrong is "the quiet ones are still quiet", which is also the
        // symptom of the files having no tags at all — so debug builds say which it is. Same
        // reasoning as the EventLogger in [PlaybackService].
        if (BuildConfig.DEBUG) {
            Log.d(LogTag, "track=$trackGainDb album=$albumGainDb -> volume=$volume")
        }
        apply(volume)
    }

    private companion object {

        const val LogTag = "ReplayGain"

        /**
         * One metadata entry as the name-and-value pair it is, whatever format wrote it.
         *
         * Four shapes for the same tag, because four containers spell it four ways: `TXXX` in an
         * MP3's ID3, a freeform `----` atom in an M4A (which media3 reports as an ID3 internal
         * frame), a Vorbis comment in a FLAC, an Ogg Vorbis or an Opus — all three of which come
         * through `VorbisUtil` and so arrive as the one class — and an `mdta` key in an MP4
         * written by something modern.
         */
        fun keyValue(entry: Metadata.Entry): Pair<String, String>? {
            val key: String?
            val value: String?
            when (entry) {
                is TextInformationFrame -> {
                    key = entry.description
                    value = entry.values.firstOrNull()
                }
                is InternalFrame -> {
                    key = entry.description
                    value = entry.text
                }
                is VorbisComment -> {
                    key = entry.key
                    value = entry.value
                }
                is MdtaMetadataEntry -> {
                    key = entry.key
                    value = String(entry.value, Charsets.UTF_8)
                }
                else -> return null
            }
            if (key == null || value == null) return null
            return key to value
        }

        /**
         * A key as it is compared: lower case, and without the domain an MP4 atom carries in front
         * of it (`com.apple.iTunes:replaygain_track_gain`).
         */
        fun normalizeKey(key: String): String = key.substringAfterLast(':').trim().lowercase()

        /**
         * `-7.50 dB`, `+3.2 dB`, `-7.5` — the unit is conventional rather than required, and a
         * tagger that leaves it out means the same thing.
         */
        fun parseGainDb(raw: String): Float? =
            raw.trim().removeSuffix("dB").removeSuffix("DB").removeSuffix("db").trim().toFloatOrNull()

        /** R128 gain is Q7.8 fixed point, and against a reference five decibels quieter. */
        fun parseR128Db(raw: String): Float? =
            raw.trim().toIntOrNull()?.let { it / 256f + R128ReferenceOffsetDb }

        const val R128ReferenceOffsetDb = 5f

        /**
         * Decibels as a fraction of full output, never above it.
         *
         * The clamp is where the "can only turn down" limit is: a positive gain asks for more than
         * the sink can give, so it is taken as "leave this one alone".
         */
        fun volumeFor(db: Float?): Float {
            if (db == null || db.isNaN()) return 1f
            return 10f.pow(db / 20f).coerceIn(0f, 1f)
        }
    }
}
