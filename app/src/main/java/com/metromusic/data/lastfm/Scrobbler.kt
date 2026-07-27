package com.metromusic.data.lastfm

import android.content.Context
import android.util.Log
import com.metromusic.core.Connectivity
import com.metromusic.data.store.JsonStore
import com.metromusic.data.store.SettingsStore
import com.metromusic.playback.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

/** One play waiting to go up, in the shape the API wants it. */
@Serializable
data class PendingScrobble(
    val artist: String,
    val title: String,
    val album: String,
    val durationMs: Long,
    val timestampSeconds: Long
)

@Serializable
data class ScrobbleQueue(val items: List<PendingScrobble> = emptyList())

/**
 * Reports listening to Last.fm.
 *
 * Follows their rules rather than inventing our own: a track counts once it has been *playing* for
 * half its length or four minutes, whichever comes first, and tracks under thirty seconds never
 * count. "Playing" is accumulated from the state flow rather than polled — the position tick only
 * runs while a screen is showing a progress bar, and a scrobbler that forced it to run always would
 * cost battery for the whole session to learn something it can get from pause and resume timestamps.
 *
 * **Everything is scrobbled locally first.** A play is written to `scrobbles.json` the moment it is
 * earned, carrying the wall-clock second the track *started*, and only then is a send attempted — so
 * being offline is not a special case, it is the ordinary path with the send failing. The queue goes
 * up when the network comes back ([Connectivity]), when the app starts, when the user signs in, on a
 * backing-off timer while a send keeps failing, and when the Last.fm settings page is tapped.
 *
 * The timestamp is what makes this worth doing rather than just dropping the play: Last.fm files a
 * scrobble at the time it says, so an underground journey turns up in the right order and at the
 * right hour once the phone surfaces, not as a burst at 19:40. Their rule is that a scrobble must be
 * no older than [MaxAgeSeconds]; older ones are dropped here rather than sent to be ignored.
 */
