package com.metromusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroSlideInEasing
import com.metromusic.core.LocalServices
import com.metromusic.data.lyrics.Lyrics
import com.metromusic.data.lyrics.LyricsStatus

/**
 * The size every line in the window is **laid out** at, which is the size of the line being sung.
 *
 * Everything else is drawn smaller than this, never larger — see [LyricLineText] for why that way
 * round — so this number is what decides where the text wraps, and it is the one thing in here that
 * is a compromise. The sung line wants to be as large as a Windows Phone would make it; the column
 * is only as wide as the toggle strip leaves it, and past about this size every line of an ordinary
 * verse wraps onto two rows and the window holds half a verse.
 */
private val WindowLineSize = 38.sp

/**
 * How small a line that is not being sung is drawn here.
 *
 * **Well under the lyrics page's [LyricRestingScale], and that is the whole look.** This started at
 * 0.80, which put a sung line and its neighbours within a quarter of each other's size — legible,
 * and not what this is imitating. A phone of this era says which thing you are meant to be reading
 * by making it *much* bigger than everything around it, not by tinting it: at 0.56 the sung line is
 * nearly twice its neighbours and carries across a room, which is the point of putting words where
 * the cover was. The resting size still lands around 15sp, which is a readable line of type, so the
 * verse ahead can still be read.
 */
private const val WindowRestingScale = 0.56f

/**
 * Where down the window the line being sung comes to rest.
 *
 * A little above the middle rather than a third of the way down as on the page. The page is read
 * downwards and gives most of itself to what is coming; this is a *window*, and a window reads best
 * with the sung line near the centre of it — slightly high, so that rather more of the verse ahead
 * is visible than of the one just gone.
 */
private const val RestFraction = 0.40f

/**
 * How much of the window's height the words fade out over at its top and bottom edges.
 *
 * A line at a time rather than a gradient across the glyphs — see [edgeFade] for why — so this is
 * roughly two lines' worth of band at each end, which is what it takes for the dimming to read as a
 * slope rather than as one line that is oddly grey.
 */
private const val FadeFraction = 0.20f

/** How long the cover takes to become the words, and the words the cover. */
private const val RevealMillis = 420

/**
 * What the player knows about the song's words: the words themselves, and whether the screen should
 * be laid out for them at all.
 *
 * Two facts rather than one, because a null set of words means two different things a moment apart.
 */
@Immutable
data class PlayerWords(val lyrics: Lyrics?, val showWords: Boolean)

/** The three answers, since a null cannot tell "none" from "not yet". */
private sealed interface Answer {
    data object Looking : Answer
    data class Words(val lyrics: Lyrics) : Answer
    data object None : Answer
}

/**
 * Looks up the words for [trackId], and decides whether the player wears the words layout for it.
 *
 * **A song with nothing to show gets the ordinary player back**, cover and all, rather than a tall
 * empty slot with a small square at the bottom of it. That is what makes the setting safe to leave
 * on: it says *show the words where there are words*, and where there are none nothing is different
 * from how the player has always looked.
 *
 * [PlayerWords.showWords] is therefore **sticky**, and that is the whole subtlety here. The lookup
 * resets while the next song is being found — `produceState` restarts its producer on a key change
 * — so a literal reading of "no words yet" would collapse the layout to the cover and rebuild it a
 * few frames later, on every track change of an album that has words for all of them. The furniture
 * would jump each time. So the last settled verdict stands until the next one arrives, and the first
 * one is seeded from the availability index, which is in memory and right about nearly every song.
 */
