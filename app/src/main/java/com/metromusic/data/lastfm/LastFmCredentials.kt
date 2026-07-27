package com.metromusic.data.lastfm

import com.metromusic.BuildConfig
import com.metromusic.data.store.Settings

/**
 * Where the Last.fm application key comes from: whatever was pasted into settings, and failing that
 * whatever `local.properties` put into the build.
 *
 * That order matters. The build-time value is a convenience for whoever builds this from source, but
 * it must not win over a key the user typed in — a build that happens to carry a key would otherwise
 * silently ignore the one they had entered and there would be nothing on screen to explain why.
 */
fun Settings.lastFmKey(): String? =
    lastfmApiKey?.takeIf { it.isNotBlank() }
        ?: BuildConfig.LASTFM_API_KEY.takeIf { it.isNotBlank() }

fun Settings.lastFmSecret(): String? =
    lastfmApiSecret?.takeIf { it.isNotBlank() }
        ?: BuildConfig.LASTFM_API_SECRET.takeIf { it.isNotBlank() }

/** True when there is a key and a secret to sign requests with, wherever they came from. */
fun Settings.lastFmConfigured(): Boolean = lastFmKey() != null && lastFmSecret() != null
