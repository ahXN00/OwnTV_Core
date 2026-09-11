package tv.own.owntv.core.sync.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * An OwnTV device answering on the network right now.
 *
 * [deviceId] is its lasting identity, announced in the service record so a device already paired can
 * be recognised as such **before** anyone is asked for a PIN again. Blank when the far side is older
 * than this, in which case the screens fall back to matching on the address.
 */
data class DiscoveredDevice(val name: String, val address: String, val port: Int, val deviceId: String = "")

/**
 * Finds the other OwnTV on the same Wi-Fi, and announces this one, over Android's own NSD (mDNS /
 * Bonjour) as `_owntv._tcp`.
 *
 * **Discovery is a convenience, never the only way in.** mDNS is blocked by AP isolation on guest
 * networks, by some routers outright, and by anything with more than one VLAN — so the sync screens
 * always offer the address and the QR code as well. A feature that only works on a friendly network
 * is a feature that generates support questions instead of syncs.
 */
class LocalSyncDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registration: NsdManager.RegistrationListener? = null

    /** Announces this device while it is hosting. Silently does nothing where NSD is unavailable. */
    fun advertise(name: String, port: Int, deviceId: String) {
        val manager = nsd ?: return
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name.take(SERVICE_NAME_LIMIT)
            serviceType = SERVICE_TYPE
            setPort(port)
            // Who this is, so the other device can say "already paired" instead of asking for a PIN
            // it does not need. A service record is public on the LAN, so this carries the id and
            // nothing else — never the secret, which is what actually grants access.
            if (deviceId.isNotBlank()) setAttribute(ATTRIBUTE_ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not announce this device for local sync (error $errorCode)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "Local sync announcement failed", it) }
    }

    fun stopAdvertising() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        registration = null
    }

    /**
     * Devices as they appear, until the collector goes away. Each one is resolved before it is
     * emitted, because an unresolved discovery has no address to connect to.
     */
    fun discover(): Flow<DiscoveredDevice> = callbackFlow {
        val manager = nsd ?: run { close(); return@callbackFlow }
        // Resolving is one-at-a-time on older Android: a second call while one is in flight fails
        // with FAILURE_ALREADY_ACTIVE and the device is simply never seen. A queue keeps it honest.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION") // resolveService(ServiceInfoCallback) is API 34+; this app supports older.
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        if (host is Inet4Address) {
                            val id = info.attributes[ATTRIBUTE_ID]?.toString(Charsets.UTF_8).orEmpty()
                            host.hostAddress?.let { trySend(DiscoveredDevice(info.serviceName, it, info.port, id)) }
                        }
                        resolving = false
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.').endsWith(SERVICE_TYPE.trimEnd('.'))) {
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Local sync discovery could not start (error $errorCode)")
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "Local sync discovery failed", it)
                close()
            }
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    private companion object {
        const val TAG = "LocalSyncDiscovery"
        const val SERVICE_TYPE = "_owntv._tcp."

        /** The service-record key carrying [DiscoveredDevice.deviceId]. */
        const val ATTRIBUTE_ID = "id"

        /** mDNS instance names are bounded; a long "Living Room Television" would be rejected whole. */
        const val SERVICE_NAME_LIMIT = 40
    }
}
