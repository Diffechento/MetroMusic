package com.metromusic

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroTheme
import com.metromusic.core.LocalServices
import com.metromusic.core.Services
import com.metromusic.ui.PermissionGate
import com.metromusic.ui.nav.Screen

class MainActivity : ComponentActivity() {

    private lateinit var services: Services

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
        val startOnPlayer = Screen.widgetTargetIsPlayer(target)

        setContent {
            CompositionLocalProvider(LocalServices provides services) {
                val settings by services.settings.settings.collectAsStateWithLifecycle()
                // "Follow the device" is read here, at the top, because a change to the system
                // setting has to re-theme everything: isSystemInDarkTheme() is state, so the whole
                // subtree recomposes when Android flips at sunset.
                val dark = if (settings.followSystemTheme) isSystemInDarkTheme() else settings.dark

                // The bars have no background of their own, so their icons have to be told which
                // way to contrast — light icons over a dark app, dark icons over a light one.
                val view = LocalView.current
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
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
                            openPlayerAtStart = startOnPlayer
                        )
                    }
                }
            }
        }
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
