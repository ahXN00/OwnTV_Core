package tv.own.owntv.core.sync.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.core.sync.local.LocalAutoSyncRunner
import tv.own.owntv.core.sync.local.LocalAutoSyncServer

/**
 * The moments an automatic sync is tried, and the listener's conditions. Each app calls [start] once,
 * after Koin, from the main process only (never `:autostart` or `:restart`).
 *
 * - OwnTV comes to the front (which includes starting): a pass for every due agreement.
 * - OwnTV goes to the background: a pass a few seconds later, so what was just watched reaches the
 *   other device; coming back in that time cancels it (the return runs one anyway).
 * - Wi-Fi or Ethernet comes back: a pass.
 * - [periodic] (the phone): an hourly check while on Wi-Fi with the battery not low, which does
 *   nothing unless an agreement is due.
 *
 * The network watched is the local one (Wi-Fi or Ethernet), not a validated internet: a sync across
 * the room needs no internet at all.
 */
class LocalAutoSyncStarter(
    context: Context,
    private val runner: LocalAutoSyncRunner,
    private val server: LocalAutoSyncServer,
    private val scheduler: LocalAutoSyncScheduler,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var backgroundPass: Job? = null

    /** Main thread, once per process. */
    fun start(periodic: Boolean) {
        if (started) return
        started = true
        server.watch()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                server.appVisible.value = true
                backgroundPass?.cancel()
                run("foreground")
            }

            override fun onStop(owner: LifecycleOwner) {
                server.appVisible.value = false
                backgroundPass?.cancel()
                backgroundPass = scope.launch {
                    delay(BACKGROUND_DELAY_MS)
                    runner.runDue("background")
                }
            }
        })
        watchNetwork()
        if (periodic) scheduler.schedulePeriodic() else scheduler.cancel()
    }

    private fun watchNetwork() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val before = server.onLan.value
                server.onLan.value = lan
                if (lan && !before) run("network")
            }

            override fun onLost(network: Network) {
                server.onLan.value = false
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "could not watch the network", it) }
    }

    private fun run(reason: String) {
        scope.launch { runner.runDue(reason) }
    }

    private companion object {
        const val TAG = "LocalAutoSync"
        const val BACKGROUND_DELAY_MS = 3_000L
    }
}