class Scrobbler(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val connectivity: Connectivity
) {
    private val queue = JsonStore(
        file = File(context.filesDir, "scrobbles.json"),
        serializer = ScrobbleQueue.serializer(),
        defaultValue = ScrobbleQueue(),
        scope = scope
    )

    /** How many plays are waiting — worth showing on the Last.fm settings page. */
    val pending: StateFlow<ScrobbleQueue> = queue.state

    private val sendMutex = Mutex()

    /** Sleeps out a failed send; see [scheduleRetry]. */
    private var retryJob: Job? = null
    private var retryDelayMs = FirstRetryMs

    /**
     * The track being watched, and how much of it has actually been heard.
     *
     * The metadata is held loose rather than as a finished [PendingScrobble] because it *arrives*
     * loose: a media item's duration is unknown at the moment it becomes current and turns up a
     * frame or two later, and the title and artist can follow it. Freezing the scrobble at the
     * transition captured `durationMs = 0`, which made the "half the track or four minutes" test
     * unsatisfiable — nothing was ever scrobbled — and told Last.fm nothing about how long the
     * now-playing entry should last, so it sat there claiming the song was still on.
     */
    private class Watched(
        val trackId: Long,
        var artist: String,
        var title: String,
        var album: String,
        var durationMs: Long,
        val startedAtSeconds: Long,
        var playedMs: Long,
        var playingSince: Long?,
        var submitted: Boolean
    ) {
        fun heard(now: Long): Long = playedMs + (playingSince?.let { now - it } ?: 0L)

        fun scrobble(): PendingScrobble = PendingScrobble(
            artist = artist,
            title = title,
            album = album,
            durationMs = durationMs,
            timestampSeconds = startedAtSeconds
        )
    }

    private var watched: Watched? = null

    /** Sleeps until the current track has earned its scrobble; see [scheduleThreshold]. */
    private var thresholdJob: Job? = null

    private val client: LastFmClient
        get() = settings.settings.value.let { LastFmClient(it.lastFmKey(), it.lastFmSecret()) }

    private val sessionKey: String?
        get() = settings.settings.value.takeIf { it.scrobbleEnabled }?.lastfmSessionKey

    /**
     * Starts watching [states], and starts watching for a chance to empty the queue; call once, from
     * the shell.
     *
     * The two triggers are the two ways a stuck queue becomes an unstuck one. A validated network
     * appearing is the tunnel case, and it is an event rather than a poll — the system knows before
     * any timer would. Credentials appearing is the cold-start and the just-signed-in case: the
     * settings file is read from disk asynchronously, so at the moment `attach` is called there is
     * usually no session key yet and a flush here would be a no-op.
     */
    fun attach(states: Flow<PlayerState>) {
        scope.launch {
            states.collect { onState(it, System.currentTimeMillis()) }
        }
        scope.launch {
            connectivity.online.filter { it }.collect { flush() }
        }
        scope.launch {
            settings.settings
                .map { it.scrobbleEnabled && !it.lastfmSessionKey.isNullOrBlank() }
                .distinctUntilChanged()
                .filter { it }
                .collect { flush() }
        }
    }

    private fun onState(state: PlayerState, now: Long) {
        val current = watched
        val id = state.trackId

        if (current == null || current.trackId != id) {
            // A different track: settle up for the old one before letting go of it.
            if (current != null) finish(current, now)
            watched = if (id == null) {
                null
            } else {
                Watched(
                    trackId = id,
                    artist = state.artist,
                    title = state.title,
                    album = state.album,
                    durationMs = state.durationMs,
                    // Last.fm wants when the play *started*, so it is stamped here rather
                    // than when the threshold is crossed.
                    startedAtSeconds = now / 1000,
                    playedMs = 0L,
                    playingSince = if (state.isPlaying) now else null,
                    submitted = false
                ).also { fresh ->
                    // Only worth announcing once there is something to announce. With no title
                    // yet this would tell Last.fm the user is listening to nothing at all.
                    if (fresh.title.isNotBlank()) announce(fresh)
                    scheduleThreshold(fresh)
                }
            }
            return
        }

        // Same track. Metadata that was not ready at the transition lands here; a duration in
        // particular has to be picked up, both so the scrobble threshold can be computed and so the
        // now-playing entry expires when the song actually ends rather than lingering.
        val wasBlank = current.title.isBlank()
        val gainedDuration = current.durationMs <= 0 && state.durationMs > 0
        if (state.title.isNotBlank()) current.title = state.title
        if (state.artist.isNotBlank()) current.artist = state.artist
        if (state.album.isNotBlank()) current.album = state.album
        if (state.durationMs > 0) current.durationMs = state.durationMs
        if (gainedDuration || (wasBlank && current.title.isNotBlank())) announce(current)

        // Fold pauses and resumes into the total, then see if it has earned a scrobble.
        if (state.isPlaying && current.playingSince == null) {
            current.playingSince = now
            // A now-playing entry expires after the track's length, so one that was paused for a
            // while is gone from the profile by the time playback resumes. Say it again.
            announce(current)
        } else if (!state.isPlaying && current.playingSince != null) {
            current.playedMs += now - current.playingSince!!
            current.playingSince = null
        }
        if (!current.submitted && earned(current, now)) submit(current)
        scheduleThreshold(current)
    }

    /**
     * Submits the scrobble the moment the track has been heard long enough, rather than whenever the
     * player next happens to say something.
     *
     * Needed because playback position is deliberately not part of [PlayerState] — nothing ticks
     * while a track simply plays, so a threshold checked only on state changes was in practice
     * checked when the track *ended*. Last.fm expects the play to arrive as it is earned, and a
     * listener who skips ten seconds after the halfway mark should already have it counted.
     *
     * Rescheduled on every state change and cancelled while paused, so the wait always reflects how
     * much of the track has actually been heard.
     */
    private fun scheduleThreshold(watched: Watched) {
        thresholdJob?.cancel()
        if (watched.submitted || watched.playingSince == null) return
        val duration = watched.durationMs
        if (duration < MinDurationMs) return

        val enough = minOf(duration / 2, FourMinutesMs)
        val remaining = enough - watched.heard(System.currentTimeMillis())
        thresholdJob = scope.launch {
            if (remaining > 0) delay(remaining)
            if (this@Scrobbler.watched === watched && !watched.submitted) submit(watched)
        }
    }

    /** Sends the scrobble as soon as the threshold is passed, not at the end of the track. */
    private fun earned(watched: Watched, now: Long): Boolean {
        val duration = watched.durationMs
        if (duration < MinDurationMs) return false
        val enough = minOf(duration / 2, FourMinutesMs)
        return watched.heard(now) >= enough
    }

    private fun finish(watched: Watched, now: Long) {
        thresholdJob?.cancel()
        watched.playingSince?.let { watched.playedMs += now - it }
        watched.playingSince = null
        if (!watched.submitted && earned(watched, now)) submit(watched)
    }

    /**
     * Records the play locally, then tries to send it.
     *
     * In that order and unconditionally, which is the whole of the offline story: nothing here asks
     * whether there is a network, because the answer would be stale by the time the request went out
     * anyway. Queued while scrobbling is switched off would be a lie of a different kind — those
     * plays were never meant for Last.fm — so that one case does not record.
     */
    private fun submit(watched: Watched) {
        watched.submitted = true
        if (!settings.settings.value.scrobbleEnabled) return
        val play = watched.scrobble()
        Log.d(Tag, "scrobbling ${play.artist} — ${play.title}")
        queue.update { ScrobbleQueue(pruned(it.items + play)) }
        flush()
    }

    /**
     * Drops what Last.fm will not take: plays older than a fortnight, and the oldest of them once
     * there are more than [MaxQueued].
     *
     * Both are about a queue that is never going to drain — an account signed out for a month, a
     * suspended key — quietly becoming the largest file the app owns. A day's listening is about a
     * hundred plays, so the cap is weeks of unsendable history before anything is lost.
     */
    private fun pruned(items: List<PendingScrobble>): List<PendingScrobble> {
        val oldest = System.currentTimeMillis() / 1000 - MaxAgeSeconds
        val fresh = items.filter { it.timestampSeconds >= oldest }
        if (fresh.size != items.size) {
            Log.d(Tag, "dropping ${items.size - fresh.size} play(s) older than 14 days")
        }
        return if (fresh.size > MaxQueued) fresh.takeLast(MaxQueued) else fresh
    }

    private fun announce(watched: Watched) {
        val key = sessionKey ?: return
        val play = watched.scrobble()
        scope.launch(Dispatchers.IO) {
            val api = client
            if (!api.configured) return@launch
            val sent = api.updateNowPlaying(key, play)
            Log.d(Tag, "now playing ${play.title} (${play.durationMs / 1000}s): sent=$sent")
        }
    }

    /**
     * Tries to send everything queued. Safe to call often — one attempt at a time, and anything that
     * does not go up is left in the queue exactly as it was.
     */
    fun flush() {
        val key = sessionKey ?: return
        scope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                val api = client
                if (!api.configured) return@withLock
                queue.update { ScrobbleQueue(pruned(it.items)) }
                while (true) {
                    val batch = queue.state.value.items.take(BatchSize)
                    if (batch.isEmpty()) {
                        retryJob?.cancel()
                        retryDelayMs = FirstRetryMs
                        return@withLock
                    }
                    when (api.scrobble(key, batch)) {
                        LastFmClient.Submission.Sent -> {
                            queue.update { ScrobbleQueue(it.items.drop(batch.size)) }
                            retryDelayMs = FirstRetryMs
                        }
                        // Nobody heard us. Keep the plays, come back later — and back off, because
                        // the common reason is no network at all and the network callback will beat
                        // any timer to the moment that changes.
                        LastFmClient.Submission.Retry -> {
                            Log.d(Tag, "${batch.size} play(s) held back; will retry")
                            scheduleRetry()
                            return@withLock
                        }
                        // The session key is dead. Signing out here is not losing anything — the
                        // queue survives it, the settings page says "sign in", and signing in flushes
                        // the queue as its last step.
                        LastFmClient.Submission.Unauthorised -> {
                            Log.w(Tag, "session rejected; signing out and keeping the queue")
                            settings.setLastfmSession(null, null)
                            return@withLock
                        }
                        // Refused for a reason repeating will not fix. No timer: the next play, the
                        // next launch or the settings page's own tap will try again.
                        LastFmClient.Submission.Refused -> {
                            Log.w(Tag, "Last.fm refused ${batch.size} play(s)")
                            return@withLock
                        }
                    }
                }
            }
        }
    }

    /**
     * Comes back to a failed send later, waiting twice as long each time up to [MaxRetryMs].
     *
     * The backstop rather than the mechanism: [Connectivity] is what catches the ordinary "the
     * tunnel ended" case within a second of it happening. This is for the cases the system does not
     * report — a captive portal that answers every request with a login page, Last.fm itself being
     * down — where the only way to find out is to ask again, and asking every minute for an hour is
     * how a music player becomes a battery complaint.
     */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MaxRetryMs)
        retryJob = scope.launch {
            delay(wait)
            flush()
        }
    }

    fun clearQueue() = queue.update { ScrobbleQueue() }

    private companion object {
        const val Tag = "Scrobbler"

        /** Last.fm's own thresholds. */
        const val MinDurationMs = 30_000L
        const val FourMinutesMs = 4 * 60_000L
        const val BatchSize = 50

        /** Last.fm refuses anything older than a fortnight, so there is no point keeping it. */
        const val MaxAgeSeconds = 14L * 24 * 60 * 60

        /** Roughly three weeks of heavy listening, and about 150 KB of JSON. */
        const val MaxQueued = 5_000

        const val FirstRetryMs = 60_000L
        const val MaxRetryMs = 30 * 60_000L
    }
}
