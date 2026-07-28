package com.metromusic.data.lastfm

import android.content.Context
import android.util.Log
import com.metromusic.core.Connectivity
import com.metromusic.data.library.LibraryRepository
import com.metromusic.data.model.Library
import com.metromusic.data.store.JsonStore
import com.metromusic.data.store.SettingsStore
import com.metromusic.data.store.StatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer

/** One love, or un-love, still owed to Last.fm. */
@Serializable
data class PendingLove(
    val artist: String,
    val title: String,
    val loved: Boolean
)

/** How a track is spelled: what Last.fm has to be sent, since the folded key is one-way. */
data class LovedName(val artist: String, val title: String)

/**
 * What the two sides agreed on last time, and what is still owed.
 *
 * [synced] is the whole basis of the merge: without a record of the previous agreement there is no
 * way to tell a track *added* here from one *removed* there, and a sync that cannot tell those apart
 * either resurrects every love you have ever removed or removes every favourite you have ever added.
 */
@Serializable
data class LoveState(
    val synced: Set<String> = emptySet(),
    val pending: List<PendingLove> = emptyList(),
    /** When the last full reconciliation finished, for the settings page to show. */
    val lastSyncedAtMs: Long = 0L
) {
    /** True before the first reconciliation — the state in which a run is a union of both sides. */
    val isFresh: Boolean get() = lastSyncedAtMs == 0L
}

/**
 * Keeps favourites and Last.fm's loved tracks the same, in both directions.
 *
 * Opt-in (`Settings.syncLoves`), and off by default for a reason worth stating: scrobbling only ever
 * adds to a profile, while this can take a love *off* the website because the track was un-favourited
 * here. Nobody's years of loves should be reconciled against a fresh install without being asked.
 *
 * **It is a three-way merge, not a copy.** Each run compares three sets: the favourites here, the
 * loved tracks there, and [LoveState.synced] — what the two agreed on when the last run finished. A
 * side that differs from that baseline has *changed*, and the change is what gets propagated; a side
 * that matches it has said nothing and is left alone. Where both changed the same way there is nothing
 * to do. Opposite changes to one track cannot arise from an agreed baseline — from a state both sides
 * held, each side can only remove it or leave it — which is precisely why the baseline is kept rather
 * than the sync guessing from two sets and a rule.
 *
 * **The first run is a union**, since with nothing on record nothing counts as removed: every
 * favourite here is loved there, every love there becomes a favourite here, and neither side loses
 * anything. That is also what the settings page's "sync from scratch" goes back to.
 *
 * **Only the music on this device is in scope, and that is the load-bearing rule.** Every comparison
 * is restricted to keys the library can name ([keysOfLibrary]); a track loved on Last.fm that is not
 * on the phone is not compared, not favourited, and above all **never un-loved**. Without that
 * restriction the baseline fills up with keys the favourites list is incapable of holding — a
 * favourite is a track on the device — so the very next run reads all of them as "removed here". Seen
 * on the emulator against a real account with seven hundred loves and none of them in the test
 * library: the second pass queued 699 un-loves. The same rule covers a file that is deleted later:
 * it drops out of scope, so removing music from the phone never removes a love from the profile.
 *
 * **Local changes do not need the profile.** What was favourited or un-favourited here is a difference
 * from the baseline alone, so it is sent as it happens; the profile is only read to learn what changed
 * *there*, and that is rate-limited to [PullIntervalMs] rather than done on every tap of a heart —
 * a loved-tracks list is paged and thousands of entries long on an old account.
 *
 * **Tracks are matched by artist and title**, folded to lower case with accents and punctuation
 * stripped ([keyOf]), because Last.fm has no idea what a MediaStore id is and its own spelling comes
 * from whatever was scrobbled. That is also the limit of the feature: a track whose tags disagree with
 * the profile is two different tracks as far as this is concerned.
 *
 * **Nothing is lost to being offline.** A change is queued in `loves.json` and sent when it can be, on
 * the same triggers the scrobbler uses — a validated network appearing, credentials appearing, and a
 * backing-off timer. A key still queued is held out of the remote comparison until it has gone up, or
 * the un-sent change would read as the far side disagreeing and be undone here on the next run.
 */
