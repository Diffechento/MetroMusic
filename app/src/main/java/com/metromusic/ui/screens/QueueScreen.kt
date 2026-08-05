package com.metromusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroContextMenu
import com.metrocompose.MetroLight
import com.metrocompose.MetroRegular
import com.metrocompose.MetroRisingPageState
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroTheme
import com.metrocompose.metroReorderRow
import com.metrocompose.metroRiseDrag
import com.metrocompose.rememberMetroReorder
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.playback.TrackFace
import com.metromusic.ui.components.AlbumArt
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.formatTrackCount

/**
 * The queue, over the player.
 *
 * It is a second [MetroRisingPageState] stacked on the first one, and it has to be: the player is
 * already an overlay outside the navigation host, so a queue pushed as a destination would appear
 * *under* it. Pulling the player up once it is open is the gesture — the same movement that brought the
 * player out of the strip, one page further on — and pushing this page's own title back down puts it
 * away, as does Back.
 *
 * **Holding a row picks it up.** Drag it and it moves, one place at a time, with the list creeping when
 * the row is held against either end. Let go without having moved it and the context menu opens
 * instead, which is what a hold means everywhere else in the app — so one press offers both and the
 * hand decides which. See `MetroReorderState` for why that is one gesture rather than a grip in the
 * margin, which is how the playlist page does it.
 *
 * **The order on screen is this screen's own copy of it.** Every edit is applied here and sent to the
 * player, rather than sent and waited for: a `MediaController` answers a frame or several later, and a
 * row that snaps back to where it was for two frames after each step of a drag is the whole reason
 * [com.metrocompose.MetroPageSwipeState] exists. The player's own queue is what re-seeds this list, and
 * because our edits echo back identical, that costs nothing.
 */
@Composable
fun QueueScreen(rising: MetroRisingPageState, onClose: () -> Unit) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val queue by services.player.queue.collectAsStateWithLifecycle()

    // The queue as this screen holds it: the player's list, with an identity per entry so that a
    // lazy key survives a move and a queue holding the same track twice is still two rows.
    var entries by remember { mutableStateOf(emptyList<QueueEntry>()) }
    LaunchedEffect(queue) {
        // Our own edits come back from the player identical, and are therefore not a change.
        if (queue.map { it.trackId } != entries.map { it.face.trackId }) {
            entries = queue.mapIndexed { index, face -> QueueEntry(index.toLong(), face) }
        }
    }

    val listState = rememberLazyListState()
    val reorder = rememberMetroReorder(listState, rowCount = entries.size) { from, to ->
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
        services.player.moveQueueItem(from, to)
    }

    // Opened onto where you are, not onto the top of a queue whose first forty tracks have played.
    // Once, on the way in: re-running it would drag the list back under a finger that had scrolled it.
    LaunchedEffect(Unit) {
        val at = playerState.queueIndex
        if (at > 0) listState.scrollToItem(at)
    }

    var menuFor by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The title is the handle. The list underneath owns every vertical drag inside itself —
            // it has to, it scrolls — so the push that puts this page away lives up here, where there
            // is nothing else a downward drag could mean.
            //
            // A tap on it does the same thing, which is the idiom the strip already teaches: tap it or
            // pull it, either one opens the player. Here it is tap it or push it, and either one leaves.
            // It also means the page is never shut only by Back, whatever the gesture switch says.
            Column(
                Modifier
                    .fillMaxWidth()
                    .metroRiseDrag(rising, enabled = settings.gestureQueueDown)
                    .clickable(onClick = onClose)
                    .padding(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.queue_overline),
                    color = colors.fg,
                    fontFamily = MetroSemilight,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp
                )
                Text(
                    text = stringResource(R.string.queue_title),
                    color = colors.fg,
                    fontFamily = MetroLight,
                    fontSize = 46.sp,
                    maxLines = 1
                )
                Text(
                    text = if (entries.isEmpty()) {
                        stringResource(R.string.queue_hint)
                    } else {
                        formatTrackCount(entries.size)
                    },
                    color = colors.dim,
                    fontFamily = MetroRegular,
                    fontSize = 13.sp
                )
            }

            if (entries.isEmpty()) {
                EmptyNote(stringResource(R.string.queue_empty))
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(entries, key = { _, entry -> entry.uid }) { index, entry ->
                    val lifted = reorder.active && reorder.index == index
                    QueueRow(
                        face = entry.face,
                        position = index + 1,
                        isCurrent = index == playerState.queueIndex,
                        lifted = lifted,
                        menuOpen = menuFor == entry.uid,
                        onPlay = { services.player.skipToQueueIndex(index) },
                        onRemove = {
                            menuFor = null
                            entries = entries.filterNot { it.uid == entry.uid }
                            services.player.removeQueueItem(index)
                        },
                        onPlayNext = {
                            menuFor = null
                            val to = playerState.queueIndex
                                .let { if (it < index) it + 1 else it }
                                .coerceIn(0, entries.lastIndex)
                            if (to != index) {
                                entries = entries.toMutableList()
                                    .apply { add(to, removeAt(index)) }
                                services.player.moveQueueItem(index, to)
                            }
                        },
                        onDismissMenu = { menuFor = null },
                        modifier = Modifier.metroReorderRow(
                            state = reorder,
                            index = index,
                            onHeldStill = { menuFor = entry.uid }
                        )
                    )
                }
                item(key = "queue-inset") { MetroBottomInset(extra = 20.dp) }
            }
        }
    }
}

