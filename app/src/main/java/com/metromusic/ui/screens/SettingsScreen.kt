package com.metromusic.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrocompose.ListRow
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroTheme
import com.metromusic.R
import com.metromusic.ui.nav.Screen
import com.metromusic.ui.nav.SettingsPage

/**
 * Settings as a page, for the ways in that are not the panorama: the home-screen tile's
 * `screen=Settings` extra, and a restored back stack that was inside settings when the process died.
 *
 * The same index the panorama's settings section shows, deliberately — there is one list of settings
 * pages in this app, and it should be the same list wherever you meet it.
 */
@Composable
fun SettingsScreen(onNavigate: (Screen) -> Unit) {
    val colors = MetroTheme.colors

    MetroPage(stringResource(R.string.overline_app), stringResource(R.string.section_settings)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SettingsPage.entries.forEach { page ->
                ListRow(
                    primary = stringResource(titleOf(page)),
                    secondary = stringResource(subtitleOf(page)),
                    onClick = { onNavigate(Screen.SettingsDetail(page)) }
                )
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.settings_credit),
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/**
 * The name and the one-line description of each settings page, as resource ids.
 *
 * Ids rather than strings so both the panorama's section and this page can use them, and neither has
 * to be composable just to name a row.
 */
@StringRes
internal fun titleOf(page: SettingsPage): Int = when (page) {
    SettingsPage.Theme -> R.string.settings_theme
    SettingsPage.Interface -> R.string.settings_interface
    SettingsPage.Gestures -> R.string.settings_gestures
    SettingsPage.Equalizer -> R.string.settings_equalizer
    SettingsPage.LastFm -> R.string.settings_lastfm
    SettingsPage.Playback -> R.string.settings_playback
    SettingsPage.Library -> R.string.settings_library
    SettingsPage.Hidden -> R.string.settings_hidden
}

@StringRes
internal fun subtitleOf(page: SettingsPage): Int = when (page) {
    SettingsPage.Theme -> R.string.settings_theme_hint
    SettingsPage.Interface -> R.string.settings_interface_hint
    SettingsPage.Gestures -> R.string.settings_gestures_hint
    SettingsPage.Equalizer -> R.string.settings_equalizer_hint
    SettingsPage.LastFm -> R.string.settings_lastfm_hint
    SettingsPage.Playback -> R.string.settings_playback_hint
    SettingsPage.Library -> R.string.settings_library_hint
    SettingsPage.Hidden -> R.string.settings_hidden_hint
}
