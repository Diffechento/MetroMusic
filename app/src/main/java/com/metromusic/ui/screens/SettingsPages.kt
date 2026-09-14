package com.metromusic.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.ListRow
import com.metrocompose.MetroAccents
import com.metrocompose.MetroButton
import com.metrocompose.MetroListBox
import com.metrocompose.MetroPage
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroSlider
import com.metrocompose.MetroTextBox
import com.metrocompose.MetroTheme
import com.metrocompose.SettingRow
import com.metromusic.R
import com.metromusic.core.LocalServices
import com.metromusic.data.lastfm.lastFmConfigured
import com.metromusic.data.lyrics.lyricsFolderName
import com.metromusic.data.store.ArtistAlbumOrder
import com.metromusic.data.store.Hidden
import com.metromusic.data.store.LyricsSource
import com.metromusic.ui.formatAlbumCount
import com.metromusic.ui.formatArtistCount
import com.metromusic.ui.formatDuration
import com.metromusic.ui.formatTrackCount
import com.metromusic.ui.formatWhen
import com.metromusic.ui.nav.SettingsPage
import java.util.Locale

/**
 * One page of settings.
 *
 * Settings are pages rather than one long scroll because that is how the phone did it and because
 * the panorama's settings section is already the index — a second index inside a single page would
 * be one level of scrolling too many. The dispatch lives here so the shell has one branch to add.
 */
@Composable
fun SettingsDetailScreen(page: SettingsPage) {
    when (page) {
        SettingsPage.Theme -> ThemeSettings()
        SettingsPage.Interface -> InterfaceSettings()
        SettingsPage.Equalizer -> EqualizerSettings()
        SettingsPage.LastFm -> LastFmSettings()
        SettingsPage.Playback -> PlaybackSettings()
        SettingsPage.Library -> LibrarySettings()
        SettingsPage.Gestures -> GestureSettings()
        SettingsPage.Hidden -> HiddenSettings()
        SettingsPage.About -> AboutSettings()
    }
}

/**
 * Every gesture the app adds on top of tapping, each with a switch.
 *
 * Switchable because a gesture that fires by accident is worse than no gesture — a strip swiped while
 * scrolling past it changes the song, and a phone in a thick case makes the edges hard to avoid.
 * Tapping is not on this page: it always works, and an app whose taps are configurable is a puzzle.
 */
