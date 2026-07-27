package com.metromusic

import android.app.Application
import com.metromusic.core.Services

/**
 * Owns the composition root. There is no DI framework here on purpose — the graph is a
 * handful of singletons, and a plain object holding lazy fields costs nothing at startup
 * and nothing in APK size.
 */
class MetroMusicApp : Application() {

    val services: Services by lazy { Services(this) }
}
