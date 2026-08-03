package com.metromusic.playback

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
    private val savedQueue: PlaybackStateStore
) {
    private var controller: MediaController? = null
    private val connectMutex = Mutex()

    private val _state = MutableStateFlow(PlayerState.Empty)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

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
     * Where the next "play next" block goes, as the media id it must land behind.
     *
     * An index would be wrong within one track: everything shifts as items are added and the user is
     * free to skip in the middle of it. Held as an id and looked up each time, so a cursor that no
     * longer makes sense — its track played and gone, the queue replaced — simply isn't found and the
     * insert falls back to "right after what is playing".
     *
     * This is what makes "queue up three albums" mean what it says. Inserting each one directly after
     * the current track puts the *last* album you picked first and leaves the others behind it in
     * reverse, which reads as the feature being broken rather than as a policy.
     */
    private var queueTailId: String? = null

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
            stats.recordPlay(trackId, queueAlbumIds[trackId] ?: -1L)
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

        val tracks = library.resolve(saved.trackIds)
        if (tracks.isEmpty()) return
        // Match on the id rather than the index: tracks that have since gone shift everything after
        // them, and coming back on the wrong song is worse than coming back on the first one.
        val currentId = saved.trackIds.getOrNull(saved.index)
        val startIndex = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        command { player ->
            if (player.mediaItemCount > 0) return@command
            queueAlbumIds = tracks.associate { it.id to it.albumId }
            queueTailId = saved.queueTailId?.toString()
            // From the top of the track, not from where it was cut off: nobody wants a song
            // handed back to them from the middle.
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            // prepare() and *not* play(): buffered, seekable and on the strip, but silent until
            // something is pressed.
            player.prepare()
        }
    }

    /**
     * Snapshots the queue into [PlaybackStateStore]. Main thread, like every other player read.
     *
     * Called from [publish], which is enough precisely because the position isn't kept: everything
     * that changes what would be restored — a new queue, a skip, shuffle, repeat — is an event, and
     * a track that is merely playing changes none of it.
     */
    private fun remember(player: Player) {
        val count = player.mediaItemCount
        // An empty player is the state at startup, before the restore has run — saving it there
        // would erase the queue we are about to put back. Emptying the queue on purpose ([stop])
        // clears the store itself.
        if (count == 0) return
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) {
            ids.add(player.getMediaItemAt(i).mediaId.toLongOrNull() ?: continue)
        }
        if (ids.isEmpty()) return
        savedQueue.save(
            SavedQueue(
                trackIds = ids,
                index = player.currentMediaItemIndex.coerceIn(0, ids.lastIndex),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                queueTailId = queueTailId?.toLongOrNull()
            )
        )
    }

    /**
     * Replaces the queue with [tracks] and starts at [startIndex]. This is what every "play"
     * affordance in the app funnels into — a track row, an album, a playlist, shuffle-all.
     */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        queueAlbumIds = tracks.associate { it.id to it.albumId }
        queueTailId = null
        command { player ->
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
            player.prepare()
            player.play()
            // onMediaItemTransition doesn't fire for the very first item of a new queue.
            val first = tracks.getOrNull(startIndex)
            if (first != null) stats.recordPlay(first.id, first.albumId)
        }
    }

    /**
     * Slots one track in right after whatever is playing, without disturbing it.
     *
     * The queue operation a player like this actually needs: you hear something, you want it next,
     * and you do not want the album you are in the middle of thrown away to get it. With nothing
     * playing there is no "next", so it just plays.
     */
    fun playNext(track: Track) = playNext(listOf(track), track.title)

    /**
     * The same for a whole album, artist or genre: everything lands after the current track, in
     * order — and behind anything queued just before it, so several albums queued one after another
     * play in the order they were picked.
     *
     * [label] is what the confirmation banner names; the album's title where there is one, since "14
     * songs" on its own does not say which fourteen.
     */
    fun playNext(tracks: List<Track>, label: String? = null) = command { player ->
        if (tracks.isEmpty()) return@command
        queueAlbumIds = queueAlbumIds + tracks.associate { it.id to it.albumId }
        val items = tracks.map { it.toMediaItem() }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
            tracks.first().let { stats.recordPlay(it.id, it.albumId) }
        } else {
            player.addMediaItems(insertionPoint(player), items)
        }
        queueTailId = tracks.last().id.toString()
        notify(label ?: tracks.first().title, tracks.size)
    }

    /**
     * The index the next queued block goes at: behind the last one if it is still ahead of us,
     * otherwise directly after the current track.
     *
     * Only positions at or after the current one count. A cursor left behind by a block that has
     * already played would insert *into the past* of the queue, where the tracks would never be
     * reached — which is indistinguishable from the tap having done nothing at all.
     */
    private fun insertionPoint(player: MediaController): Int {
        val current = player.currentMediaItemIndex
        val tail = queueTailId
        if (tail != null) {
            for (i in current until player.mediaItemCount) {
                if (player.getMediaItemAt(i).mediaId == tail) return i + 1
            }
        }
        return (current + 1).coerceIn(0, player.mediaItemCount)
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

    fun seekToFraction(fraction: Float) = command { player ->
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }

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
        queueTailId = null
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
        val steps = 20
        repeat(steps) { step ->
            player.volume = 1f - (step + 1) / steps.toFloat()
            delay(FadeMs / steps)
        }
        player.pause()
        // Restore the volume so the next play isn't silent.
        player.volume = 1f
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
        remember(player)
        NowPlayingWidget.publish(
            context = context,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty()
        )
        _state.value = PlayerState(
            trackId = item?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumId = item?.mediaId?.toLongOrNull()?.let { queueAlbumIds[it] } ?: -1L,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0L),
            shuffle = player.shuffleModeEnabled,
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
                .setArtworkUri(albumArtUri(albumId))
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

        /** How long the sleep timer takes to fade playback out. */
        const val FadeMs = 5_000L
    }
}
