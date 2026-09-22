package com.metromusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroSlideInEasing
import com.metrocompose.MetroSlideInMillis
import com.metrocompose.MetroTheme
import com.metromusic.core.LocalServices
import com.metromusic.data.lyrics.LeadingLine
import com.metromusic.data.lyrics.Lyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * One line of a song, and the two rules that make a list of them follow the music.
 *
 * This lives here rather than on the lyrics page because there are two of them now: the page, and
 * the window the player draws where its cover goes. They are different sizes and hold different
 * amounts of song, and everything else about them — what a line does when it is reached, when a list
 * stops following a hand that is reading it, where down the list the sung line rests — has to be the
 * same, or the smaller one becomes a place for the lessons below to be quietly relearnt.
 */

/**
 * How often the position is asked for while a timed set of words is on screen.
 *
 * A line is therefore at worst this late, which is well under what the eye reads as a delay against
 * a line being sung over several seconds — and four ticks a second cost nothing, because
 * `positionFlow` only runs while something is collecting it.
 */
const val LyricTickMs = 250L

/** How long a list that was scrolled by hand stays where it was put. */
const val LyricResumeFollowingMs = 6_000L

/**
 * The size a line on the lyrics page is **laid out** at, and the fraction a line that is not being
 * sung is **drawn** at — which are two different things on purpose. See [LyricLineText].
 */
val LyricLineSize = 28.sp
const val LyricRestingScale = 0.70f

/** How far the line taking over travels in from the right. */
private val Travel = 22.dp

/**
 * How long a handover takes when nothing says otherwise — the growth, the travel and the
 * scroll that carries them, all three on this number and on [MetroSlideInEasing], because that is
 * what makes them read as one movement instead of three things happening at once.
 *
 * It was 320ms while the sung line was barely larger than its neighbours. It is not any more: the
 * player's window scales a line by nearly two, and a change that big taken at the old speed is a
 * snap rather than a handover. Still short of the framework's own [MetroSlideInMillis], which is
 * the length of a whole track change, because lines can be a second and a half apart and a growth
 * still finishing when the next one starts reads as lag.
 *
 * It is the default rather than the rule: where the words carry timings, each handover is given
 * [Lyrics.leadingAt]'s own length instead, which is what makes the lines flow into one another at
 * the speed the song is actually moving at. This is what is left — the first line of a song, and any
 * set of words with no timings in it at all.
 *
 * A `tween` and not a spring, which is the one place this project departs from what 1.2 concluded:
 * a spring has no duration to share, and sharing a duration with the scroll is the whole point
 * here. Nothing is following a finger, so there is no velocity to carry either.
 */
const val LyricGrowMillis = 440

/**
 * The longest a handover is allowed to take, and equally the furthest ahead of the voice it may
 * start — because those are the same number: the change begins this long before the line's first
 * word and *lands* on it.
 *
 * **A line that only begins growing when it is sung is at its full size a third of a second late**,
 * so the emphasis spends the whole song a beat behind the music. The timings are right there and say
 * exactly when the next line begins, which is what makes anticipating it honest rather than a guess.
 *
 * Every handover shorter than this is a *gap* being shorter than this: [Lyrics.leadingAt] gives each
 * one half the room between its line and the one before, so lines a second apart flow over half a
 * second and a rapid pair hands over in a fifth of one. That is the whole of "smoothly" here — the
 * movement takes its speed from the song rather than from a constant, and there is never a moment
 * where nothing is happening followed by a jump.
 *
 * Just under the framework's own `MetroSlideInMillis`, which is the length of a whole page turn: a
 * line of a song is a smaller event than that, however long the instrumental break before it was.
 */
const val LyricLeadMillis = 560L

/**
 * Which line is lit right now, moved on **at the moment itself** rather than at whatever poll
 * happens to notice.
 *
 * The position is asked for four times a second, which is cheap and is plenty for deciding *which*
 * line is current — but it is not enough to *start* anything, because a quarter of a second of
 * quantisation on top of a handover is the emphasis arriving half a second after the voice. So the
 * poll is only the sync: each one recomputes where we are and then **sleeps until exactly the next
 * handover**, and the next poll cancels that sleep and re-arms it from a fresh reading. Drift cannot
 * accumulate, and the last arming before any boundary is within 250ms of it, which a `delay` hits
 * to the millisecond.
 *
 * It stops scheduling while the music is paused, or the sleep would walk the emphasis on through a
 * song that is not playing. Both facts are read inside the effect rather than collected into the
 * composition: `PlayerState` is rebuilt on every player event, and this screen has no reason to
 * recompose for any of them.
 */
