package com.metromusic.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroPage
import com.metrocompose.MetroProgressDots
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroSlideInEasing
import com.metrocompose.MetroSlideInMillis
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.lyrics.Lyrics
import com.metromusic.data.lyrics.LyricsOrigin
import kotlinx.coroutines.delay

/** The three things this screen can be showing. Nulls could not tell the last two apart. */
private sealed interface LyricsUi {
    data object Looking : LyricsUi
    data class Words(val lyrics: Lyrics) : LyricsUi
    data object None : LyricsUi
}

/**
 * How often the position is asked for while a timed page is open.
 *
 * A line is therefore at worst this late, which is well under what the eye reads as a delay against
 * a line being sung over several seconds — and four ticks a second cost nothing, because
 * `positionFlow` only runs while something is collecting it.
 */
private const val TickMs = 250L

/** How long a page that was scrolled by hand stays where it was put. */
private const val ResumeFollowingMs = 6_000L

/**
 * The size every line is **laid out** at, and the fraction a line that is not being sung is **drawn**
 * at — which are two different things on purpose. See [LyricLineText].
 */
private val LineSize = 28.sp
private const val RestingScale = 0.70f

/** How far the line taking over travels in from the right. */
private val Travel = 22.dp

/**
 * How long the handover takes.
 *
 * Deliberately well short of the framework's own [MetroSlideInMillis], which is the length of a
 * whole track change: lines can be a second and a half apart, and a growth still finishing when the
 * next one starts reads as lag rather than as motion.
 */
private const val LineGrowMillis = 320

/**
 * A song's words.
 *
 * A page rather than an overlay on the player: reading lyrics is something you do *instead* of
 * watching the player, and as a page it gets Back, the turnstile and its own scroll position for
 * free. The words come from a file on the device if there is one and from Genius otherwise, and are
 * cached, so a song you have read before opens instantly and offline.
 *
 * **Timed words follow the music.** A `.lrc` carries a timestamp per line, and when the track being
 * shown is also the track being played the page highlights the line that is being sung, scrolls it
 * into place, and seeks to it if you tap it. All of that is conditional on the *file* rather than on
 * a setting — a plain file, and anything fetched from Genius, is a page you scroll, because that is
 * all the words themselves support.
 *
 * Two conditions there, not one, and the second is easy to forget: lyrics open from a list as well
 * as from the player, so the song on screen is frequently not the song playing. Following the
 * position then would highlight a line of one song at the position of another.
 */
@Composable
fun LyricsScreen(trackId: Long) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val playerState by services.player.state.collectAsStateWithLifecycle()
    val track = library.track(trackId)

    val ui by produceState<LyricsUi>(initialValue = LyricsUi.Looking, track) {
        // Reset first: `produceState` keeps its value across key changes and only restarts the
        // producer, so without this a second song would show the first one's words while it loads.
        value = LyricsUi.Looking
        val lyrics = track?.let { services.lyrics.lyrics(it) }
        value = if (lyrics == null) LyricsUi.None else LyricsUi.Words(lyrics)
    }

    val words = (ui as? LyricsUi.Words)?.lyrics
    val follows = words != null && words.synced && playerState.trackId == trackId

    MetroPage(
        overline = track?.artist?.uppercase() ?: stringResource(R.string.overline_lyrics),
        title = track?.title ?: stringResource(R.string.unknown)
    ) {
        when {
            ui is LyricsUi.Looking -> Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                MetroProgressDots()
            }
            words == null -> ReadingPage {
                Text(
                    text = stringResource(R.string.lyrics_none),
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 18.sp
                )
            }
            follows -> TimedWords(words)
            else -> ReadingPage {
                Text(
                    text = words.plainText,
                    color = colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 18.sp,
                    lineHeight = 28.sp
                )
                Spacer(Modifier.height(24.dp))
                Credit(words)
            }
        }
    }
}

/** The flat page: one block of text you scroll yourself, which is all untimed words can be. */
@Composable
private fun ReadingPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 40.dp)
    ) {
        content()
    }
}

/**
 * Words that follow the music: the line being sung is bright, the rest are not, and tapping one
 * seeks to it.
 *
 * **A hand on the list wins.** Auto-scrolling a page somebody is reading is the way this feature is
 * usually got wrong: they scroll back to a verse and the page drags itself away mid-sentence. A drag
 * stops the page following, and it takes [ResumeFollowingMs] of stillness to start again — long
 * enough to read a verse, short enough that the page is not left behind the song for good. The list's
 * own interactions say when that happened, rather than a second detector laid over it: an overlay
 * takes the hit test from the rows underneath, which is the trap the edge scrubber is built around,
 * and here it would eat the tap that seeks.
 */