@Composable
fun rememberPlayerWords(trackId: Long): PlayerWords {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()

    // **A lookup that could not be made is made again when there is a network.** Without this the
    // player decides once per song and never revisits it: a request that failed in a tunnel leaves
    // the cover up for the rest of that track however long the signal has been back, which reads as
    // the app having decided the song has no words. It is a key rather than a condition because the
    // cases that would cost a request are exactly the ones worth retrying — words already in hand
    // return before the network, and a song the index has written off returns before that.
    val retries by services.lyrics.retries.collectAsStateWithLifecycle()

    val answer by produceState<Answer>(Answer.Looking, trackId, library, retries) {
        // Reset first: `produceState` keeps its value across key changes and only restarts the
        // producer, so without this the next song would be read against the last one's words.
        value = Answer.Looking
        val track = library.track(trackId)
        // A track the index has already written off is not asked about at all. The lyrics page can
        // afford that request because somebody chose to open it; here the player would make one per
        // track played, for ever, against a song that is known to have nothing.
        if (track == null || services.lyrics.status(track) == LyricsStatus.Missing) {
            value = Answer.None
            return@produceState
        }

        // **Two emissions, and the first is what stops the slot being empty while the page rises.**
        // What is already on the device goes up as soon as it is read; only if it has no timings is
        // anything asked for, and that ask can be a network round trip. Waiting for it would leave
        // the words blank for as long as it takes — which is the whole second the cover used to be
        // flashed into.
        val local = services.lyrics.localLyrics(track)
        if (local != null) value = Answer.Words(local)
        if (local != null && local.synced) return@produceState

        val best = services.lyrics.lyrics(track)
        value = if (best == null) Answer.None else Answer.Words(best)
    }

    var showWords by remember {
        mutableStateOf(
            library.track(trackId)?.let { services.lyrics.status(it) } == LyricsStatus.Available
        )
    }
    LaunchedEffect(answer) {
        when (answer) {
            is Answer.Words -> showWords = true
            Answer.None -> showWords = false
            Answer.Looking -> Unit
        }
    }

    return PlayerWords(lyrics = (answer as? Answer.Words)?.lyrics, showWords = showWords)
}

/**
 * Whether it is worth offering the words for [trackId] — which is *not* the same question as
 * whether they are on screen.
 *
 * Read from the availability index and nothing else: it is in memory, it costs nothing, and the
 * player's toggle needs an answer for every track that goes past whether or not anybody is looking
 * at the words. Asking properly would mean a lookup per track played for people who never use the
 * feature. Unknown reads as *yes*, the same way the track menu's "show lyrics" stays tappable until
 * something has actually said no — a greyed control that turns out to have been wrong is worse than
 * a live one that finds nothing.
 */
@Composable
fun rememberWordsAvailable(trackId: Long): Boolean {
    val services = LocalServices.current
    val library by services.library.library.collectAsStateWithLifecycle()
    // Collected rather than read, so the toggle comes back to life when a background probe lands.
    val verdicts by services.lyrics.verdicts.collectAsStateWithLifecycle()
    val track = library.track(trackId) ?: return true
    return services.lyrics.statusIn(verdicts, track) != LyricsStatus.Missing
}

/**
 * The player's face, showing the song's words instead of its cover.
 *
 * **The artwork does not leave the screen for this.** The player's whole backdrop is that same
 * cover, drawn at the screen's width behind a scrim, so what this replaces is the small second copy
 * of it — and the slot becomes the one thing on the player that is worth looking at for three
 * minutes rather than for one. That is the whole argument for the setting.
 *
 * **It is not square, and only its [width] is stated.** A cover is artwork and has a shape; words do
 * not, so the player gives this slot a weight and it takes whatever height the column has left over
 * — which is read back off the constraints rather than worked out from a stated chrome height,
 * because the column is the thing that knows. A cover shown in here — see below — is still square,
 * and sits on the bottom edge of it.
 *
 * **Nothing is drawn here until there are words to draw** — the slot simply shows the page through
 * it, and the words fade up when they land. It used to put the cover in while it waited, which made
 * sense when this composable also answered for songs that have none; it does not now, because
 * [rememberPlayerWords] puts the whole player back into its ordinary layout for those. What was left
 * was a cover square flashing up for as long as a read took and then vanishing, every time the page
 * was pulled out of the strip, which is exactly how it was reported. The wait itself is most of the
 * way gone too: what is already on the device now goes up in the frame it is read in.
 *
 * [listModifier] is where the page this square sits on hands its own gesture in — the push that puts
 * the player away, which a list inside it would otherwise swallow whole. **Hoist it with `remember`
 * at the call site**: it is a `composed` modifier, so a fresh instance every recomposition would
 * make this function unskippable and rebuild the window four times a second while the position ticks.
 */
