package com.metromusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.AppBar
import com.metrocompose.AppBarButton
import com.metrocompose.Metro
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroInputBox
import com.metrocompose.MetroLight
import com.metrocompose.MetroRegular
import com.metrocompose.MetroRisingPageState
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroTheme
import com.metrocompose.metroReorderRow
import com.metrocompose.metroRiseDrag
import com.metrocompose.metroRiseOverscroll
import com.metrocompose.metroRowDismiss
import com.metrocompose.rememberMetroReorder
import com.metrocompose.rememberMetroRowDismiss
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.playback.TrackFace
import com.metromusic.ui.Glyphs
import com.metromusic.ui.components.AlbumArt
import com.metromusic.ui.components.EmptyNote
import com.metromusic.ui.formatTrackCount

/**
 * The queue, over the player.
 *
 * It is a second [MetroRisingPageState] stacked on the first one, and it has to be: the player is
 * already an overlay outside the navigation host, so a queue pushed as a destination would appear
 * *under* it. Pulling the player up once it is open is the gesture — the same movement that brought the
 * player out of the strip, one page further on — and pushing it back down puts it away, as does Back.
 * That push works anywhere: the title answers to a drag directly, and the list hands over what it cannot
 * scroll, so a page dragged down from the top of the list travels with the finger like any other.
 *
 * **Holding a row picks it up**, and dragging carries it a place at a time, with the list creeping while
 * the row is held against either end. **Swiping a row aside removes it.** There is no menu here at all:
 * the two things this screen exists for are both the row itself moving under the finger, which is a
 * shorter road than a sheet you have to read — and it leaves the hold free to mean one thing only.
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

    var saving by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The title answers to the push directly, because no list is involved in touching it — the
            // list below hands the same push over through nested scroll, so between them the gesture
            // works anywhere on the page rather than only on one strip of it.
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
                if (entries.isNotEmpty()) {
                    Text(
                        text = formatTrackCount(entries.size),
                        color = colors.dim,
                        fontFamily = MetroRegular,
                        fontSize = 13.sp
                    )
                }
            }

            if (entries.isEmpty()) {
                EmptyNote(stringResource(R.string.queue_empty))
                return@Column
            }

            // The one thing on this screen that is not a row moving under a finger, and the reason it
            // is a button rather than another gesture: a queue somebody assembled out of four albums
            // and then pruned is a playlist that does not exist yet, and the only alternative to
            // keeping it here is building it again in the playlists screen from memory.
            //
            // Where the app puts an app bar — under the title, not at the bottom edge — and the inset
            // is consumed before it, because [AppBar] clears the gesture bar for the case where it is
            // the last thing on a full-bleed screen and here it is the first.
            AppBar(
                Modifier
                    .consumeWindowInsets(WindowInsets.navigationBars)
                    .padding(bottom = 4.dp)
            ) {
                AppBarButton(Glyphs.Add, stringResource(R.string.queue_save)) { saving = true }
            }

            // And the list itself puts the page away, so the gesture is available anywhere on the
            // screen and not only on the title: a downward drag it has nothing left to scroll to — the
            // top of it, or a queue too short to scroll at all — becomes the page's own travel. It
            // keeps the scroll it can use, which is why this is a nested-scroll hand-over rather than a
            // second drag detector fighting the list for the same finger.
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .metroRiseOverscroll(rising, enabled = settings.gestureQueueDown),
                state = listState
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.uid }) { index, entry ->
                    QueueRow(
                        face = entry.face,
                        position = index + 1,
                        isCurrent = index == playerState.queueIndex,
                        lifted = reorder.active && reorder.index == index,
                        swipeToRemove = settings.gestureQueueRemove,
                        onPlay = { services.player.skipToQueueIndex(index) },
                        onRemove = {
                            entries = entries.filterNot { it.uid == entry.uid }
                            services.player.removeQueueItem(index)
                        },
                        modifier = Modifier.metroReorderRow(state = reorder, index = index)
                    )
                }
                item(key = "queue-inset") { MetroBottomInset(extra = 20.dp) }
            }
        }
    }

    // The order as it is on screen, which is the order the user just made. Anything the library does
    // not have an id for is left out
    // rather than written down as a number that will resolve to nothing (see `PlayerController
    // .ExternalTrackId`); a playlist is ids, and an id that means nothing is a row that never draws.
    MetroInputBox(
        visible = saving,
        title = stringResource(R.string.queue_save),
        placeholder = stringResource(R.string.label_name),
        onConfirm = { name ->
            val ids = entries.map { it.face.trackId }.filter { it > 0 }
            if (ids.isNotEmpty()) {
                services.playlists.create(
                    name = name,
                    trackIds = ids,
                    now = System.currentTimeMillis()
                )
            }
            saving = false
        },
        onDismiss = { saving = false }
    )
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
 *
 * **Swiping the row aside removes it**, either way, and there is no menu on this screen at all: the two
 * things you come here to do are reorder and remove, and both are now the row itself moving under the
 * finger rather than a sheet unrolling to be read. The word appears in the gap as the row leaves and is
 * at full strength exactly where letting go would commit, so the threshold is something the hand is
 * told rather than has to discover by losing a song.
 */
@Composable
private fun QueueRow(
    face: TrackFace,
    position: Int,
    isCurrent: Boolean,
    lifted: Boolean,
    swipeToRemove: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MetroTheme.colors
    val away = rememberMetroRowDismiss(onDismiss = onRemove)
    Box(modifier) {
        // Behind the row, and only ever seen through the gap the row leaves — which is why the block
        // spans the whole row and is simply covered up: no arithmetic about how wide the gap is, and
        // the word is revealed by the row moving off it rather than drawn to fit.
        if (away.active) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.accent.copy(alpha = away.progress))
            ) {
                Text(
                    text = stringResource(R.string.queue_remove),
                    color = Metro.Fg,
                    fontFamily = MetroRegular,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .align(
                            if (away.offset > 0f) Alignment.CenterStart else Alignment.CenterEnd
                        )
                        .padding(horizontal = 24.dp)
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                // The row is where you can see it: the layer and the detector are on the same node, so
                // the finger stays on the row it is carrying.
                .graphicsLayer { translationX = away.offset }
                .metroRowDismiss(away, enabled = swipeToRemove)
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