@Composable
private fun GestureSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()

    SettingsPageFrame(stringResource(R.string.settings_gestures)) {
        SettingsHeader(stringResource(R.string.interface_strip))
        SettingRow(
            title = stringResource(R.string.gesture_strip_swipe),
            checked = settings.gestureStripSwipe,
            onChange = { services.settings.setGestureStripSwipe(it) }
        )
        SettingRow(
            title = stringResource(R.string.gesture_strip_up),
            checked = settings.gestureStripUp,
            onChange = { services.settings.setGestureStripUp(it) }
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.gesture_player))
        SettingRow(
            title = stringResource(R.string.gesture_player_swipe),
            checked = settings.gesturePlayerSwipe,
            onChange = { services.settings.setGesturePlayerSwipe(it) }
        )
        SettingRow(
            title = stringResource(R.string.gesture_player_down),
            checked = settings.gesturePlayerDown,
            onChange = { services.settings.setGesturePlayerDown(it) }
        )
        SettingRow(
            title = stringResource(R.string.gesture_player_up),
            checked = settings.gesturePlayerUp,
            onChange = { services.settings.setGesturePlayerUp(it) }
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.gesture_queue))
        SettingRow(
            title = stringResource(R.string.gesture_queue_down),
            checked = settings.gestureQueueDown,
            onChange = { services.settings.setGestureQueueDown(it) }
        )
        SettingRow(
            title = stringResource(R.string.gesture_queue_remove),
            checked = settings.gestureQueueRemove,
            onChange = { services.settings.setGestureQueueRemove(it) }
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.gesture_lists))
        SettingRow(
            title = stringResource(R.string.gesture_edge_scroll),
            checked = settings.gestureEdgeScroll,
            onChange = { services.settings.setGestureEdgeScroll(it) }
        )
        // This one gets a line of its own: it is the only gesture here that shares its axis with a
        // list's own scrolling, so what it takes and what it leaves is worth saying outright.
        Text(
            text = stringResource(R.string.gesture_edge_scroll_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp)
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.gesture_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/**
 * Everything the user has hidden, and the way back.
 *
 * Hiding is reversible and has to look it — an app that can make part of your library vanish with a
 * long press owes you one place that lists exactly what is gone. Artists first, then albums with the
 * artist they belong to, and a tap puts one back; the list is empty on a fresh install and says so.
 */
@Composable
private fun HiddenSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val hidden by services.hidden.hidden.collectAsStateWithLifecycle()

    SettingsPageFrame(stringResource(R.string.settings_hidden)) {
        if (hidden.isEmpty) {
            Text(
                text = stringResource(R.string.hidden_empty),
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            return@SettingsPageFrame
        }

        Text(
            text = stringResource(R.string.hidden_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )

        if (hidden.artists.isNotEmpty()) {
            SettingsHeader(stringResource(R.string.section_artists))
            hidden.artists.sorted().forEach { artist ->
                ListRow(
                    primary = artist,
                    secondary = stringResource(R.string.hidden_tap_to_show),
                    onClick = { services.hidden.showArtist(artist) }
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        if (hidden.albums.isNotEmpty()) {
            SettingsHeader(stringResource(R.string.section_albums))
            hidden.albums.sorted().forEach { key ->
                val (artist, title) = Hidden.readAlbumKey(key)
                ListRow(
                    primary = title,
                    secondary = artist.ifBlank { stringResource(R.string.hidden_tap_to_show) },
                    onClick = { services.hidden.showAlbum(key) }
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        MetroButton(
            text = stringResource(R.string.hidden_show_all),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            services.hidden.showEverything()
        }
    }
}

/**
 * Colour: the accent, what the background is, and whether the cover shows through it.
 *
 * The twenty accents are the ones the phone shipped; the background offers WP8's own black and white
 * plus a few flat colours, because "цвет фона" past those two is something the phone never allowed
 * and the app now does. Text colour is not offered — it is derived from the background's luminance,
 * so a pale background cannot end up with white text on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()

    SettingsPageFrame(stringResource(R.string.settings_theme)) {
        SettingsHeader(stringResource(R.string.theme_accent))
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetroAccents.forEach { (_, color) ->
                val argb = color.toArgb()
                Swatch(
                    color = color,
                    selected = settings.accentArgb == argb,
                    onClick = { services.settings.setAccent(argb) }
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        SettingsHeader(stringResource(R.string.theme_background))
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BackgroundChoices.forEach { (_, argb) ->
                Swatch(
                    color = Color(argb),
                    selected = settings.backgroundArgb == argb,
                    onClick = { services.settings.setBackground(argb) }
                )
            }
        }
        ListRow(
            primary = stringResource(R.string.theme_background_default),
            secondary = stringResource(
                if (settings.backgroundArgb == null) {
                    R.string.theme_background_default_in_use
                } else {
                    R.string.theme_background_default_hint
                }
            ),
            onClick = { services.settings.setBackground(null) }
        )

        Spacer(Modifier.height(10.dp))
        SettingRow(
            title = stringResource(R.string.theme_follow_device),
            checked = settings.followSystemTheme,
            onChange = { services.settings.setFollowSystemTheme(it) }
        )
        if (!settings.followSystemTheme) {
            ListRow(
                primary = stringResource(
                    if (settings.dark) R.string.theme_dark else R.string.theme_light
                ),
                secondary = stringResource(R.string.theme_tap_to_switch),
                onClick = { services.settings.setDark(!settings.dark) }
            )
        }
        SettingRow(
            title = stringResource(R.string.theme_artwork_background),
            checked = settings.artworkBackground,
            onChange = { services.settings.setArtworkBackground(it) }
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.theme_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** What the home panorama looks like: which sections, in what order, and the strip's cover. */
@Composable
private fun InterfaceSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    var pickingArtistAlbums by remember { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_interface)) {
        SettingsHeader(stringResource(R.string.interface_sections))
        Text(
            text = stringResource(R.string.interface_sections_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        val order = settings.sections
        order.forEachIndexed { index, section ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    color = colors.subtle,
                    fontFamily = MetroRegular,
                    fontSize = 16.sp,
                    modifier = Modifier.width(26.dp)
                )
                Text(
                    text = stringResource(sectionTitleOf(section)),
                    color = colors.fg,
                    fontFamily = MetroRegular,
                    fontSize = 23.sp,
                    modifier = Modifier.weight(1f)
                )
                MoveButton(
                    label = stringResource(R.string.interface_move_up),
                    enabled = index > 0,
                    onClick = { services.settings.moveSection(section, -1) }
                )
                Spacer(Modifier.width(8.dp))
                MoveButton(
                    label = stringResource(R.string.interface_move_down),
                    enabled = index < order.lastIndex,
                    onClick = { services.settings.moveSection(section, +1) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.interface_screen))
        SettingRow(
            title = stringResource(R.string.interface_full_screen),
            checked = settings.fullScreen,
            onChange = { services.settings.setFullScreen(it) }
        )
        Text(
            text = stringResource(R.string.interface_full_screen_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.interface_strip))
        SettingRow(
            title = stringResource(R.string.interface_strip_artwork),
            checked = settings.stripArtwork,
            onChange = { services.settings.setStripArtwork(it) }
        )
        Text(
            text = stringResource(R.string.interface_strip_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.interface_lists))
        SettingRow(
            title = stringResource(R.string.interface_letter_tiles),
            checked = settings.letterTiles,
            onChange = { services.settings.setLetterTiles(it) }
        )
        SettingRow(
            title = stringResource(R.string.interface_collapse_title),
            checked = settings.collapseTitle,
            onChange = { services.settings.setCollapseTitle(it) }
        )
        Text(
            text = stringResource(R.string.interface_collapse_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        // A page-level choice rather than a fifth arrangement held out of the albums section's own
        // header: an artist's page is not a MetroLongList and its "albums" heading is a plain label,
        // so there is nothing there for a hold to come out of. Two answers, so the picker is the
        // whole of the interface.
        Spacer(Modifier.height(6.dp))
        ListRow(
            primary = stringResource(R.string.interface_artist_albums),
            secondary = stringResource(artistAlbumOrderLabel(settings.artistAlbumOrder)),
            onClick = { pickingArtistAlbums = true }
        )
        Text(
            text = stringResource(R.string.interface_artist_albums_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    MetroListBox(
        visible = pickingArtistAlbums,
        title = stringResource(R.string.interface_artist_albums),
        items = ArtistAlbumOrder.entries.map { stringResource(artistAlbumOrderLabel(it)) },
        onSelect = { index ->
            ArtistAlbumOrder.entries.getOrNull(index)?.let {
                services.settings.setArtistAlbumOrder(it)
            }
            pickingArtistAlbums = false
        },
        onDismiss = { pickingArtistAlbums = false }
    )
}

@StringRes
private fun artistAlbumOrderLabel(order: ArtistAlbumOrder): Int = when (order) {
    ArtistAlbumOrder.Year -> R.string.interface_artist_albums_year
    ArtistAlbumOrder.YearOldest -> R.string.interface_artist_albums_year_oldest
    ArtistAlbumOrder.Name -> R.string.interface_artist_albums_name
}

/**
 * The device's own equalizer, driven straight from settings.
 *
 * Bands, their frequencies, the gain range and the presets all come from the platform rather than
 * being invented here — a phone with five bands and a phone with ten both get their own. When there
 * is no equalizer at all the page says so instead of showing dead sliders.
 */
@Composable
private fun EqualizerSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val effects = services.effects
    val capabilities = effects.capabilities

    var pickingPreset by remember { mutableStateOf(false) }

    // Start the sliders from what the hardware is actually doing, so switching away from a preset
    // continues from its curve instead of jumping to flat.
    var bands by remember(capabilities, settings.equalizerPreset) {
        mutableStateOf(
            settings.equalizerBands.takeIf { it.isNotEmpty() }
                ?: effects.currentBands()
        )
    }

    SettingsPageFrame(stringResource(R.string.settings_equalizer)) {
        if (capabilities == null) {
            Text(
                text = stringResource(R.string.equalizer_none),
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            return@SettingsPageFrame
        }

        SettingRow(
            title = stringResource(R.string.settings_equalizer),
            checked = settings.equalizerEnabled,
            onChange = { services.settings.setEqualizerEnabled(it) }
        )
        ListRow(
            primary = stringResource(R.string.equalizer_preset),
            secondary = capabilities.presets.getOrNull(settings.equalizerPreset)
                ?: stringResource(R.string.equalizer_preset_custom),
            onClick = { pickingPreset = true }
        )

        Spacer(Modifier.height(10.dp))
        SettingsHeader(stringResource(R.string.equalizer_bands))
        val range = (capabilities.maxLevel - capabilities.minLevel).coerceAtLeast(1)
        capabilities.bandFrequencies.forEachIndexed { index, frequency ->
            val level = bands.getOrElse(index) { 0 }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatFrequency(frequency),
                        color = colors.fg,
                        fontFamily = MetroRegular,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatGain(level),
                        color = colors.subtle,
                        fontFamily = MetroRegular,
                        fontSize = 15.sp
                    )
                }
                MetroSlider(
                    value = (level - capabilities.minLevel).toFloat() / range,
                    onValueChange = { fraction ->
                        val millibels =
                            (capabilities.minLevel + fraction * range).toInt()
                        bands = bands.toMutableList().also { list ->
                            while (list.size <= index) list.add(0)
                            list[index] = millibels
                        }
                    },
                    onValueChangeFinished = { services.settings.setEqualizerBands(bands) },
                    height = 34.dp
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        MetroButton(
            text = stringResource(R.string.equalizer_flat),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            bands = List(capabilities.bandFrequencies.size) { 0 }
            services.settings.setEqualizerBands(bands)
        }

        Spacer(Modifier.height(22.dp))
        SettingsHeader(stringResource(R.string.equalizer_bass))
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.percent, settings.bassBoost / 10),
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 15.sp
            )
            MetroSlider(
                value = settings.bassBoost / 1000f,
                onValueChange = { services.settings.setBassBoost((it * 1000).toInt()) },
                height = 34.dp
            )
        }
    }

    MetroListBox(
        visible = pickingPreset,
        title = stringResource(R.string.equalizer_preset),
        items = capabilities?.presets.orEmpty(),
        onSelect = { index ->
            services.settings.setEqualizerPreset(index)
            pickingPreset = false
        },
        onDismiss = { pickingPreset = false }
    )
}

/**
 * Last.fm: sign in through their own page, then every play goes up.
 *
 * Signing in is a web flow on purpose. Last.fm's mobile method wants the password itself, and an app
 * that asks for it is teaching a habit worth not teaching; the web page is theirs, over their TLS,
 * and what comes back here is a session key that can be revoked from the account page.
 */
@Composable
private fun LastFmSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val queue by services.scrobbler.pending.collectAsStateWithLifecycle()
    val loves by services.loves.state.collectAsStateWithLifecycle()

    var signingIn by remember { mutableStateOf(false) }
    var key by remember(settings.lastfmApiKey) { mutableStateOf(settings.lastfmApiKey.orEmpty()) }
    var secret by remember(settings.lastfmApiSecret) {
        mutableStateOf(settings.lastfmApiSecret.orEmpty())
    }

    val configured = settings.lastFmConfigured()
    val signedIn = !settings.lastfmSessionKey.isNullOrBlank()

    // The key fields are a fallback, not the way in. They appear only when the build was made
    // without a key, because then there is nothing this screen can do until someone supplies one —
    // an application key is not something a user has, and Last.fm issues no session without it.
    var showingKeyFields by remember(configured) { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_lastfm)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text(
                text = if (signedIn) {
                    stringResource(R.string.lastfm_signed_in_as, settings.lastfmUser.orEmpty())
                } else {
                    stringResource(R.string.lastfm_signed_out)
                },
                color = colors.fg,
                fontFamily = MetroRegular,
                fontSize = 22.sp
            )
            Spacer(Modifier.height(14.dp))
            if (signedIn) {
                MetroButton(stringResource(R.string.lastfm_logout)) {
                    services.settings.setLastfmSession(null, null)
                }
            } else {
                MetroButton(stringResource(R.string.lastfm_login), filled = true) {
                    if (configured) signingIn = true else showingKeyFields = true
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingRow(
            title = stringResource(R.string.lastfm_scrobble),
            checked = settings.scrobbleEnabled,
            onChange = { services.settings.setScrobbleEnabled(it) }
        )

        if (queue.items.isNotEmpty()) {
            ListRow(
                primary = pluralStringResource(
                    R.plurals.scrobbles_waiting,
                    queue.items.size,
                    queue.items.size
                ),
                secondary = stringResource(R.string.lastfm_queue_hint),
                onClick = { services.scrobbler.flush() }
            )
        }

        // Only offered to an account, because there is nothing to reconcile against without one — and
        // switching it on is what starts the first reconciliation, which is a union of both sides.
        if (signedIn) {
            Spacer(Modifier.height(14.dp))
            SettingsHeader(stringResource(R.string.lastfm_loves))
            SettingRow(
                title = stringResource(R.string.lastfm_sync_loves),
                checked = settings.syncLoves,
                onChange = { services.settings.setSyncLoves(it) }
            )
            if (settings.syncLoves) {
                if (loves.pending.isNotEmpty()) {
                    ListRow(
                        primary = pluralStringResource(
                            R.plurals.loves_waiting,
                            loves.pending.size,
                            loves.pending.size
                        ),
                        secondary = stringResource(R.string.lastfm_loves_queue_hint),
                        onClick = { services.loves.sync(pull = true) }
                    )
                } else {
                    ListRow(
                        primary = stringResource(R.string.lastfm_loves_sync_now),
                        secondary = if (loves.isFresh) {
                            stringResource(R.string.lastfm_loves_never)
                        } else {
                            stringResource(
                                R.string.lastfm_loves_last,
                                formatWhen(loves.lastSyncedAtMs)
                            )
                        },
                        onClick = { services.loves.sync(pull = true) }
                    )
                }
                ListRow(
                    primary = stringResource(R.string.lastfm_loves_scratch),
                    secondary = stringResource(R.string.lastfm_loves_scratch_hint),
                    onClick = { services.loves.forgetBaseline() }
                )
            }
            Text(
                text = stringResource(R.string.lastfm_loves_explainer),
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        if (!configured || showingKeyFields) {
            Spacer(Modifier.height(22.dp))
            SettingsHeader(stringResource(R.string.lastfm_api_key))
            Text(
                text = stringResource(R.string.lastfm_key_explainer),
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Column(Modifier.padding(horizontal = 24.dp)) {
                MetroTextBox(
                    key,
                    { key = it },
                    placeholder = stringResource(R.string.lastfm_api_key)
                )
                Spacer(Modifier.height(8.dp))
                MetroTextBox(
                    secret,
                    { secret = it },
                    placeholder = stringResource(R.string.lastfm_api_secret)
                )
                Spacer(Modifier.height(12.dp))
                MetroButton(stringResource(R.string.action_save)) {
                    services.settings.setLastfmCredentials(key, secret)
                }
            }
        }
    }

    if (signingIn) {
        LastFmSignIn(
            onDone = { signingIn = false }
        )
    }
}

@Composable
private fun PlaybackSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val sleepRemaining by services.player.sleepRemainingMs.collectAsStateWithLifecycle()
    var pickingSleep by remember { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_playback)) {
        SettingsHeader(stringResource(R.string.playback_loudness))
        SettingRow(
            title = stringResource(R.string.playback_normalize),
            checked = settings.volumeNormalization,
            onChange = { services.settings.setVolumeNormalization(it) }
        )
        Text(
            text = stringResource(R.string.playback_normalize_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.playback_sleep))
        ListRow(
            primary = stringResource(R.string.sleep_timer),
            secondary = if (sleepRemaining > 0) {
                stringResource(R.string.sleep_left, formatDuration(sleepRemaining))
            } else {
                stringResource(R.string.sleep_off)
            },
            onClick = { pickingSleep = true }
        )
        if (settings.sleepTimerMinutes > 0 && sleepRemaining == 0L) {
            ListRow(
                primary = stringResource(R.string.sleep_restart),
                secondary = stringResource(R.string.minutes, settings.sleepTimerMinutes),
                onClick = { services.player.setSleepTimer(settings.sleepTimerMinutes) }
            )
        }
    }

    MetroListBox(
        visible = pickingSleep,
        title = stringResource(R.string.sleep_timer),
        items = SleepOptions.map { stringResource(it.label) },
        onSelect = { index ->
            services.player.setSleepTimer(SleepOptions[index].minutes)
            services.settings.setSleepTimerMinutes(SleepOptions[index].minutes)
            pickingSleep = false
        },
        onDismiss = { pickingSleep = false }
    )
}

@Composable
private fun LibrarySettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val settings by services.settings.settings.collectAsStateWithLifecycle()
    val library by services.library.library.collectAsStateWithLifecycle()
    val scanning by services.library.scanning.collectAsStateWithLifecycle()
    val report by services.library.report.collectAsStateWithLifecycle()
    var pickingMinLength by remember { mutableStateOf(false) }
    var pickingLyricsSource by remember { mutableStateOf(false) }

    SettingsPageFrame(stringResource(R.string.settings_library)) {
        ListRow(
            primary = stringResource(R.string.library_min_length),
            secondary = stringResource(R.string.seconds, settings.minTrackSeconds),
            onClick = { pickingMinLength = true }
        )
        ListRow(
            primary = stringResource(
                if (scanning) R.string.library_scanning else R.string.row_rescan
            ),
            secondary = listOf(
                formatTrackCount(library.tracks.size),
                formatAlbumCount(library.albums.size),
                formatArtistCount(library.artists.size)
            ).joinToString(" · "),
            onClick = { services.library.rescan() }
        )
        // What the scan saw against what it kept. "The app does not see my files" cannot be answered
        // from the outside — the store either has a row for a file or it does not, and the app either
        // kept that row or dropped it for a reason. These are the numbers behind that.
        Text(
            text = stringResource(
                R.string.library_scan_report,
                report.rows,
                report.notMusic,
                report.tooShort
            ),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.section_artists))
        SettingRow(
            title = stringResource(R.string.library_split_artists),
            checked = settings.splitArtistCredits,
            onChange = { services.settings.setSplitArtistCredits(it) }
        )
        Text(
            text = stringResource(R.string.library_split_artists_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        SettingRow(
            title = stringResource(R.string.library_album_artist),
            checked = settings.useAlbumArtist,
            onChange = { services.settings.setUseAlbumArtist(it) }
        )
        Text(
            text = stringResource(R.string.library_album_artist_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        // Under the album artist row, because it is the other half of that answer and because the
        // setter turns that one on with it — the switch above moving is the explanation for why.
        SettingRow(
            title = stringResource(R.string.library_artists_album_artist),
            checked = settings.artistsFromAlbumArtist,
            onChange = { services.settings.setArtistsFromAlbumArtist(it) }
        )
        Text(
            text = stringResource(R.string.library_artists_album_artist_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        // Last of the three, because it only chooses between names the two above hand it — and its
        // setter turns both of them on, so the rows moving is what says so.
        SettingRow(
            title = stringResource(R.string.library_prefer_known_artist),
            checked = settings.preferKnownArtist,
            onChange = { services.settings.setPreferKnownArtist(it) }
        )
        Text(
            text = stringResource(R.string.library_prefer_known_artist_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        SettingRow(
            title = stringResource(R.string.library_fix_artists),
            checked = settings.fixArtistDoubling,
            onChange = { services.settings.setFixArtistDoubling(it) }
        )
        Text(
            text = stringResource(R.string.library_fix_artists_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.section_genres))
        SettingRow(
            title = stringResource(R.string.library_fix_genres),
            checked = settings.fixGenreDoubling,
            onChange = { services.settings.setFixGenreDoubling(it) }
        )
        Text(
            text = stringResource(R.string.library_fix_genres_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.artwork_heading))
        SettingRow(
            title = stringResource(R.string.artwork_online),
            checked = settings.onlineArtwork,
            onChange = { services.settings.setOnlineArtwork(it) }
        )
        Text(
            text = stringResource(R.string.artwork_online_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        SettingsHeader(stringResource(R.string.lyrics_heading))
        SettingRow(
            title = stringResource(R.string.lyrics_lookup),
            checked = settings.lyricsEnabled,
            onChange = { services.settings.setLyricsEnabled(it) }
        )
        Text(
            text = stringResource(R.string.lyrics_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(6.dp))
        ListRow(
            primary = stringResource(R.string.lyrics_service),
            secondary = stringResource(lyricsSourceLabel(settings.lyricsSource)),
            onClick = { pickingLyricsSource = true }
        )
        Text(
            text = stringResource(R.string.lyrics_service_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        // The system's own folder picker, which is the only way an app is handed a directory it can
        // both read and write from Android 10 onwards. The grant is taken as persistable inside
        // `LyricsFiles` — without that it lasts until the process dies, which reads as the feature
        // having worked once.
        val pickFolder = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> if (uri != null) services.lyrics.setLyricsFolder(uri) }
        val folder = settings.lyricsFolderUri?.let { lyricsFolderName(Uri.parse(it)) }
        ListRow(
            primary = stringResource(R.string.lyrics_folder),
            secondary = folder ?: stringResource(R.string.lyrics_folder_none),
            onClick = { pickFolder.launch(null) }
        )
        Text(
            text = stringResource(R.string.lyrics_folder_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        if (folder != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 24.dp)) {
                MetroButton(stringResource(R.string.lyrics_folder_clear)) {
                    services.lyrics.setLyricsFolder(null)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        SettingRow(
            title = stringResource(R.string.lyrics_save_lrc),
            checked = settings.lyricsSaveLrc,
            onChange = { services.settings.setLyricsSaveLrc(it) }
        )
        Text(
            text = stringResource(R.string.lyrics_save_lrc_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    MetroListBox(
        visible = pickingMinLength,
        title = stringResource(R.string.library_min_length),
        items = MinLengthOptions.map { stringResource(R.string.seconds, it) },
        onSelect = { index ->
            services.settings.setMinTrackSeconds(MinLengthOptions[index])
            pickingMinLength = false
        },
        onDismiss = { pickingMinLength = false }
    )

    // Through the repository rather than straight to the settings store: switching services has to
    // forget what the old one answered, and that is the repository's cache and index to clear.
    MetroListBox(
        visible = pickingLyricsSource,
        title = stringResource(R.string.lyrics_service),
        items = LyricsSource.entries.map { stringResource(lyricsSourceLabel(it)) },
        onSelect = { index ->
            LyricsSource.entries.getOrNull(index)?.let { services.lyrics.setLyricsSource(it) }
            pickingLyricsSource = false
        },
        onDismiss = { pickingLyricsSource = false }
    )
}

@StringRes
private fun lyricsSourceLabel(source: LyricsSource): Int = when (source) {
    LyricsSource.LrcLib -> R.string.lyrics_service_lrclib
    LyricsSource.Genius -> R.string.lyrics_service_genius
}

// ---- shared bits ----

@Composable
internal fun SettingsPageFrame(title: String, content: @Composable () -> Unit) {
    MetroPage("SETTINGS", title) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun SettingsHeader(text: String) {
    Text(
        text = text,
        color = MetroTheme.colors.subtle,
        fontFamily = MetroSemilight,
        fontSize = 24.sp,
        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, MetroTheme.colors.fg) else Modifier)
            .clickable { onClick() }
    )
}

/** A small square nudge button — the reorder control, kept to the size of a glyph. */
@Composable
private fun MoveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MetroTheme.colors
    Box(
        Modifier
            .size(38.dp)
            .border(2.dp, if (enabled) colors.fg else colors.dim.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) colors.fg else colors.dim.copy(alpha = 0.4f),
            fontFamily = MetroRegular,
            fontSize = 15.sp
        )
    }
}

/** "60 Hz" under a thousand, "3.6 kHz" above it — the way an equalizer is labelled. */
@Composable
private fun formatFrequency(hz: Int): String = if (hz >= 1000) {
    val whole = hz / 1000
    val tenths = (hz % 1000) / 100
    stringResource(R.string.equalizer_khz, if (tenths > 0) "$whole.$tenths" else "$whole")
} else {
    stringResource(R.string.equalizer_hz, hz)
}

/**
 * Always signed, so a boost and a cut are told apart at a glance, and always in the device's own
 * locale — a comma is the decimal separator in most of Europe, and `%+.1f` with no locale would
 * print a full stop there.
 */
@Composable
private fun formatGain(millibels: Int): String = stringResource(
    R.string.equalizer_db,
    String.format(Locale.getDefault(), "%+.1f", millibels / 100f)
)

/**
 * Backgrounds on offer: WP8's two, then a few flat colours dark enough to keep white text legible
 * and light enough to keep black text legible, since the palette derives the text colour.
 */
private val BackgroundChoices: List<Pair<String, Int>> = listOf(
    "black" to 0xFF000000.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "graphite" to 0xFF1C1C1C.toInt(),
    "midnight" to 0xFF0B1220.toInt(),
    "forest" to 0xFF0F1A12.toInt(),
    "plum" to 0xFF1A0F1A.toInt(),
    "sand" to 0xFFEFE7D8.toInt()
)

private data class SleepOption(@StringRes val label: Int, val minutes: Int)

private val SleepOptions = listOf(
    SleepOption(R.string.sleep_off, 0),
    SleepOption(R.string.sleep_15, 15),
    SleepOption(R.string.sleep_30, 30),
    SleepOption(R.string.sleep_45, 45),
    SleepOption(R.string.sleep_60, 60),
    SleepOption(R.string.sleep_120, 120)
)

private val MinLengthOptions = listOf(0, 10, 20, 30, 60, 90)