@Composable
fun PlayerLyrics(
    lyrics: Lyrics?,
    width: Dp,
    listModifier: Modifier,
    modifier: Modifier = Modifier
) {
    // Held in an `Animatable` and read only inside the draw-time lambda below, so the fade does not
    // recompose anything: the value is never read during composition.
    val reveal = remember { Animatable(0f) }
    val shown = lyrics
    LaunchedEffect(shown != null) {
        reveal.animateTo(
            targetValue = if (shown != null) 1f else 0f,
            animationSpec = tween(RevealMillis, easing = MetroSlideInEasing)
        )
    }

    BoxWithConstraints(
        modifier.width(width).fillMaxHeight(),
        contentAlignment = Alignment.BottomStart
    ) {
        // The height the row gave us. The window needs it as a Dp — the rest line and the padding
        // that lets the first and last line reach it are fractions of it — and reading it here is
        // what lets the player hand this slot a weight instead of a number.
        val height = maxHeight

        if (shown != null) {
            Box(Modifier.graphicsLayer { alpha = reveal.value }) {
                LyricsWindow(lyrics = shown, height = height, listModifier = listModifier)
            }
        }
    }
}

/**
 * The words themselves, in a window that holds rather less than one song.
 *
 * Two things the lyrics page does not have to think about and this does.
 *
 * **The window has no edges**, and what that is allowed to cost was measured rather than assumed.
 * A list clipped to a box stops its top and bottom lines in mid-stroke, which reads as a layout
 * fault rather than as more song carrying on past the frame. The first answer was the obvious one —
 * an offscreen layer over the whole list and a `DstIn` gradient across it, which fades *content*
 * rather than painting a band of page colour over artwork that is showing through. It is also the
 * most expensive thing this screen could possibly do: the buffer is the size of the list and has to
 * be re-rendered on every frame whose content moved, which is every frame of a handover. Measured on
 * a release build: **15.3% of frames janky, 61ms at the 90th and 93ms at the 99th**, against 24/34ms
 * for the same player showing a cover. With the layer gone it is **2.7% janky, 30ms and 34ms** — the
 * whole of the difference, and the whole of what a user sees as a jerky animation. The fade is now
 * [edgeFade], a line at a time.
 *
 * **The list gives away what it cannot use.** A window this size holds a dozen lines, so a short
 * song does not scroll at all — and a scrollable that cannot scroll still swallows every vertical
 * drag inside itself, which would mean the push that puts the player away stopped working over half
 * of the player. [listModifier] carries the framework's nested-scroll hand-over for exactly that, the
 * same way the queue screen's list does: what the list has no use for becomes the page's travel.
 *
 * Which line is lit comes from [rememberLeadingLine], which watches the position itself and
 * recomposes this only on the frames where the line really changes — one every few seconds rather
 * than the four a second a collected position would cost. The player around it is recomposing on its
 * own position tick either way; that is what the stable-parameter discipline above is for.
 */
