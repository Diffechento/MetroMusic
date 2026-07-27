package com.metromusic.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.metrocompose.MetroTile
import com.metrocompose.MetroWidgetProvider
import com.metromusic.R

/**
 * A WP8 live tile on the home screen showing what's playing.
 *
 * The widget runs as a broadcast receiver with no access to the running player, so the player
 * leaves the current track in a tiny preferences file ([NowPlayingWidget]) and pokes the
 * widget whenever it changes. Everything else — rendering, the tap-to-open intent — comes
 * from the framework's base class.
 */
class NowPlayingTile : MetroWidgetProvider() {

    override fun tile(context: Context): MetroTile {
        val (title, artist) = NowPlayingWidget.read(context)
        return if (title.isEmpty()) {
            MetroTile(
                color = 0xFF1BA1E2.toInt(),
                glyph = "♪",
                label = context.getString(R.string.tile_label),
                targetScreen = "Collection"
            )
        } else {
            MetroTile(
                color = 0xFF1BA1E2.toInt(),
                glyph = "♪",
                label = if (artist.isEmpty()) title else "$title · $artist",
                targetScreen = "NowPlaying"
            )
        }
    }
}

/** The player's side of the contract: store the current track and refresh any live tiles. */
object NowPlayingWidget {

    private const val Prefs = "now_playing_widget"
    private const val KeyTitle = "title"
    private const val KeyArtist = "artist"

    fun read(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        return prefs.getString(KeyTitle, "").orEmpty() to prefs.getString(KeyArtist, "").orEmpty()
    }

    fun publish(context: Context, title: String, artist: String) {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        if (prefs.getString(KeyTitle, "") == title && prefs.getString(KeyArtist, "") == artist) {
            return
        }
        prefs.edit().putString(KeyTitle, title).putString(KeyArtist, artist).apply()

        // Only bother the widget manager if a tile is actually placed.
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingTile::class.java))
        if (ids.isNotEmpty()) {
            context.sendBroadcast(
                android.content.Intent(context, NowPlayingTile::class.java)
                    .setAction(MetroWidgetProvider.ACTION_REFRESH)
            )
        }
    }
}