class LovesSync(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val stats: StatsStore,
    private val library: LibraryRepository,
    private val connectivity: Connectivity
) {
    private val store = JsonStore(
        file = File(context.filesDir, "loves.json"),
        serializer = LoveState.serializer(),
        defaultValue = LoveState(),
        scope = scope
    )

    /** For the Last.fm settings page: what is owed, and when the last reconciliation finished. */
    val state: StateFlow<LoveState> = store.state

    private val syncMutex = Mutex()
    private var retryJob: Job? = null
    private var retryDelayMs = FirstRetryMs
    private var lastPullAtMs = 0L

    private val client: LastFmClient
        get() = settings.settings.value.let { LastFmClient(it.lastFmKey(), it.lastFmSecret()) }

    /** The credentials this needs, or null when the feature is off or nobody is signed in. */
    private val account: Account?
        get() = settings.settings.value.let { current ->
            if (!current.syncLoves) return null
            val key = current.lastfmSessionKey?.takeIf { it.isNotBlank() } ?: return null
            val user = current.lastfmUser?.takeIf { it.isNotBlank() } ?: return null
            Account(user, key)
        }

    private data class Account(val user: String, val sessionKey: String)

    /**
     * Starts watching for the things that mean a sync is worth attempting; call once, from the shell.
     *
     * A favourite being toggled is one of them, and it is handled as *state* rather than as an event —
     * the difference matters, because a heart tapped in a tunnel, or in a process that then died, is
     * still a difference from the baseline whenever this next runs. Nothing depends on having seen the
     * tap.
     */
    fun attach() {
        scope.launch {
            // The library first: a favourite is a MediaStore id, and this needs the artist and title
            // behind it before any of these sets can be built.
            library.loaded.first { it }
            combine(settings.settings, stats.stats) { current, stats ->
                Trigger(
                    enabled = current.syncLoves && !current.lastfmSessionKey.isNullOrBlank(),
                    favourites = stats.favorites
                )
            }
                .distinctUntilChanged()
                .filter { it.enabled }
                .collectLatest {
                    // Settles a burst of taps: `collectLatest` cancels this wait when the next change
                    // arrives, so hearting a whole album is one reconciliation rather than twelve — and
                    // a heart turned on and straight back off differs from the baseline by nothing at
                    // all, so it costs no requests whatsoever.
                    delay(SettleMs)
                    sync()
                }
        }
        scope.launch {
            connectivity.online.filter { it }.collect { sync(pull = true) }
        }
    }

    /** What a reconciliation depends on; a change in either is a reason to run one. */
    private data class Trigger(val enabled: Boolean, val favourites: Set<Long>)

    /**
     * Reconciles both sides once. Safe to call often — one at a time, and a run that cannot reach
     * Last.fm changes nothing and asks to be called again later.
     *
     * @param pull read the profile even if it was read recently. The local half always runs.
     */
    fun sync(pull: Boolean = false) {
        val account = account ?: return
        scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                val api = client
                if (!api.configured) return@withLock
                val library = library.library.value
                if (library.isEmpty) return@withLock
                Log.d(Tag, "reconciling as ${account.user} (pull=$pull)")

                queueLocalChanges(library, keysOfLibrary(library))
                if (!push(api, account)) return@withLock

                val now = System.currentTimeMillis()
                val due = pull || store.state.value.isFresh ||
                    now - lastPullAtMs >= PullIntervalMs
                if (due && pull(api, account, library)) lastPullAtMs = now
            }
        }
    }

    /**
     * Works out what changed *here* since the baseline and queues it, without asking Last.fm anything.
     *
     * The baseline moves with the queue rather than with the send: a key goes into [LoveState.pending]
     * and into [LoveState.synced] at the same moment, and [push] only ever removes it from the queue.
     * That is what makes the queue the record of what is owed, and it is why [pull] must ignore keys
     * that are still in it.
     */
    private fun queueLocalChanges(library: Library, scope: Map<String, LovedName>) {
        val state = store.state.value
        val local = favouriteKeys(library)
        val owed = state.pending.map { keyOf(it.artist, it.title) }.toSet()

        // In scope only: the baseline may name a track that has since left the device, and an absent
        // file is not a removed favourite.
        val baseline = state.synced.filter { it in scope }
        val added = local.keys - state.synced - owed
        val removed = baseline - local.keys - owed
        if (added.isEmpty() && removed.isEmpty()) return

        val queued = ArrayList<PendingLove>(added.size + removed.size)
        added.forEach { key -> scope[key]?.let { queued += PendingLove(it.artist, it.title, true) } }
        removed.forEach { key -> scope[key]?.let { queued += PendingLove(it.artist, it.title, false) } }

        Log.d(Tag, "queueing ${added.size} love(s) and ${removed.size} un-love(s) from here")
        store.update {
            it.copy(
                pending = it.pending + queued,
                synced = it.synced + added - removed
            )
        }
    }

    /**
     * Sends what is owed. Returns false when the caller should stop for now — a failed send means the
     * network is not there, and the read that would follow it could only fail too.
     */
    private fun push(api: LastFmClient, account: Account): Boolean {
        while (true) {
            val owed = store.state.value.pending.firstOrNull() ?: break
            when (api.love(account.sessionKey, owed.artist, owed.title, owed.loved)) {
                LastFmClient.Submission.Sent -> {
                    store.update { it.copy(pending = it.pending.drop(1)) }
                    retryDelayMs = FirstRetryMs
                }
                // A track Last.fm does not have, or a request it will refuse however often it is
                // repeated. Dropping it is the only way the queue behind it ever moves.
                LastFmClient.Submission.Refused -> {
                    Log.d(Tag, "Last.fm refused ${owed.artist} — ${owed.title}")
                    store.update { it.copy(pending = it.pending.drop(1)) }
                }
                LastFmClient.Submission.Retry -> {
                    scheduleRetry()
                    return false
                }
                // Keep everything: signing in again is exactly what will send it.
                LastFmClient.Submission.Unauthorised -> {
                    Log.w(Tag, "session rejected; signing out and keeping what is queued")
                    settings.setLastfmSession(null, null)
                    return false
                }
            }
        }
        return true
    }

    /**
     * Reads the profile's loved tracks and applies what changed *there*. Returns false when the read
     * did not get through, so the caller does not count it as having happened.
     */
    private suspend fun pull(api: LastFmClient, account: Account, library: Library): Boolean {
        // Null is "could not ask", and it must never be read as "nothing is loved" — an empty list
        // taken from a captive portal would un-favourite the whole library on the spot.
        val loved = api.lovedTracks(account.user) ?: run {
            Log.d(Tag, "could not read ${account.user}'s loved tracks; will ask again later")
            scheduleRetry()
            return false
        }
        Log.d(Tag, "${account.user} has ${loved.size} loved track(s)")
        retryDelayMs = FirstRetryMs

        val state = store.state.value
        val owed = state.pending.map { keyOf(it.artist, it.title) }.toSet()
        val local = favouriteKeys(library)
        val ids = idsOfLibrary(library)
        val remote = HashSet<String>(loved.size)
        loved.forEach { remote += keyOf(it.artist, it.title) }

        val lovedHere = ArrayList<Long>()
        val unlovedHere = ArrayList<Long>()

        // The device's own music is the whole of what this compares — see the class comment. A love
        // for a track that is not here says nothing about a favourite that cannot exist.
        ids.keys.forEach { key ->
            if (key in owed) return@forEach
            val here = key in local
            val there = key in remote
            if (here == there) return@forEach
            val id = ids.getValue(key)

            if (there) {
                // Loved on the profile and not a favourite here. Either it was loved there since the
                // baseline, or it was un-favourited here — and the second case cannot reach this point,
                // because un-favouriting here queued an un-love and the key would be owed.
                lovedHere += id
            } else if (key in state.synced) {
                // A favourite here that the profile agreed on and no longer has: un-loved there, on the
                // website or in another client. Follow it. A key the baseline never had is a favourite
                // this run is about to send, not one to undo.
                unlovedHere += id
            }
        }

        if (lovedHere.isNotEmpty() || unlovedHere.isNotEmpty()) {
            // Back on the main thread's side of the store: these end up in the same JSON file the UI
            // reads, and the update itself is cheap.
            withContext(Dispatchers.Main.immediate) {
                lovedHere.forEach { stats.setFavorite(it, true) }
                unlovedHere.forEach { stats.setFavorite(it, false) }
            }
            Log.d(Tag, "applied here: +${lovedHere.size} -${unlovedHere.size} favourite(s)")
        }

        // What the two sides now agree on, which is only ever said about tracks in scope:
        //  - everything the profile has that is also on this device,
        //  - keys still queued, whose answer this run was not entitled to touch,
        //  - and whatever the baseline says about music that is not here, left exactly as it was.
        val settled = HashSet<String>(ids.size)
        ids.keys.forEach { if (it in remote) settled += it }
        settled += state.synced.filter { it in owed || it !in ids }
        settled += local.keys.filter { it in owed }
        store.update {
            it.copy(synced = settled, lastSyncedAtMs = System.currentTimeMillis())
        }
        return true
    }

    /** The folded key of every favourite that is still on the device, and how it is spelled. */
    private fun favouriteKeys(library: Library): Map<String, LovedName> {
        val favourites = stats.stats.value.favorites
        val keys = HashMap<String, LovedName>(favourites.size)
        favourites.forEach { id ->
            val track = library.track(id) ?: return@forEach
            keys[keyOf(track.artist, track.title)] = LovedName(track.artist, track.title)
        }
        return keys
    }

    /**
     * Every track on the device by folded key, with its spelling — the scope of the whole sync.
     *
     * First spelling wins, so the pairing is stable from run to run instead of depending on the order
     * a scan happened to return.
     */
    private fun keysOfLibrary(library: Library): Map<String, LovedName> {
        val keys = HashMap<String, LovedName>(library.tracks.size)
        library.tracks.forEach { keys.putIfAbsent(keyOf(it.artist, it.title), LovedName(it.artist, it.title)) }
        return keys
    }

    /** The same scope, as the track id to favourite or un-favourite. */
    private fun idsOfLibrary(library: Library): Map<String, Long> {
        val ids = HashMap<String, Long>(library.tracks.size)
        library.tracks.forEach { ids.putIfAbsent(keyOf(it.artist, it.title), it.id) }
        return ids
    }

    /**
     * Comes back later when a send or a read did not get through, waiting twice as long each time.
     *
     * The same shape as the scrobbler's, and the same reasoning: [Connectivity] catches the ordinary
     * case within a second of it happening, and this is for what the system does not report.
     */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MaxRetryMs)
        retryJob = scope.launch {
            delay(wait)
            sync(pull = true)
        }
    }

    /**
     * Forgets the baseline without touching either side's favourites.
     *
     * What "sync from scratch" means: with no agreement on record nothing counts as removed on either
     * side, so the next run is a union — every favourite here is loved there and every love there
     * becomes a favourite here. It is the honest way out of a mismatch, and the reason the settings
     * page offers this rather than a repair nobody could predict.
     */
    fun forgetBaseline() {
        store.update { LoveState(pending = it.pending) }
        sync(pull = true)
    }

    private companion object {
        const val Tag = "LovesSync"
        const val FirstRetryMs = 60_000L
        const val MaxRetryMs = 30 * 60_000L

        /** How long a burst of taps is allowed to settle before anything is sent. */
        const val SettleMs = 3_000L

        /** How often the profile is read for changes made elsewhere. */
        const val PullIntervalMs = 15 * 60_000L

        /**
         * How a track is recognised on both sides: artist and title, lower-cased, accents removed and
         * everything that is not a letter or a digit dropped.
         *
         * Last.fm's spelling comes from whatever was scrobbled first, by any client, so "Björk" and
         * "Bjork", "Song (Remastered 2011)" and "Song (remastered 2011)" have to fold together — the
         * same folding the lyrics search needs, and for the same reason. Bracketed suffixes are
         * deliberately *not* stripped: "(live)" is a different recording, and folding it into the studio
         * take would love the wrong one.
         */
        fun keyOf(artist: String, title: String): String = "${fold(artist)}\u0000${fold(title)}"

        fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }
}
