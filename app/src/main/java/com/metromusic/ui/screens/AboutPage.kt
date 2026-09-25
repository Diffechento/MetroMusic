package com.metromusic.ui.screens

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import com.metrocompose.MetroFlipTile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrocompose.MetroAccents
import com.metrocompose.MetroLight
import com.metrocompose.MetroRegular
import com.metrocompose.MetroSemilight
import com.metrocompose.MetroSubheader
import com.metrocompose.MetroTheme
import com.metromusic.BuildConfig
import com.metromusic.R
import com.metromusic.core.LocalServices

/**
 * What this is, whose it is, and a handful of tiles to poke.
 *
 * The facts first — name, version, the date it was released, the copyright and the licence — because
 * that is what an about page is for and it should not need scrolling past anything to be read.
 *
 * Then the tiles, which are the reason this page is worth having rather than a paragraph in a
 * settings row. They are the phone's own idea of what a tile is: they carry a real number from your
 * own library rather than decoration, they flip when tapped the way a live tile flips by itself, and
 * they press inwards under a finger. Poking them is the whole feature, and the counter underneath
 * notices how long you keep at it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSettings() {
    val services = LocalServices.current
    val colors = MetroTheme.colors
    val library by services.library.library.collectAsStateWithLifecycle()
    val stats by services.stats.stats.collectAsStateWithLifecycle()
    val playlists by services.playlists.playlists.collectAsStateWithLifecycle()

    // Survives rotation and being covered by another page, so a count built up over a minute of
    // poking is not reset by turning the phone sideways to look at it.
    var pokes by rememberSaveable { mutableIntStateOf(0) }

    val tiles = listOf(
        AboutTile(R.string.about_tile_songs, "♪", library.tracks.size),
        AboutTile(R.string.about_tile_albums, "▤", library.albums.size),
        // Glyphs, not emoji: a code point with an emoji presentation (☺ is one) is drawn by the
        // colour font, and one yellow cartoon face among five flat white marks is the whole WP8
        // look gone. Everything here is from a range the text font answers for.
        AboutTile(R.string.about_tile_artists, "✦", library.artists.size),
        AboutTile(R.string.about_tile_favourites, "★", stats.favorites.size),
        AboutTile(R.string.about_tile_plays, "▶", stats.playCounts.values.sum()),
        AboutTile(R.string.about_tile_playlists, "≡", playlists.items.size)
    )

    SettingsPageFrame(stringResource(R.string.settings_about)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                color = colors.fg,
                fontFamily = MetroLight,
                fontSize = 40.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                ),
                color = colors.subtle,
                fontFamily = MetroRegular,
                fontSize = 16.sp
            )
            Text(
                text = BuildConfig.RELEASE_DATE,
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.about_copyright),
                color = colors.fg,
                fontFamily = MetroRegular,
                fontSize = 15.sp
            )
            Text(
                text = stringResource(R.string.about_licence),
                color = colors.dim,
                fontFamily = MetroRegular,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(26.dp))
        MetroSubheader(top = 0.dp, text = stringResource(R.string.about_tiles))
        FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tiles.forEachIndexed { index, tile ->
                PokeableTile(tile = tile, seed = index, onPoke = { pokes++ })
            }
        }

        Spacer(Modifier.height(14.dp))
        // The count and the remark are two strings on purpose: the count has to inflect its noun
        // (one poke, two pokes, and three forms of it in Russian) and the remark must not be a
        // plurals entry repeated five times to carry a joke that does not depend on the number.
        Text(
            text = if (pokes == 0) {
                stringResource(R.string.about_pokes_none)
            } else {
                pluralStringResource(R.plurals.about_pokes, pokes, pokes) + " · " +
                    stringResource(pokeRemarkOf(pokes))
            },
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.about_explainer),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(28.dp))
        MetroSubheader(top = 0.dp, text = stringResource(R.string.about_notices))
        // Not decoration and not optional. Three-clause BSD asks that its copyright notice, its
        // conditions and its disclaimer travel with *binaries* as well as source, and an APK handed to
        // someone is a binary distribution with no README beside it — so the notice has to be reachable
        // from inside the app. The vendored ALAC decoder is the one that makes this necessary; the rest
        // are here because a list that names only the awkward dependency reads as an apology.
        Text(
            text = stringResource(R.string.about_notices_body),
            color = colors.subtle,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.about_source),
            color = colors.dim,
            fontFamily = MetroRegular,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** One tile: a glyph on the front, a real number from the library on the back. */
private data class AboutTile(val label: Int, val glyph: String, val count: Int)

/**
 * A tile that flips on tap and repaints itself on the way round.
 *
 * The turn itself is the framework's [MetroFlipTile]; what is this page's own is the colour, which
 * walks on through the twenty accents with each turn, and the two faces — a glyph on the front, a
 * real count out of the library on the back.
 */
@Composable
private fun PokeableTile(tile: AboutTile, seed: Int, onPoke: () -> Unit) {
    var turns by rememberSaveable(tile.label) { mutableIntStateOf(0) }
    // Starting somewhere different per tile, so a freshly opened page is not six squares of one
    // colour.
    val accent = MetroAccents[(seed * 3 + turns) % MetroAccents.size].second
    val label = stringResource(tile.label)

    MetroFlipTile(
        turns = turns,
        onClick = {
            turns++
            onPoke()
        },
        color = accent,
        front = { TileFace(tile.glyph, MetroSemilight, 30.sp, label) },
        back = { TileFace(tile.count.toString(), MetroLight, 34.sp, label) }
    )
}

@Composable
private fun TileFace(big: String, family: FontFamily, size: TextUnit, label: String) {
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = big,
            color = Color.White,
            fontFamily = family,
            fontSize = size,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = label,
            color = Color.White,
            fontFamily = MetroRegular,
            fontSize = 12.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 2.dp, end = 4.dp)
        )
    }
}

/**
 * What the line under the tiles says, which is the joke: it is the only counter in the app that
 * measures the user rather than the library.
 */
private fun pokeRemarkOf(pokes: Int): Int = when {
    pokes < 5 -> R.string.about_pokes_few
    pokes < 15 -> R.string.about_pokes_some
    pokes < 40 -> R.string.about_pokes_many
    else -> R.string.about_pokes_plenty
}
