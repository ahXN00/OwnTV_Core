package tv.own.owntv.core.sync.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.own.owntv.core.sync.local.LocalAutoSyncStore
import tv.own.owntv.core.sync.local.LocalAutoSyncTiming

/**
 * Keeps the auto-sync promise at the moments it promises.
 *
 * Two of them, and neither of them is the setting being changed:
 *
 *  - **The app starts.** A timer is not kept by the process: a television that was rebooted, or an
 *    app the system killed, comes back with no work scheduled at all. So the schedule is put back
 *    in front of WorkManager here, from the saved settings, rather than only when the user changes
 *    them.
 *  - **The network comes back.** "Immediately" is the one timing that cares, because its promise is
 *    that the content is current *now* — and the moment the Wi-Fi comes back is the moment the other
 *    device can be reached.
 *
 * The network callback is deliberately its own, and not [tv.own.owntv.core.network.ConnectivityObserver]:
 * that one asks whether the internet is *validated*, which is the right question for a stream that
 * comes from the internet and the wrong one for a sync that crosses a LAN. A television with the
 * router's WAN down can still reach the phone two metres away, and must be allowed to.
 */
class LocalAutoSyncStarter(
    context: Context,
    private val store: LocalAutoSyncStore,
    private val scheduler: LocalAutoSyncScheduler,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    /** Call once per process. Safe to call again: it does nothing the second time. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val config = store.current()
            if (!config.enabled) return@launch
            scheduler.schedule(config)
            Log.i(TAG, "schedule restored: ${config.timing}")
        }
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val config = store.current()
                    if (!config.enabled) return@launch
                    if (config.timing != LocalAutoSyncTiming.STARTUP) return@launch
                    scheduler.trigger()
                    Log.i(TAG, "network back: asking for a run now")
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "could not watch the network", it) }
    }

    private companion object {
        const val TAG = "LocalAutoSync"
    }
}
