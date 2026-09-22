package com.metromusic.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether there is a working network, as a flow.
 *
 * Exists because "send it when the internet comes back" is otherwise unanswerable: the scrobble queue
 * can only guess by retrying, and a retry timer that is short enough to feel immediate is one that
 * wakes the radio all day for nothing. The system already knows the moment a network is usable, so
 * ask it.
 *
 * **Usable, not merely present.** `NET_CAPABILITY_VALIDATED` is the difference between joining a wifi
 * and that wifi reaching the internet — a captive portal is `available` the moment you associate with
 * it, and flushing into one is how a queue gets marked "sent" against a login page. The
 * hotel-wifi case is exactly the one this feature is for.
 *
 * Nothing is ever unregistered: one callback, for the life of the process, on the application
 * context. Every call is wrapped — a device that refuses the callback has to read as "assume online
 * and let the request find out", not as a crash on startup.
 */
class Connectivity(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(true)
    private val _arrivals = MutableStateFlow(0)

    /**
     * True while a validated network is up.
     *
     * Starts optimistic: with no answer yet, the honest thing is to let the request go and learn from
     * how it fails, rather than to hold a scrobble back over a callback that has not fired.
     */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * Counts the moments a usable network **arrived**, which is the thing anything holding a failed
     * request wants to hear about.
     *
     * [online] on its own is the wrong shape for that. Keyed on a boolean, work re-runs when the
     * network *goes* as well as when it comes, and a flapping connection reports `true` again without
     * the value ever changing. A number that only ever goes up says "something that could not be
     * asked before can be asked now" exactly once per arrival, which is what a `produceState` key or
     * a retry wants.
     *
     * It does not tick for the network that is already up at startup: nothing has failed yet.
     */
    val arrivals: StateFlow<Int> = _arrivals.asStateFlow()

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()

            override fun onLost(network: Network) = refresh()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) = refresh()
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(Tag, "no network callback; assuming online", it) }
        refresh()
    }

    private fun refresh() {
        val usable = runCatching {
            val active = manager?.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(true)
        val wasOffline = !_online.value
        _online.value = usable
        if (usable && wasOffline) _arrivals.value++
    }

    private companion object {
        const val Tag = "Connectivity"
    }
}
