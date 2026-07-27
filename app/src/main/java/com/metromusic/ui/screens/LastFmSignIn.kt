package com.metromusic.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroButton
import com.metrocompose.MetroLight
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.lastfm.LastFmClient
import com.metromusic.data.lastfm.lastFmKey
import com.metromusic.data.lastfm.lastFmSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Last.fm sign-in: their own login page in a web view, and a quiet loop waiting for the token it
 * authorises to become a session.
 *
 * The flow is theirs and cannot be shortened: ask for a request token, send the user to
 * `last.fm/api/auth` with it, and only once they have logged in and pressed *yes* does
 * `auth.getSession` start answering with a session key. Nothing here ever sees the password — the
 * page is Last.fm's, served over their TLS, and this only learns the key at the end.
 *
 * Polling rather than watching the web view's URL: Last.fm's confirmation page is a page like any
 * other, its address has changed before, and a redirect is easy to miss on a slow connection.
 * Asking the API whether the token is live yet is the question we actually care about.
 */
@Composable
fun LastFmSignIn(onDone: () -> Unit) {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()

    val client = remember(settings.lastFmKey(), settings.lastFmSecret()) {
        LastFmClient(settings.lastFmKey(), settings.lastFmSecret())
    }
    var url by remember { mutableStateOf<String?>(null) }
    val asking = stringResource(R.string.lastfm_asking)
    val noAnswer = stringResource(R.string.lastfm_no_answer)
    val authorize = stringResource(R.string.lastfm_authorize)
    val gaveUp = stringResource(R.string.lastfm_gave_up)
    var status by remember { mutableStateOf(asking) }

    LaunchedEffect(client) {
        val token = withContext(Dispatchers.IO) { client.requestToken() }
        if (token == null) {
            status = noAnswer
            return@LaunchedEffect
        }
        url = client.authorizeUrl(token)
        status = authorize

        repeat(PollAttempts) {
            delay(PollIntervalMs)
            val session = withContext(Dispatchers.IO) { client.session(token) }
            if (session != null) {
                services.settings.setLastfmSession(session.user, session.key)
                // Anything that piled up while signed out can go now.
                services.scrobbler.flush()
                onDone()
                return@LaunchedEffect
            }
        }
        status = gaveUp
    }

    Popup(properties = PopupProperties(focusable = true), onDismissRequest = onDone) {
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            Column(Modifier.padding(start = 22.dp, top = 46.dp, end = 22.dp, bottom = 10.dp)) {
                Text(
                    text = stringResource(R.string.settings_lastfm),
                    color = colors.fg,
                    fontFamily = MetroLight,
                    fontSize = 44.sp
                )
                Text(
                    text = status,
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 14.sp
                )
            }

            val target = url
            if (target != null) {
                // One web view for the life of this screen; `update` would reload the page on
                // every recomposition if it did not check.
                var loaded by remember { mutableStateOf<String?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        // Not `apply { settings… }`: `settings` here would resolve to this
                        // screen's own settings object, not the web view's.
                        val web = WebView(context)
                        web.webViewClient = WebViewClient()
                        // Last.fm's login form does not submit without it.
                        web.settings.javaScriptEnabled = true
                        web.settings.domStorageEnabled = true
                        web
                    },
                    update = { view ->
                        if (loaded != target) {
                            loaded = target
                            view.loadUrl(target)
                        }
                    }
                )
            } else {
                Box(Modifier.fillMaxWidth().weight(1f))
            }

            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetroButton(stringResource(R.string.action_done), onClick = onDone)
            }
        }
    }
}

private const val PollIntervalMs = 3_000L
private const val PollAttempts = 40
