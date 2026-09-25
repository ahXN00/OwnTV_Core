package tv.own.owntv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observes network reachability so the UI can warn the user when they're offline (playback / sync
 * won't work). Emits the current state immediately and on every change.
 */
class ConnectivityObserver(private val context: Context) {

    private val cm: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * A best-effort snapshot of whether the active network has validated internet.
     *
     * Both capabilities are required. `NET_CAPABILITY_INTERNET` only says the interface is *meant* to
     * carry internet — a TV with the Ethernet cable plugged into a dead router reports it forever.
     * `NET_CAPABILITY_VALIDATED` is the platform's own verdict that traffic actually reached the
     * outside, which is the thing the offline warning is about.
     */
    fun isOnlineNow(): Boolean {
        val manager = cm ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasInternet() == true
    }

    /**
     * Whether the network carrying traffic right now costs the user money — what the data saver acts
     * on. Unknown counts as unmetered: a wrong "yes" blocks playback on a connection that was working
     * a moment ago, which is the one outcome a saver setting must never produce by accident.
     */
    fun isMeteredNow(): Boolean {
        val manager = cm ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** [isMeteredNow] now, then again whenever the network carrying traffic changes (Wi-Fi ⇄ mobile). */
    val isMetered: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(isMeteredNow()) }
            override fun onLost(network: Network) { trySend(isMeteredNow()) }
        }
        trySend(isMeteredNow())
        val manager = cm
        manager?.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Every branch answers with isOnlineNow(), for two reasons: it applies the same
            // two-capability test (a callback reporting plain reachability would overwrite the poll's
            // correct verdict), and it answers for the ACTIVE network. The request below matches every
            // network with internet, so on a TV with the Ethernet cable in AND Wi-Fi joined, reporting
            // the callback's own capabilities let the idle interface's state overwrite the one actually
            // carrying traffic — an offline banner over a working connection until the 20 s poll
            // corrected it.
            override fun onAvailable(network: Network) { trySend(isOnlineNow()) }
            override fun onLost(network: Network) { trySend(isOnlineNow()) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnlineNow())
            }
        }
        trySend(isOnlineNow())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val manager = cm
        manager?.registerNetworkCallback(request, callback)
        // Poll every 20s — on devices where a network interface stays "up" forever
        // (Ethernet without cable, some TV firmwares), callbacks never fire. This
        // ensures the offline banner still appears when internet is unreachable.
        val pollJob = launch { while (isActive) { delay(20_000); trySend(isOnlineNow()) } }
        awaitClose { pollJob.cancel(); runCatching { manager?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