@Composable
private fun LyricsWindow(lyrics: Lyrics, height: Dp, listModifier: Modifier) {
    val services = LocalServices.current
    val leading = rememberLeadingLine(lyrics)
    val active = leading.index
    val handover = leading.handoverMs.toInt()

    // A fresh state per set of words, so a new song starts at its first line rather than wherever
    // the last one had been scrolled to.
    val listState = remember(lyrics) { LazyListState() }
    val following = rememberLyricsFollowing(listState)

    val rest = height * RestFraction
    val density = LocalDensity.current
    val restPx = with(density) { rest.roundToPx() }
    val windowPx = with(density) { height.roundToPx() }
    FollowActiveLine(listState, active, following, restPx, handover)

    LazyColumn(
        state = listState,
        // Only ever felt by a set of words too short to fill the window, and only the untimed ones
        // can be: a block of eight lines pinned to the top of a slot this tall reads as a window
        // that failed to load the rest. Timed words are anchored by the line being sung instead,
        // and centring them would fight the rest line for the same pixels.
        verticalArrangement =
            if (lyrics.synced) Arrangement.Top else Arrangement.Center,
        // Timed words sit under the rest line from the first one onwards, and can be scrolled past
        // the last; untimed words are a block of text you read from the top, so they start there —
        // but not *at* the top, or the first line spends the whole song inside the edge fade and is
        // the one line of the song that cannot be read. Clearing the fade band is the whole of it.
        contentPadding = if (lyrics.synced) {
            PaddingValues(top = rest, bottom = height - rest)
        } else {
            PaddingValues(top = height * FadeFraction, bottom = height * FadeFraction)
        },
        modifier = listModifier.fillMaxSize()
    ) {
        itemsIndexed(items = lyrics.lines, key = { index, _ -> index }) { index, line ->
            if (line.text.isBlank()) {
                // A gap in the file is a gap in the window — it is how an instrumental break reads.
                Spacer(Modifier.height(10.dp))
            } else {
                // The fade, and the alpha is read **in the layer block** rather than in composition.
                // A snapshot read in there invalidates the layer and nothing else, so a scroll
                // re-runs this ten times a frame and recomposes nothing — where the same read made
                // during composition would rebuild every visible line on every frame of a scroll.
                // `ModulateAlpha` is what keeps it honest: alpha on a layer otherwise buys an
                // offscreen buffer per line, which is the cost this whole approach exists to avoid.
                Box(
                    Modifier.graphicsLayer {
                        alpha = edgeFade(listState, index, windowPx)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                ) {
                LyricLineText(
                    text = line.text,
                    active = index == active,
                    // The file knows where the line starts to the hundredth of a second, which is
                    // exactly what the hairline under this square cannot give you.
                    onClick = { lyrics.startOf(index)?.let { services.player.seekTo(it) } },
                    size = WindowLineSize,
                    restingScale = WindowRestingScale,
                    // No gutter: the slot already begins on the gutter the artist, the album and
                    // the title are aligned to, so the words line up with the rest of the player.
                    horizontalPadding = 0.dp,
                    // Tight rows, generous space between lines. At this size most lines wrap, and
                    // the eye has to be able to tell the second row of one line from the first row
                    // of the next — which is leading against padding, not one number for both.
                    verticalPadding = 4.dp,
                    lineHeightScale = 1.12f,
                    // Exactly the lead this handover was given, so the line is at its full size on
                    // the beat rather than a third of a second after it — and so two lines close
                    // together hand over in the room there is between them.
                    handoverMillis = handover
                )
                }
            }
        }
    }
}

/**
 * How far in from the window's edge a line is, as an alpha.
 *
 * **A line at a time rather than a gradient across the glyphs**, which is the concession that makes
 * this free. Fading the ink itself means compositing the list offscreen and masking it, and that
 * buffer is re-rendered on every frame of every handover — the measurement is in [LyricsWindow].
 * Dimming whole lines needs nothing but an alpha each, and it is a look in its own right rather than
 * a compromise: what is leaving the window goes quietly instead of being guillotined by it.
 *
 * The line's *centre* is what is measured, so a line is already at nothing by the time the window
 * would have cut it. Positions come from the list rather than from the layout: an item inside a
 * `LazyColumn` cannot see where it was placed, and asking through `onGloballyPositioned` would mean
 * a state write per line per frame.
 *
 * [windowPx] is passed in rather than taken from `viewportEndOffset - viewportStartOffset`, which is
 * the window's height **plus both content paddings** — and this list's padding is most of a window.
 * `viewportStartOffset` on its own is sound: it is where the visible top edge sits in the same
 * coordinates the items are measured in, which is the fact [FollowActiveLine] rests on too.
 */
private fun GraphicsLayerScope.edgeFade(
    listState: LazyListState,
    index: Int,
    windowPx: Int
): Float {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 1f
    val band = windowPx * FadeFraction
    if (band <= 0f) return 1f
    val top = info.viewportStartOffset
    val bottom = top + windowPx
    val centre = item.offset + item.size / 2f
    return (minOf(centre - top, bottom - centre) / band).coerceIn(0f, 1f)
}
