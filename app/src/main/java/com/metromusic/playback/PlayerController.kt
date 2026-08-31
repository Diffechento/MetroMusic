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
     * With shuffle on this stays the *timeline* order, which is the order the queue was built in and the
     * one a move or a removal is addressed in — what plays next comes from `nextMediaItemIndex` and is
     * shown by the player, not here.
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
            // A file opened from outside the app is not in the library, so there is nothing for a
            // play count to be about and nothing the history section could show — see [playFile].
            if (trackId <= 0) return
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
            queueTailId = null
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
     * moved around it, and `currentMediaItemIndex` follows it. The "play next" cursor is held as an id
     * and looked up, so it survives this without being told.
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
        if (player.getMediaItemAt(index).mediaId == queueTailId) queueTailId = null
        player.removeMediaItem(index)
        if (player.mediaItemCount == 0) {
            queueTailId = null
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

        /** How long the sleep timer takes to fade playback out. */
        const val FadeMs = 5_000L
    }
}
