package com.metromusic

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroTheme
import com.metromusic.core.LocalServices
import com.metromusic.core.Services
import com.metromusic.ui.PermissionGate
import com.metromusic.ui.nav.Screen

class MainActivity : ComponentActivity() {

    private lateinit var services: Services

    /**
     * Bumped every time an intent arriving at an activity that is *already running* asks for the
     * player to be shown — a file opened from a file manager, the home-screen tile tapped while the
     * app is in the background.
     *
     * A counter and not a flag: two files opened in a row are two requests, and the second one has to
     * reopen a player the user may have pushed down in between. The composition watches it and can
     * therefore keep owning `playerOpen`, which is the one thing that must not have two owners.
     */
    private var openPlayerRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars fully transparent, with no scrim of their own. The default `enableEdgeToEdge()`
        // puts a translucent scrim behind the navigation bar, which on a dark page reads as a pale
        // strip along the bottom edge where the gesture pill is — the app looks like it stops short
        // of the screen. The icons' colour is set from the theme below, in the composition.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(clear),
            navigationBarStyle = SystemBarStyle.dark(clear)
        )

        services = (application as MetroMusicApp).services
        // Home-screen tiles open the app with a target screen attached. The player is not one of
        // the screens — it rides over them — so it arrives as its own flag.
        val target = intent?.getStringExtra("screen")
        val startScreen = Screen.fromWidgetTarget(target)
        // A file handed to us by another app plays at once and lands on the player, which is the
        // whole of what tapping a song in a file manager means.
        val startOnPlayer = Screen.widgetTargetIsPlayer(target) || playRequestedFile(intent)

        setContent {
            CompositionLocalProvider(LocalServices provides services) {
                val settings by services.settings.settings.collectAsStateWithLifecycle()
                // "Follow the device" is read here, at the top, because a change to the system
                // setting has to re-theme everything: isSystemInDarkTheme() is state, so the whole
                // subtree recomposes when Android flips at sunset.
                val dark = if (settings.followSystemTheme) isSystemInDarkTheme() else settings.dark

                // The bars have no background of their own, so their icons have to be told which
                // way to contrast — light icons over a dark app, dark icons over a light one.
                //
                // Full screen is the status bar *hidden*, not covered: a hidden bar reports no inset,
                // so every page grows into the strip by the insets it already answers to and nothing
                // here lays out differently. The navigation bar stays — the gesture pill is how you
                // leave, and the app deliberately runs its lists under it either way.
                //
                // `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is what makes it a mode rather than a
                // one-way door: a swipe down from the top edge brings the clock back over the page
                // for a few seconds and it leaves again by itself, without the app hearing about it.
                val view = LocalView.current
                val fullScreen = settings.fullScreen
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if (fullScreen) hide(WindowInsetsCompat.Type.statusBars())
                        else show(WindowInsetsCompat.Type.statusBars())
                    }
                }

                MetroTheme(
                    accent = Color(settings.accentArgb),
                    dark = dark,
                    background = settings.backgroundArgb?.let(::Color)
                ) {
                    PermissionGate {
                        MetroMusicRoot(
                            initialScreen = startScreen,
                            openPlayerAtStart = startOnPlayer,
                            openPlayerRequests = openPlayerRequests
                        )
                    }
                }
            }
        }
    }

    /**
     * A second intent arriving at the instance already on screen, which `singleTop` is what makes
     * happen: another file opened while this one plays, or the tile tapped from the home screen.
     *
     * `setIntent` because `getIntent()` otherwise keeps answering with the one this activity was
     * created for — which is the whole of why the widget's `screen` extra never did anything against
     * a running app, and reads as the extra being ignored.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val wantsPlayer = playRequestedFile(intent) ||
            Screen.widgetTargetIsPlayer(intent.getStringExtra("screen"))
        if (wantsPlayer) openPlayerRequests++
    }

    /**
     * Plays whatever audio file [intent] points at, and says whether there was one.
     *
     * Only `ACTION_VIEW` counts. A launcher intent carries no data, and an intent that carries data
     * we cannot make sense of is left alone rather than guessed at — the player would answer a
     * broken uri with an error over the library, which is a worse outcome than opening on it.
     */
    private fun playRequestedFile(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        services.player.playFile(uri)
        return true
    }

    /**
     * Takes the volume keys before Android does, so the app can draw its own WP8 volume banner.
     *
     * Consuming the event is the whole trick: the system's volume panel is shown by the framework's
     * default handling, and returning true here means that handling never runs. Both down *and* up
     * have to be swallowed — leave the up event to the system and its panel appears at the end of
     * the press on some builds.
     *
     * This only works while the app has the window. From the lock screen or another app the events
     * never reach us and Android shows its own panel, which is the honest limit of restyling
     * something the OS owns.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            services.volume.nudge(+1)
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            services.volume.nudge(-1)
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
        else -> super.onKeyUp(keyCode, event)
    }
}
