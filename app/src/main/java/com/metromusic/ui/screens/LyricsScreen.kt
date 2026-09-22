package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroBottomInset
import com.metrocompose.MetroPage
import com.metrocompose.MetroProgressDots
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.lyrics.Lyrics
import com.metromusic.data.lyrics.LyricsOrigin
import com.metromusic.ui.components.FollowActiveLine
import com.metromusic.ui.components.LyricLineText
import com.metromusic.ui.components.rememberLeadingLine
import com.metromusic.ui.components.rememberLyricsFollowing

/** The three things this screen can be showing. Nulls could not tell the last two apart. */
private sealed interface LyricsUi {
    data object Looking : LyricsUi
    data class Words(val lyrics: Lyrics) : LyricsUi
    data object None : LyricsUi
}

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

    // Asked again when a network arrives, the same way the player's words are: a page that opened
    // in a tunnel and said "no lyrics" is answering about the network rather than about the song.
    val retries by services.lyrics.retries.collectAsStateWithLifecycle()

    val ui by produceState<LyricsUi>(initialValue = LyricsUi.Looking, track, retries) {
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
 * The line itself, when a page stops following a hand that is reading it, and how the sung line is
 * brought to its resting place are all [com.metromusic.ui.components.LyricLineText] and its
 * neighbours: the player's square does the same things in a tenth of the room, and the two must not
 * drift apart.
 */
@Composable
private fun TimedWords(lyrics: Lyrics) {
    val services = LocalServices.current
    val listState = rememberLazyListState()

    // **Which line, and when to start moving to it.** Not `lineAt(position)`: a handover that begins
    // when the first word arrives is at its full size a third of a second later, so the emphasis
    // spends the whole song a beat behind the voice. The timings say when the next line starts, so
    // [rememberLeadingLine] begins the change early enough to *land* on it — and recomposes this only
    // on the frames where the line really changes, which is one every few seconds rather than the
    // four a second a collected position would cost.
    val leading = rememberLeadingLine(lyrics)
    val active = leading.index

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

    val following = rememberLyricsFollowing(listState)
    FollowActiveLine(listState, active, following, restOffset, leading.handoverMs.toInt())

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
                    onClick = { lyrics.startOf(index)?.let { services.player.seekTo(it) } },
                    handoverMillis = leading.handoverMs.toInt()
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