@Composable
private fun TimedWords(lyrics: Lyrics) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val position by services.player.positionFlow(TickMs).collectAsStateWithLifecycle(0L)
    val listState = rememberLazyListState()

    // Derived, so four ticks a second do not recompose the page four times a second: only the frames
    // where the line actually changes are worth anything, and there is one of those every few
    // seconds. The same reason the edge scrubber splits its derived state.
    val active by remember(lyrics) { derivedStateOf { lyrics.lineAt(position) } }

    // A third of the way down rather than at the top: what has just been sung is as much of the
    // context as what is coming, and a line pinned against the top edge reads as a page that has been
    // cut off. Measured off the list rather than assumed — the viewport is what the page title leaves,
    // and that title rolls away as this list is scrolled, so it is not a constant.
    val restOffset by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.viewportEndOffset - info.viewportStartOffset) / 3
        }
    }

    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(following) {
        if (!following) {
            delay(ResumeFollowingMs)
            following = true
        }
    }

    LaunchedEffect(active, following, restOffset) {
        if (!following || active < 0 || restOffset <= 0) return@LaunchedEffect
        runCatching {
            val onScreen = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            if (onScreen != null) {
                // The line is already on screen, so the exact distance is known — and scrolling it on
                // the *same* curve and length as the growth is what makes the two read as one
                // movement rather than as a page that shifts while a word swells. `animateScrollBy`
                // is the only one of the two that takes a spec; `animateScrollToItem` brings its own.
                listState.animateScrollBy(
                    (onScreen.offset - restOffset).toFloat(),
                    tween(LineGrowMillis, easing = MetroSlideInEasing)
                )
            } else {
                // Nowhere near it — after a seek, or after the page was scrolled away by hand. There
                // is no distance to share a curve with, so this is a jump that lands correctly.
                listState.animateScrollToItem(active, -restOffset)
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(
            items = lyrics.lines,
            key = { index, _ -> index }
        ) { index, line ->
            if (line.text.isBlank()) {
                // A gap in the file is a gap on the page — it is how an instrumental break reads.
                Spacer(Modifier.height(14.dp))
            } else {
                LyricLineText(
                    text = line.text,
                    active = index == active,
                    // Seeking to the line you are looking at: the file knows where it is to the
                    // hundredth of a second, which is exactly what the scrubber cannot give you.
                    onClick = { lyrics.startOf(index)?.let { services.player.seekTo(it) } }
                )
            }
        }

        item(key = "credit") {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.padding(horizontal = 24.dp)) { Credit(lyrics) }
            MetroBottomInset(40.dp)
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
 *    its target's height is changing underneath it, so it re-aims and the page's speed steps.
 *
 * So the text is measured once, at [LineSize], and the emphasis is a **draw-time** transform: a
 * `graphicsLayer` scale from [RestingScale] up to 1. Layout is then constant — no reflow, no height
 * change, nothing for the scroll to chase — and the rows a wrapped line occupies are settled before
 * any animation starts. Scaling *down* to rest rather than up to emphasis is deliberate: a layer is
 * rasterised at its layout size, so drawing 28sp text at 0.70 is a downsample and stays crisp, where
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
 * [emphasis], because emphasis is a *state*: an offset computed from it would leave every resting
 * line permanently shifted to the right by the part of the curve it never travelled. It is skipped on
 * first composition — the framework's own `metroSlideIn` idiom — or a line that merely scrolled back
 * into view would fly in as though it had just been reached.
 */
@Composable
private fun LyricLineText(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = MetroTheme.colors
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(LineGrowMillis, easing = MetroSlideInEasing),
        label = "lyric-emphasis"
    )
    val travel = remember { Animatable(0f) }
    val composed = remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        val first = !composed.value
        composed.value = true
        if (active && !first) {
            travel.snapTo(1f)
            travel.animateTo(0f, tween(LineGrowMillis, easing = MetroSlideInEasing))
        } else {
            travel.snapTo(0f)
        }
    }

    val travelPx = with(LocalDensity.current) { Travel.toPx() }
    Text(
        text = text,
        color = lerp(colors.subtle, colors.accent, emphasis),
        // Semilight throughout, rather than a weight that changes with the size: a face that swapped
        // at some point during the growth would pop, and the growth is the effect.
        fontFamily = MetroSemilight,
        fontSize = LineSize,
        lineHeight = LineSize * 1.3f,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row takes the tap, not the glyphs.
            .clickable(onClick = onClick)
            // Padding outside the layer, so the transform's origin is the *text's* left edge. Inside
            // it, the 24dp margin would scale too and resting lines would sit indented differently
            // from the one being sung.
            .padding(horizontal = 24.dp, vertical = 5.dp)
            .graphicsLayer {
                val scale = RestingScale + (1f - RestingScale) * emphasis
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                translationX = travel.value * travelPx
            }
    )
}

/** The source line under the words: which file they came out of, or the site they were fetched from. */
@Composable
private fun Credit(lyrics: Lyrics) {
    val colors = MetroTheme.colors
    Text(
        text = when (lyrics.origin) {
            LyricsOrigin.Genius -> stringResource(R.string.lyrics_source)
            LyricsOrigin.LrcLib -> stringResource(R.string.lyrics_source_lrclib)
            LyricsOrigin.Lrc -> lyrics.sourceName?.let {
                stringResource(R.string.lyrics_source_file, it)
            } ?: stringResource(R.string.lyrics_source_file_unnamed)
            LyricsOrigin.Tag -> lyrics.sourceName?.let {
                stringResource(R.string.lyrics_source_tag, it)
            } ?: stringResource(R.string.lyrics_source_file_unnamed)
        },
        color = colors.dim,
        fontFamily = MetroRegular,
        fontSize = 12.sp
    )
}