@Composable
fun rememberLeadingLine(lyrics: Lyrics): LeadingLine {
    val services = LocalServices.current
    var leading by remember(lyrics) {
        mutableStateOf(LeadingLine(-1, LyricGrowMillis.toLong(), null))
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(lyrics, services, owner) {
        if (!lyrics.synced) return@LaunchedEffect
        // Lifecycle-aware, like the `collectAsStateWithLifecycle` this replaced: the player's page
        // stays composed behind a home press, and a poll plus a timer running through it would be
        // this screen alone keeping the app busy while nothing of it is on screen.
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val playing = services.player.state.map { it.isPlaying }.distinctUntilChanged()
            combine(services.player.positionFlow(LyricTickMs), playing, ::Pair)
                .collectLatest { (polled, isPlaying) ->
                    var at = polled
                    // Each poll re-arms from a fresh reading, so this loop normally runs once and is
                    // cancelled by the next one. It is a loop for the case where it is not — a line
                    // whose successor falls between two polls — and for keeping the emphasis moving
                    // if the poll were ever the slower of the two.
                    while (true) {
                        val now = lyrics.leadingAt(at, LyricLeadMillis)
                        leading = now
                        val next = now.nextAtMs
                        if (!isPlaying || next == null) return@collectLatest
                        delay((next - at).coerceAtLeast(1L))
                        at = next
                    }
                }
        }
    }
    return leading
}

/**
 * Whether a list of lyrics should be following the music, given what hands have been doing to it.
 *
 * **A hand on the list wins.** Auto-scrolling a page somebody is reading is the way this feature is
 * usually got wrong: they scroll back to a verse and the page drags itself away mid-sentence. A drag
 * stops it following, and it takes [LyricResumeFollowingMs] of stillness to start again — long
 * enough to read a verse, short enough that the words are not left behind the song for good. The
 * list's own interactions say when that happened, rather than a second detector laid over it: an
 * overlay takes the hit test from the rows underneath, which is the trap the edge scrubber is built
 * around, and here it would eat the tap that seeks.
 */
@Composable
fun rememberLyricsFollowing(listState: LazyListState): Boolean {
    var following by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(following) {
        if (!following) {
            delay(LyricResumeFollowingMs)
            following = true
        }
    }
    return following
}

/**
 * Keeps the line at [active] [restOffset] pixels down the list, for as long as [following] says to.
 *
 * Two ways of getting there, and which one is right depends on whether that line is already on
 * screen. When it is, the distance is known exactly, and travelling it on the *same* curve and
 * length as the line's own growth is what makes the two read as one movement rather than as a list
 * that shifts while a word swells; `animateScrollBy` is the only one of the two that takes a spec.
 * `animateScrollToItem` brings its own curve and is kept for the case where the target is nowhere
 * near — after a seek, or after the list was scrolled away by hand — where there is no distance to
 * share a curve with and the job is simply to land correctly.
 */
@Composable
fun FollowActiveLine(
    listState: LazyListState,
    active: Int,
    following: Boolean,
    restOffset: Int,
    handoverMillis: Int = LyricGrowMillis
) {
    LaunchedEffect(active, following, restOffset) {
        if (!following || active < 0 || restOffset <= 0) return@LaunchedEffect
        runCatching {
            // [restOffset] is measured from the *visible* top edge, and an item's own offset is not:
            // a list with content padding above it reports `viewportStartOffset` as minus that
            // padding, and every item is measured from there. Adding it is the difference between a
            // window whose sung line sits where it was asked to and one that sits a whole padding
            // lower — invisible on the lyrics page, which has no padding, and exactly what the
            // player's square showed the first time it was tried on a device.
            val target = listState.layoutInfo.viewportStartOffset + restOffset
            val onScreen = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            if (onScreen != null) {
                listState.animateScrollBy(
                    (onScreen.offset - target).toFloat(),
                    tween(handoverMillis, easing = MetroSlideInEasing)
                )
            } else {
                listState.animateScrollToItem(active, -target)
            }
        }
    }
}