/**
 * One queue entry with an identity of its own.
 *
 * The track id cannot be that identity: queueing an album twice is a thing people do, and two lazy
 * items under one key is a crash. The index cannot be it either — that is what a move changes, and rows
 * re-keyed on every step of a drag are rows recomposed on every step of a drag. So the identity is
 * assigned when the list is seeded and rides along with the row through every move.
 */
private data class QueueEntry(val uid: Long, val face: TrackFace)

/** Where "remove from queue" and "play next" sit in the row's menu. */
private const val RemoveIndex = 0
private const val PlayNextIndex = 1

/**
 * One row of the queue: its position, its cover, and what it is.
 *
 * Deliberately built out of the queue's own metadata rather than out of a [com.metromusic.data.model
 * .Track] resolved from the library, which is what every other track list in the app does. A queue
 * entry is what media3 holds — see `faceAt` — so a queue built out of a playlist whose files have since
 * been re-scanned still draws, and nothing here needs a duration or a path.
 *
 * The number is the position in the queue and not the track number on its album: this list is an order
 * somebody made, and its rows come from as many albums as they like.
 */
@Composable
private fun QueueRow(
    face: TrackFace,
    position: Int,
    isCurrent: Boolean,
    lifted: Boolean,
    menuOpen: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
    onDismissMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MetroTheme.colors
    MetroContextMenu(
        expanded = menuOpen,
        items = listOf(
            stringResource(R.string.menu_remove_from_queue),
            stringResource(R.string.menu_play_next)
        ),
        onSelect = { index ->
            when (index) {
                RemoveIndex -> onRemove()
                PlayNextIndex -> onPlayNext()
            }
        },
        onDismiss = onDismissMenu,
        // "Play next" against the track that is already playing, or the one already next, has nothing
        // to do. Greyed rather than dropped, so the menu keeps its shape — see [MetroContextMenu].
        disabledItems = if (isCurrent) setOf(PlayNextIndex) else emptySet(),
        modifier = modifier
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // A row that is up is tinted, not merely shifted: the finger is over it, so the only
                // part of it the eye can check is the edges.
                //
                // The page's own colour goes on first and the tint over it, rather than one translucent
                // fill: a lifted row travels *over* its neighbours, and a fill you can see through
                // makes that two rows of text superimposed instead of one row passing another.
                .background(colors.bg)
                .then(
                    if (lifted) {
                        Modifier.background(colors.accent.copy(alpha = 0.22f))
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onPlay)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = position.toString(),
                color = if (isCurrent) colors.accent else colors.dim,
                fontFamily = MetroRegular,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            AlbumArt(
                albumId = face.albumId,
                representativeTrackId = face.trackId,
                size = 48.dp
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = face.title,
                    color = if (isCurrent) colors.accent else colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (face.artist.isNotEmpty()) {
                    Text(
                        text = face.artist,
                        color = colors.subtle,
                        fontFamily = MetroRegular,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
