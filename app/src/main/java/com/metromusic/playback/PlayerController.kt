package com.metromusic.playback

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.concurrent.futures.await
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.metromusic.data.model.Library
import com.metromusic.data.model.Track
import com.metromusic.data.store.PlaybackStateStore
import com.metromusic.data.store.SavedQueue
import com.metromusic.data.store.SettingsStore
import com.metromusic.data.store.StatsStore
import com.metromusic.widget.NowPlayingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's handle on playback.
 *
 * All the UI ever sees is a [PlayerState] flow and a set of commands; the actual player lives
 * in [PlaybackService] and is reached through a [MediaController]. That indirection is what
 * lets playback survive the activity and keeps the notification, lock screen and headset
 * buttons working for free.
 *
 * A [MediaController] is bound to the looper it was built on, so every call here hops to the
 * main thread.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stats: StatsStore,
    private val savedQueue: PlaybackStateStore,
    private val settings: SettingsStore
) {
    private var controller: MediaController? = null
    private val connectMutex = Mutex()

    /**
     * A track by its MediaStore id, filled in by the composition root.
     *
     * Only [playFile] needs it, and only to answer one question: is the file another app just handed
     * us one this library already knows? A property rather than a constructor argument for the reason
     * `ArtworkLoader.albumNames` is one — the library is built above this, so it cannot be passed in
     * from below.
     */
    var trackById: ((Long) -> Track?)? = null

    private val _state = MutableStateFlow(PlayerState.Empty)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<TrackFace>>(emptyList())

    /**
     * The whole queue, in the order the player holds it — what the queue screen draws.
     *
     * A flow of its own rather than a field on [PlayerState], for the same reason the position is one:
     * [PlayerState] is collected by the strip, the player and the scrobbler and is rebuilt on every
     * player event, and hanging a list of every track in the queue off it would allocate that list
     * dozens of times a session for the benefit of one screen that is usually not open. Here the list is
     * rebuilt only when the queue itself changes, which [publish] decides by comparing the ids it has
     * already collected for the saved session.
     *
     * Shuffle does not complicate this: shuffling means rearranging the queue itself ([shuffled]), so
     * the order here is always the order things will play in, and a move or a removal is addressed in
     * the order you can see.
     */
    val queue: StateFlow<List<TrackFace>> = _queue.asStateFlow()

    /** The ids [queue] was last built from, so an unchanged queue is not rebuilt or re-emitted. */
    private var queueIds: List<Long> = emptyList()

    /** Ids of the current queue, so we can map a media item back to a track. */
    private var queueAlbumIds: Map<Long, Long> = emptyMap()

    private val _queued = MutableSharedFlow<QueueNotice>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Something was added to the queue — what the shell puts a banner up for.
     *
     * There is no queue screen, so without this "play next" is a menu item that visibly does nothing:
     * the album you are looking at stays on screen, the song that is playing keeps playing, and
     * whether the tap registered is a mystery until three minutes later. Queueing several albums in a
     * row without any feedback is not something anyone would trust twice.
     */
    val queued: SharedFlow<QueueNotice> = _queued.asSharedFlow()

    private var noticeCount = 0L

    /**
     * Whether the queue is shuffled — the flag the transport draws.
     *
     * media3's own `shuffleModeEnabled` is deliberately left off, and this is the whole of why. It
     * does not touch the queue: it lays a random *traversal* over the order the queue is in, so the
     * list on screen stays as it was while "next" jumps to something unrelated — and when that random
     * order happens to put the current track last, there is no next at all and playback simply stops
     * part way through an album, for no reason anything on screen can explain. Shuffling a queue
     * means shuffling the queue, so that is what this does and the flag is ours to keep.
     */
    private var shuffled = false

    /**
     * The order the queue was in before it was shuffled, as track ids, so the toggle comes back.
     *
     * Ids rather than the queue's own items, for two reasons. A `MediaItem` read back from the
     * session is a *copy* of the one that was put in — the timeline travels as a bundle — so there is
     * no identity to compare; and ids are what survives the process, which is how the order to come
     * back to is still there after a restore. Two entries for one track are interchangeable, being
     * the same track, so matching them up greedily is exact rather than approximate.
     *
     * Null means there is nothing to go back to — the queue was not shuffled, or it has been taken
     * apart since — and turning shuffle off then leaves the order where it is rather than inventing
     * one.
     */
    private var unshuffledIds: List<Long>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new queue is not a play. [play] and [playNext] record their own first track, since
            // this doesn't reliably fire for the first item of a queue that was just set — and a
            // queue restored at startup must not count as having played anything at all, or every
            // launch inflates the play counts and the history of whatever you last listened to.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            // REPEAT means the same track came round again; that still counts as a play.
            val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
            // A file opened from outside the app is not in the library, so there is nothing for a
            // play count to be about and nothing the history section could show — see [playFile].
            if (trackId <= 0) return
            stats.recordPlay(trackId, queueAlbumIds[trackId] ?: -1L)
        }

        /**
         * Somebody outside the app asking the session to shuffle — a system control, a car, an
         * assistant. Nothing in the app does this; media3's flag is the one thing here that another
         * process can reach. The request is answered the way the app answers it, by shuffling the
         * queue, and the flag is put straight back down so the two shuffles cannot compound.
         */
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!shuffleModeEnabled) return
            val player = controller ?: return
            player.shuffleModeEnabled = false
            if (!shuffled) shuffle(player)
        }
    }

    /** Connects to the session if it isn't connected already. Safe to call from anywhere. */
    suspend fun connect(): MediaController? = connectMutex.withLock {
        controller?.let { return it }
        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val newController = MediaController.Builder(context, token).buildAsync().await()
            newController.addListener(listener)
            controller = newController
            publish(newController)
            newController
        }
    }

    // ---- remembering the queue ----

    private var restoreAttempted = false

    /**
     * Puts back the queue the app had when it was last closed, paused and at the start of the track
     * it was on. Called once at startup, after the library scan — the queue is stored as ids and
     * there is nothing to resolve them against until then.
     *
     * Does nothing if there is already something loaded: the playback service outlives the activity,
     * so coming back to a still-running player must not throw its queue away and replace it with a
     * snapshot of itself, and neither must a track the user managed to tap while the scan finished.
     */
    suspend fun restoreLastSession(library: Library) {
        if (restoreAttempted) return
        restoreAttempted = true
        val saved = savedQueue.awaitLoaded()
        if (saved.isEmpty) return
        // The resume threshold is read below, and read a moment early it is the default — off —
        // which would quietly send a podcast back to its first second.
        settings.awaitLoaded()

        val tracks = library.resolve(saved.trackIds)
        if (tracks.isEmpty()) return
        // Match on the id rather than the index: tracks that have since gone shift everything after
        // them, and coming back on the wrong song is worse than coming back on the first one.
        val currentId = saved.trackIds.getOrNull(saved.index)
        val startIndex = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        // The saved moment belongs to the saved track, so it is only used if that is the track the
        // queue comes back on — and only if the track is still long enough under the setting as it
        // stands now, since turning the setting off is asking for tracks to start from the top.
        val startTrack = tracks[startIndex]
        val startPosition = saved.positionMs
            .takeIf { startTrack.id == currentId && keepsPosition(startTrack.durationMs) }
            ?.coerceIn(0L, startTrack.durationMs)
            ?: 0L

        command { player ->
            if (player.mediaItemCount > 0) return@command
            queueAlbumIds = tracks.associate { it.id to it.albumId }
            shuffled = saved.shuffle
            // The queue comes back in the order it was left in, shuffled or not. The order to come
            // *back* to only comes with it if the library still holds exactly the tracks it names:
            // one deleted file and there is nothing to restore to, which is worth saying by leaving
            // the order alone rather than by half-sorting it.
            val savedOrder = saved.unshuffledIds
            unshuffledIds = savedOrder?.takeIf { order ->
                order.sorted() == tracks.map { track -> track.id }.sorted()
            }
            // From the top of the track, not from where it was cut off: nobody wants a song
            // handed back to them from the middle. A podcast is the exception — see [keepsPosition].
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, startPosition)
            player.repeatMode = saved.repeatMode
            // prepare() and *not* play(): buffered, seekable and on the strip, but silent until
            // something is pressed.
            player.prepare()
        }
    }

    /**
     * Snapshots the queue into [PlaybackStateStore]. Main thread, like every other player read.
     *
     * Called from [publish], which is enough for everything but the position: a new queue, a skip,
     * shuffle, repeat, a pause and a seek are all events. A long track that merely plays changes
     * only its position, and that is what [positionSaver] is for.
     */
    private fun remember(player: Player, ids: List<Long>) {
        // An empty player is the state at startup, before the restore has run — saving it there
        // would erase the queue we are about to put back. Emptying the queue on purpose ([stop])
        // clears the store itself.
        if (ids.isEmpty()) return
        // A queue holding something the library does not — a file opened from another app, see
        // [playFile] — cannot be written down, because what is written down is ids to resolve against
        // the library. Saving it would come back as nothing at the next launch *and* throw away the
        // queue the user actually built, so the old one is left where it is instead.
        if (ids.any { it <= 0 }) return
        savedQueue.save(
            SavedQueue(
                trackIds = ids,
                index = player.currentMediaItemIndex.coerceIn(0, ids.lastIndex),
                shuffle = shuffled,
                unshuffledIds = unshuffledIds,
                repeatMode = player.repeatMode,
                positionMs = if (keepsPosition(currentDuration(player))) {
                    player.currentPosition.coerceAtLeast(0L)
                } else {
                    0L
                }
            )
        )
    }

    /**
     * Whether a track of [durationMs] comes back where it was left rather than from the top — the
     * setting for podcasts and audiobooks, off by default.
     */
    private fun keepsPosition(durationMs: Long): Boolean {
        val minutes = settings.settings.value.resumePositionMinutes
        return minutes > 0 && durationMs >= minutes * 60_000L
    }

    /**
     * The current track's length, from the library first.
     *
     * The player only knows a duration once it has prepared the item, and a publish arrives before
     * that — right after a restore, for one. Asking the player alone would read that moment as "not
     * long enough" and write 0 over the position that was just put back.
     */
    private fun currentDuration(player: Player): Long {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        return id?.let { trackById?.invoke(it)?.durationMs }?.takeIf { it > 0 }
            ?: player.duration.coerceAtLeast(0L)
    }

    private var positionSaver: Job? = null

    /**
     * While a long track plays, writes its position down every [PositionSaveMs].
     *
     * Everything else about the queue is saved by the event that changes it, but a podcast playing
     * raises no event for an hour, and a process killed from the recents screen gets no chance to
     * say where it was. So the loss is bounded instead: at most this interval, plus the store's own
     * debounce. Runs only while something that qualifies is actually playing, so a library of songs
     * costs nothing.
     */
    private fun updatePositionSaver(player: Player) {
        val wanted = player.isPlaying && keepsPosition(currentDuration(player))
        if (!wanted) {
            positionSaver?.cancel()
            positionSaver = null
            return
        }
        if (positionSaver?.isActive == true) return
        positionSaver = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(PositionSaveMs)
                val current = controller ?: break
                remember(current, current.queueTrackIds())
            }
        }
    }

    /**
     * Replaces the queue with [tracks] and starts at [startIndex]. This is what every "play"
     * affordance in the app funnels into — a track row, an album, a playlist, shuffle-all.
     *
     * [shuffle] says what the queue should arrive in; left out, it keeps whatever the transport is
     * already set to. That is the answer to "shuffle is on and I tapped a song": you get that song,
     * and the rest of the album behind it in a random order, rather than the mode quietly turning
     * itself off or the tap landing on something else.
     */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        queueAlbumIds = tracks.associate { it.id to it.albumId }
        command { player ->
            val start = startIndex.coerceIn(0, tracks.lastIndex)
            val on = shuffle ?: shuffled
            shuffled = on
            unshuffledIds = if (on) tracks.map { it.id } else null
            val ordered = if (on) shuffleAround(tracks, start) else tracks
            player.setMediaItems(ordered.map { it.toMediaItem() }, if (on) 0 else start, 0L)
            player.prepare()
            player.play()
            // onMediaItemTransition doesn't fire for the very first item of a new queue.
            val first = ordered.first()
            stats.recordPlay(first.id, first.albumId)
        }
    }

    /**
     * The whole of an album, a genre or a playlist in a random order, starting on a random one of
     * them — the shuffle-all button.
     *
     * The random *start* is the point of it. "What is playing first, the rest shuffled behind it" is
     * what shuffling a queue means everywhere else in here, and applied to a list nothing has been
     * played out of yet it would open every shuffled album on that album's own first track. Shuffle
     * is turned on rather than the list being shuffled on the way in, so the transport says what the
     * queue is and turning it off brings the record's own order back.
     */
    fun shuffleAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        play(tracks, tracks.indices.random(), shuffle = true)
    }

    /** [items] with the [start]-th first and everything else behind it in a random order. */
    private fun <T> shuffleAround(items: List<T>, start: Int): List<T> {
        if (items.size < 2) return items
        val rest = items.toMutableList()
        val first = rest.removeAt(start)
        rest.shuffle()
        return listOf(first) + rest
    }

    /**
     * Plays one audio file handed to the app from outside it — a tap in a file manager, an
     * attachment, a download. The whole of what `ACTION_VIEW` amounts to.
     *
     * Where the file is one MediaStore already indexes, and the library has finished reading it, this
     * is an ordinary [play] of that track and behaves like one in every respect: play counts, the
     * queue, the saved session, the artwork. That is the case worth having, and it is why this looks
     * the id up rather than always taking the short road.
     *
     * Everything else — a file from another app's provider, or one whose scan has not landed yet
     * because the app was launched *by* this intent — is played as a queue of one item that the
     * library does not contain. It carries [ExternalTrackId] so that the shell still counts it as
     * something playing (the strip and the player both key off having a track id at all), and that
     * id resolves to nothing anywhere else, which is exactly right: there is no album page to open,
     * no play count to keep, and nothing for the next launch to restore.
     */
    fun playFile(uri: Uri) {
        scope.launch {
            val known = mediaStoreId(uri)?.let { id -> trackById?.invoke(id) }
            if (known != null) {
                play(listOf(known))
                return@launch
            }
            externalName = withContext(Dispatchers.IO) { displayName(uri) }
            queueAlbumIds = emptyMap()
            unshuffledIds = null
            command { player ->
                player.setMediaItems(listOf(externalItem(uri)), 0, 0L)
                player.prepare()
                player.play()
            }
        }
    }

    /** The row id behind a `content://media/…/audio/media/<id>` uri, if that is what this is. */
    private fun mediaStoreId(uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * What to call a file from outside the library when its tags do not say — see [displayName].
     *
     * Held here rather than put on the media item, which is the mistake the first version made: a
     * `MediaItem`'s own metadata *overrides* what the container turns out to say, so setting the file
     * name as the title meant a properly tagged song announced itself as `12.mp3` for as long as it
     * played. Used only when the tags leave the title empty, and only for the external item.
     */
    private var externalName: String? = null

    /**
     * What to call the file if its tags do not.
     *
     * `DISPLAY_NAME` is the file's own name and is all any provider promises.
     */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private fun externalItem(uri: Uri): MediaItem = MediaItem.Builder()
        .setMediaId(ExternalTrackId.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    /**
     * Slots one track in right after whatever is playing, without disturbing it.
     *
     * The queue operation a player like this actually needs: you hear something, you want it next,
     * and you do not want the album you are in the middle of thrown away to get it. With nothing
     * playing there is no "next", so it just plays.
     */
    fun playNext(track: Track) = playNext(listOf(track), track.title)

    /**
     * The same for a whole album, artist or genre: the lot lands directly after the current track,
     * in its own order.
     *
     * *Directly* after, and not behind whatever was queued before it. "Play next" is a promise about
     * the next track, and a queue that honours the order things were picked in cannot keep that
     * promise for anything but the first tap. So queueing a song and then an album plays the album
     * first and the song after it — the earlier pick is not lost, it is one album further on. The
     * queue screen is where an order picked over several taps gets rearranged, and it can do it by
     * dragging rows, which is a better answer than a rule nobody can see.
     *
     * [label] is what the confirmation banner names; the album's title where there is one, since "14
     * songs" on its own does not say which fourteen.
     */
    fun playNext(tracks: List<Track>, label: String? = null) = command { player ->
        if (tracks.isEmpty()) return@command
        queueAlbumIds = queueAlbumIds + tracks.associate { it.id to it.albumId }
        if (player.mediaItemCount == 0) {
            // Nothing playing, so there is no "next" to land after: this is a play, and it answers
            // to the shuffle the transport is set to like every other one.
            val ordered = if (shuffled) shuffleAround(tracks, 0) else tracks
            unshuffledIds = if (shuffled) tracks.map { it.id } else null
            player.setMediaItems(ordered.map { it.toMediaItem() }, 0, 0L)
            player.prepare()
            player.play()
            ordered.first().let { stats.recordPlay(it.id, it.albumId) }
        } else {
            val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItems(at, tracks.map { it.toMediaItem() })
            rememberQueuedNext(player, tracks)
        }
        notify(label ?: tracks.first().title, tracks.size)
    }

    /**
     * Puts a block that was just queued into the order the queue would come back to, so that turning
     * shuffle off afterwards does not drop what you queued while it was on.
     *
     * It goes where it went in the queue itself: behind the track playing now. There is nowhere more
     * truthful to put it — the unshuffled order has no idea what was queued after what, and the end
     * of the list is the one place the tracks are certainly not wanted, "play next" being the whole
     * request.
     */
    private fun rememberQueuedNext(player: MediaController, tracks: List<Track>) {
        val order = unshuffledIds ?: return
        val ids = tracks.map { it.id }
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val at = if (currentId == null) -1 else order.indexOf(currentId)
        unshuffledIds = if (at < 0) order + ids
        else order.subList(0, at + 1) + ids + order.subList(at + 1, order.size)
    }

    private fun notify(label: String, count: Int) {
        noticeCount++
        _queued.tryEmit(QueueNotice(label = label, count = count, sequence = noticeCount))
    }

    fun togglePlayPause() = command { player ->
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = command { it.seekToNextMediaItem() }

    /**
     * WP8 behaviour: the back button restarts the current track unless you press it early on,
     * in which case it goes to the previous one.
     */
    fun previous() = command { player ->
        if (player.currentPosition > RestartThresholdMs && player.isCurrentMediaItemSeekable) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    /**
     * Seeks to a moment rather than to a proportion of the track.
     *
     * Which is what a tapped line of a timed `.lrc` means: the file says where that line is in
     * milliseconds, and turning that into a fraction on the way in only to multiply it back out
     * would lose the last of it to the float.
     */
    fun seekTo(positionMs: Long) = command { player ->
        val duration = player.duration
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        player.seekTo(target)
    }

    fun seekToFraction(fraction: Float) = command { player ->
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /**
     * Shuffles the queue, or puts it back in the order it came in.
     *
     * See [shuffled] for why this rearranges the queue rather than setting media3's own shuffle
     * mode. What is playing keeps playing and goes to the front, so there is always a full queue
     * ahead of it however far through the album the button was pressed.
     */
    fun toggleShuffle() = command { player ->
        if (shuffled) unshuffle(player) else shuffle(player)
    }

    /** What is playing now, and everything else behind it in a random order. */
    private fun shuffle(player: MediaController) {
        shuffled = true
        val count = player.mediaItemCount
        if (count < 2) {
            unshuffledIds = null
            return
        }
        val items = (0 until count).map { player.getMediaItemAt(it) }
        val ids = items.map { it.mediaId.toLongOrNull() }
        // A queue holding a file from outside the library has no id to come back by — see [playFile]
        // — so there is no order to remember and the shuffle is simply one way.
        unshuffledIds = if (ids.all { it != null }) ids.filterNotNull() else null
        reorder(player, shuffleAround(items, player.currentMediaItemIndex.coerceIn(0, count - 1)), 0)
    }

    /**
     * The order the queue was in before [shuffle], as far as it still exists.
     *
     * Entries are matched to the remembered ids in order, so two copies of one track come back to
     * the two places that track held — they are the same track, so which copy goes where is not a
     * question. Anything that does not line up exactly leaves the queue where it is: a queue that
     * has been taken apart since has no order to be put back into, and inventing one would move
     * tracks the user placed by hand.
     */
    private fun unshuffle(player: MediaController) {
        shuffled = false
        val order = unshuffledIds ?: return
        unshuffledIds = null
        val count = player.mediaItemCount
        if (order.size != count) return
        val waiting = HashMap<Long, ArrayDeque<MediaItem>>()
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            val id = item.mediaId.toLongOrNull() ?: return
            waiting.getOrPut(id) { ArrayDeque() }.addLast(item)
        }
        val target = ArrayList<MediaItem>(count)
        for (id in order) target.add(waiting[id]?.removeFirstOrNull() ?: return)
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val at = order.indexOf(currentId)
        if (at < 0) return
        reorder(player, target, at)
    }

    /**
     * Rearranges the queue into [target], whose [currentTarget]-th entry is where the track playing
     * now belongs.
     *
     * Three calls rather than a move per track, and none of them touches the current item: it is
     * *moved* to its new place and the two runs either side of it are replaced wholesale. Replacing
     * the item that is playing would tear the player down and build it again — a gap in the sound
     * for a button that was only asked to reorder a list — and a move per track is a session command
     * per track, which for an album is fine and for a shuffled library is not.
     */
    private fun reorder(player: MediaController, target: List<MediaItem>, currentTarget: Int) {
        val count = player.mediaItemCount
        if (target.size != count || count == 0) return
        val current = player.currentMediaItemIndex
        if (current !in 0 until count || currentTarget !in 0 until count) {
            // Nothing is playing, so there is nothing to protect.
            player.replaceMediaItems(0, count, target)
            return
        }
        if (currentTarget != current) player.moveMediaItem(current, currentTarget)
        if (currentTarget > 0) {
            player.replaceMediaItems(0, currentTarget, target.subList(0, currentTarget))
        }
        if (currentTarget + 1 < count) {
            player.replaceMediaItems(currentTarget + 1, count, target.subList(currentTarget + 1, count))
        }
    }

    fun cycleRepeat() = command { player ->
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() = command { player ->
        player.stop()
        player.clearMediaItems()
        unshuffledIds = null
        // Emptying the queue on purpose is the one thing that must not come back next launch.
        savedQueue.clear()
    }

    // ---- sleep timer ----

    private var sleepJob: Job? = null
    private val _sleepRemainingMs = MutableStateFlow(0L)

    /** Milliseconds left on the sleep timer, or 0 when it isn't running. */
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()

    /** Pass 0 to cancel. Playback fades out over a few seconds rather than cutting off. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepRemainingMs.value = 0L
            return
        }
        sleepJob = scope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                _sleepRemainingMs.value = remaining
                delay(1_000L)
                remaining -= 1_000L
            }
            _sleepRemainingMs.value = 0L
            fadeOutAndPause()
        }
    }

    private suspend fun fadeOutAndPause() = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        // Where the volume is *now*, not a hard 1: [ReplayGain] may have turned this track down to
        // sit level with the rest of the library, and fading from full would make the last seconds of
        // the night louder than the song was. It is also what gets put back at the end, so a fade
        // does not quietly undo the normalisation for everything played afterwards.
        val start = player.volume
        val steps = 20
        repeat(steps) { step ->
            player.volume = start * (1f - (step + 1) / steps.toFloat())
            delay(FadeMs / steps)
        }
        player.pause()
        // Restore the volume so the next play isn't silent.
        player.volume = start
        publish(player)
    }

    /**
     * Playback position, emitted only while collected. Nothing ticks when no screen is
     * showing a progress bar, which is the whole reason position isn't part of [PlayerState].
     */
    fun positionFlow(intervalMs: Long = 500L): Flow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Main)

    fun release() {
        controller?.let {
            it.removeListener(listener)
            it.release()
        }
        controller = null
    }

    /** Runs [block] against a connected controller on the main thread, connecting if needed. */
    private fun command(block: (MediaController) -> Unit) {
        scope.launch {
            val player = controller ?: connect() ?: return@launch
            withContext(Dispatchers.Main) {
                block(player)
                publish(player)
            }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        // A file from outside the library falls back to its file name, and only if its tags leave the
        // title empty — see [externalName]. Everything else in the app has a title by construction.
        val title = metadata.title?.toString()?.takeUnless { it.isBlank() }
            ?: externalName?.takeIf { item?.mediaId == ExternalTrackId.toString() }
            ?: ""
        // One walk of the queue per event, shared by the two things that need it: the snapshot that
        // survives the process, and the list the queue screen draws.
        val ids = player.queueTrackIds()
        remember(player, ids)
        updatePositionSaver(player)
        publishQueue(player, ids)
        NowPlayingWidget.publish(
            context = context,
            title = title,
            artist = metadata.artist?.toString().orEmpty()
        )
        _state.value = PlayerState(
            trackId = item?.mediaId?.toLongOrNull(),
            title = title,
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumId = item?.mediaId?.toLongOrNull()?.let { queueAlbumIds[it] } ?: -1L,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0L),
            shuffle = shuffled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queueSize = player.mediaItemCount,
            queueIndex = player.currentMediaItemIndex,
            previousIndex = player.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET },
            nextIndex = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET },
            previous = player.faceAt(player.previousMediaItemIndex),
            next = player.faceAt(player.nextMediaItemIndex)
        )
    }

    /**
     * Every queue entry's track id, in order.
     *
     * An id that will not parse is skipped rather than guessed at, which is what the saved queue has
     * always done — and nothing ever is, since every item in this queue was built by [toMediaItem] out
     * of a track of the library.
     */
    private fun Player.queueTrackIds(): List<Long> {
        val count = mediaItemCount
        if (count == 0) return emptyList()
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) {
            ids.add(getMediaItemAt(i).mediaId.toLongOrNull() ?: continue)
        }
        return ids
    }

    /**
     * Rebuilds [queue] when, and only when, the queue is not the one it already holds.
     *
     * The comparison is against the ids rather than against the faces: it is what tells a move or a
     * removal from a track merely starting, and it costs a list of longs that had to be walked anyway.
     * Without it every play, pause and seek would allocate a face per track for a screen that is
     * usually shut.
     */
    private fun publishQueue(player: Player, ids: List<Long>) {
        if (ids == queueIds) return
        queueIds = ids
        _queue.value = (0 until player.mediaItemCount).mapNotNull { player.faceAt(it) }
    }

    /**
     * Moves one queue entry to another position — the queue screen's drag, one place at a time.
     *
     * media3 does the rest: what is playing keeps playing whether it was the thing moved or something
     * moved around it, and `currentMediaItemIndex` follows it.
     *
     * A move made while the queue is shuffled is not carried back into the order shuffle came from:
     * that order is the record's own, and it is what turning shuffle off is asking for.
     */
    fun moveQueueItem(from: Int, to: Int) = command { player ->
        val count = player.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return@command
        player.moveMediaItem(from, to)
    }

    /**
     * Takes one entry out of the queue.
     *
     * Removing the track that is playing is allowed and is the interesting case: media3 moves to the
     * next one and carries on, which is what the gesture asks for — the alternative, refusing it, would
     * mean the one row you cannot get rid of is the one you are listening to.
     *
     * Emptying the queue this way ends up in the same place as [stop]: the file is cleared, because a
     * queue the user has just taken apart row by row must not come back at the next launch.
     */
    fun removeQueueItem(index: Int) = command { player ->
        if (index !in 0 until player.mediaItemCount) return@command
        val removed = player.getMediaItemAt(index)
        // Out of the order shuffle would come back to as well, or that order would no longer
        // describe this queue and turning shuffle off would quietly do nothing.
        removed.mediaId.toLongOrNull()?.let { id ->
            unshuffledIds = unshuffledIds?.toMutableList()?.apply { remove(id) }
        }
        player.removeMediaItem(index)
        if (player.mediaItemCount == 0) {
            unshuffledIds = null
            savedQueue.clear()
        }
    }

    /**
     * A queue entry as the player's swipe needs to draw it. Read from the queue's own metadata rather
     * than from the library, so it costs nothing and is right even for a queue built out of a playlist
     * whose tracks have since been re-scanned.
     */
    private fun Player.faceAt(index: Int): TrackFace? {
        if (index == C.INDEX_UNSET || index < 0 || index >= mediaItemCount) return null
        val item = getMediaItemAt(index)
        val id = item.mediaId.toLongOrNull() ?: return null
        val metadata = item.mediaMetadata
        return TrackFace(
            trackId = id,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumId = queueAlbumIds[id] ?: -1L
        )
    }

    /**
     * Goes to a queue position outright, which is what the player's swipe commits to: it has already
     * shown you the track you are landing on, so it must land there. [previous] keeps the phone's
     * behaviour of restarting the current track instead, because a *button* press early in a track is
     * a different question from a gesture that dragged a particular face into the middle of the screen.
     */
    fun skipToQueueIndex(index: Int) = command { player ->
        if (index in 0 until player.mediaItemCount) player.seekToDefaultPosition(index)
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // The store's own id, not the library's: this URI is answered by MediaStore's legacy
                // albumart table, which has never heard of a tag-derived album id.
                .setArtworkUri(albumArtUri(mediaAlbumId))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private companion object {
        val AlbumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

        fun albumArtUri(albumId: Long): Uri? =
            if (albumId > 0) ContentUris.withAppendedId(AlbumArtBase, albumId) else null

        /** Press "previous" after this much and you get the track restarted instead. */
        const val RestartThresholdMs = 3_000L

        /**
         * The id an audio file from outside the library plays under — see [playFile].
         *
         * Negative on purpose, and the same sentinel the rest of the app already uses for "no such
         * row": every lookup it is put through (a track, an album, a cover, a play count) answers
         * nothing, which is the truth about it, while the shell still sees *something* playing.
         */
        const val ExternalTrackId = -1L

        /** How often a long track's position is written down while it plays — see [updatePositionSaver]. */
        const val PositionSaveMs = 10_000L

        /** How long the sleep timer takes to fade playback out. */
        const val FadeMs = 5_000L
    }
}
