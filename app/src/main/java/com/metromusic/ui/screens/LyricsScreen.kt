package com.metromusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroPage
import com.metrocompose.MetroProgressDots
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices

/** The three things this screen can be showing. Nulls could not tell the last two apart. */
private sealed interface LyricsUi {
    data object Looking : LyricsUi
    data class Words(val text: String) : LyricsUi
    data object None : LyricsUi
}

/**
 * A song's words.
 *
 * A page rather than an overlay on the player: reading lyrics is something you do *instead* of
 * watching the player, and as a page it gets Back, the turnstile and its own scroll position for
 * free. The text is fetched once and cached, so a song you have read before opens instantly and
 * offline.
 */
@Composable
fun LyricsScreen(trackId: Long) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val track = library.track(trackId)

    val ui by produceState<LyricsUi>(initialValue = LyricsUi.Looking, track) {
        // Reset first: `produceState` keeps its value across key changes and only restarts the
        // producer, so without this a second song would show the first one's words while it loads.
        value = LyricsUi.Looking
        val text = track?.let { services.lyrics.lyrics(it) }
        value = if (text.isNullOrBlank()) LyricsUi.None else LyricsUi.Words(text)
    }

    MetroPage(
        overline = track?.artist?.uppercase() ?: stringResource(R.string.overline_lyrics),
        title = track?.title ?: stringResource(R.string.unknown)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp)
        ) {
            when (val state = ui) {
                LyricsUi.Looking -> MetroProgressDots()
                LyricsUi.None -> Text(
                    text = stringResource(R.string.lyrics_none),
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 18.sp
                )
                is LyricsUi.Words -> {
                    Text(
                        text = state.text,
                        color = colors.fg,
                        fontFamily = MetroRegular,
                        fontSize = 18.sp,
                        lineHeight = 28.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.lyrics_source),
                        color = colors.dim,
                        fontFamily = MetroRegular,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