/**
 * One line, which grows into the one being sung and settles back out of it.
 *
 * **The line changes size, not only colour.** A colour change on its own is a cue you have to be
 * looking for; the phone's idiom is that the thing you are meant to read is *bigger than everything
 * around it*, and at a glance from across a desk the size is what you see.
 *
 * **But the size it is laid out at never changes, and that is the whole of why this is smooth.** The
 * first version animated `fontSize`, which is a *layout* property, and it was reported as jerky. The
 * measurement said the frames were fine — 1274 of them, 6 janky, a 20ms median — so nothing was being
 * dropped; the movement itself had a step in it, which is the same thing that made the gestures feel
 * rough in 1.2. Two steps, in fact, and both come from measuring an animated size:
 *
 *  - **the line re-wraps mid-growth.** At 24dp margins on a 411dp screen a line fits about 40
 *    characters at the resting size and about 25 at the grown one, and lyrics are mostly 25–45
 *    characters long — so the common case is that the text reflows from one row to two *part way
 *    through the animation*, and the item's height jumps in a single frame, taking every line below
 *    it along;
 *  - **the scroll re-targets against it.** `animateScrollToItem` is flying at the same moment, and
 *    its target's height is changing underneath it, so it re-aims and the list's speed steps.
 *
 * So the text is measured once, at [size], and the emphasis is a **draw-time** transform: a
 * `graphicsLayer` scale from [restingScale] up to 1. Layout is then constant — no reflow, no height
 * change, nothing for the scroll to chase — and the rows a wrapped line occupies are settled before
 * any animation starts. Scaling *down* to rest rather than up to emphasis is deliberate: a layer is
 * rasterised at its layout size, so drawing the text at 0.70 is a downsample and stays crisp, where
 * laying out at the small size and magnifying the important line would blur precisely the line the
 * reader is on.
 *
 * **And it arrives the way WP8 brings text in.** [MetroSlideInEasing] is the framework's own curve:
 * a gentle push off and then a long glide home, which is what makes an element look *set moving*
 * rather than cut to. The line travels [Travel] in from the right as it takes over — the direction
 * everything on this phone enters from — while the one it replaces merely settles back. Two lines
 * flying at once would read as a shuffle rather than as a handover.
 *
 * The travel is a one-shot on *becoming* current, held in an `Animatable` rather than derived from
 * the emphasis, because emphasis is a *state*: an offset computed from it would leave every resting
 * line permanently shifted to the right by the part of the curve it never travelled. It is skipped
 * on first composition — the framework's own `metroSlideIn` idiom — or a line that merely scrolled
 * back into view would fly in as though it had just been reached.
 *
 * **And it starts during composition rather than in the effect that follows it.** Putting the line
 * out to the right with a `snapTo` in a `LaunchedEffect` costs a frame, because effects are
 * dispatched on the frame after the one that composed them: the first frame of a handover then has
 * the line already growing and still in its resting place, and the second jumps it to the right.
 * The eye reads that as the line stepping backwards before it comes in — a stumble at exactly the
 * moment it is being looked at, and the larger the line the more of it there is to see. A fresh
 * `Animatable` per state of [active] is at the right value on the first frame instead.
 *
 * [horizontalPadding] is the gutter, and it is **outside** the layer on purpose: inside it the
 * margin would scale too and resting lines would sit indented differently from the one being sung.
 * The player's window passes zero, because the slot it is drawn in already starts on the gutter
 * that the rest of that screen's text is aligned to.
 *
 * [lineHeightScale] is the leading, and it is a *parameter* because it is the price of a large sung
 * line. At the size the player's window lays out at, most lines of an ordinary verse wrap onto two
 * rows — that is simply what a phone's width and type that large come to — and at the page's 1.3 the
 * two rows of one line end up as far apart as two separate lines, so a wrapped line reads as two.
 * Tightened, the pair hugs and the space *between* lines is [verticalPadding], which is the
 * distinction the eye actually needs. The page keeps 1.3: it is a reading surface, its lines rarely
 * wrap, and leading is what makes a page of text readable rather than dense.
 */
/**
 * Whether a line has been composed before, which is what tells a line that is *arriving* from one
 * that was simply scrolled back into view.
 *
 * A plain class field and deliberately not snapshot state: it is written during composition, and a
 * state written there is a state something else may have read, which is how a recomposition loop
 * starts. Nothing reads this reactively — it only decides where an `Animatable` begins.
 */
private class FirstComposition {
    var first = true
}

@Composable
fun LyricLineText(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    size: TextUnit = LyricLineSize,
    restingScale: Float = LyricRestingScale,
    horizontalPadding: Dp = 24.dp,
    verticalPadding: Dp = 5.dp,
    lineHeightScale: Float = 1.3f,
    handoverMillis: Int = LyricGrowMillis
) {
    val colors = MetroTheme.colors
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(handoverMillis, easing = MetroSlideInEasing),
        label = "lyric-emphasis"
    )
    val once = remember { FirstComposition() }
    val travel = remember(active) {
        val entering = active && !once.first
        once.first = false
        Animatable(if (entering) 1f else 0f)
    }
    LaunchedEffect(travel) {
        if (travel.value != 0f) {
            travel.animateTo(0f, tween(handoverMillis, easing = MetroSlideInEasing))
        }
    }

    val travelPx = with(LocalDensity.current) { Travel.toPx() }
    Text(
        text = text,
        color = lerp(colors.subtle, colors.accent, emphasis),
        // Semilight throughout, rather than a weight that changes with the size: a face that swapped
        // at some point during the growth would pop, and the growth is the effect.
        fontFamily = MetroSemilight,
        fontSize = size,
        lineHeight = size * lineHeightScale,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row takes the tap, not the glyphs.
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .graphicsLayer {
                val scale = restingScale + (1f - restingScale) * emphasis
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                translationX = travel.value * travelPx
            }
    )
}
