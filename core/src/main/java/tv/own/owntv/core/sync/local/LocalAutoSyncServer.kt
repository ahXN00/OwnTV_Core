package tv.own.owntv.core.sync.local

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.companion.CompanionHttpProtocol

/**
 * Whether the automatic-sync listener runs: only with at least one agreement, only on the local
 * network, and then either whenever OwnTV is in front ("Keep auto-sync listening" on) or only while
 * the Local sync page has Sync mode on.
 */
fun autoSyncShouldListen(hasAgreement: Boolean, onLan: Boolean, listenAlways: Boolean, appVisible: Boolean, syncModeOn: Boolean): Boolean =
    hasAgreement && onLan && (syncModeOn || (listenAlways && appVisible))

/**
 * The listener a paired device's automatic sync talks to — on its own port ([AUTO_SYNC_PORT]),
 * because the manual one is restarted by every companion mode.
 *
 * It answers only signed requests ([LocalAutoSyncAuth]) from a paired device with an agreement, and
 * only from the local network. No PIN, no pairing, no pages: a device gets here by having paired and
 * agreed, on the Local sync page, while both pages were open. Four endpoints: `hello` (who is this,
 * what does it let travel), `export` (its container, built per request from its own agreement),
 * `import` (merged as its own agreement allows) and `notice` (the other side turned it off).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalAutoSyncServer(
    private val store: LocalAutoSyncStore,
    private val paired: PairedDeviceStore,
    private val transfer: AutoSyncTransfer,
    private val locks: AutoSyncLocks,
    private val discovery: LocalSyncDiscovery,
    private val deviceName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verifier = LocalAutoSyncAuth.Verifier(clock)
    private val slowdown = LocalAutoSyncAuth.Slowdown()
    private val inFlight = Semaphore(MAX_IN_FLIGHT)

    /** Set by the Local sync page while Sync mode is on. */
    val syncMode = MutableStateFlow(false)

    /** Set by the app: OwnTV is in front. */
    val appVisible = MutableStateFlow(false)

    /** Set by the app: on Wi-Fi or Ethernet (a television always is). */
    val onLan = MutableStateFlow(false)

    private val up = MutableStateFlow(false)

    /** Whether the listener is up right now, for the setting's state line. */
    val listening: StateFlow<Boolean> = up.asStateFlow()

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var watching = false

    /** Starts following the conditions; once per process. */
    fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            combine(store.agreements, store.listenAlways, appVisible, syncMode, onLan) { agreements, always, visible, mode, lan ->
                autoSyncShouldListen(agreements.isNotEmpty(), lan, always, visible, mode)
            }.distinctUntilChanged().collect { on -> if (on) open() else close() }
        }
    }

    private suspend fun open() {
        if (socket != null) return
        val bound = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AUTO_SYNC_PORT))
            }
        }.getOrElse {
            Log.w(TAG, "auto-sync listener could not bind port $AUTO_SYNC_PORT", it)
            return
        }
        socket = bound
        up.value = true
        discovery.advertise(deviceName(), AUTO_SYNC_PORT, paired.selfId())
        Log.i(TAG, "auto-sync listener up on $AUTO_SYNC_PORT")
        scope.launch { acceptLoop(bound) }
    }

    private fun close() {
        val open = socket ?: return
        socket = null
        up.value = false
        discovery.stopAdvertising()
        runCatching { open.close() }
        Log.i(TAG, "auto-sync listener down")
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (!inFlight.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            scope.launch {
                try {
                    client.use { handle(it) }
                } catch (e: IOException) {
                    Log.w(TAG, "auto-sync request dropped: ${e.javaClass.simpleName}")
                } finally {
                    inFlight.release()
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val peer = socket.inetAddress
        val address = peer.hostAddress.orEmpty()
        if (!isLocal(peer)) return refuse(socket, AutoSyncRefusal.NOT_LAN)
        val input = BufferedInputStream(socket.getInputStream())
        val line = readLine(input) ?: return
        val parts = line.split(' ')
        if (parts.size < 3) return send(socket, 400, JSONObject().put("reason", AutoSyncRefusal.BAD_REQUEST.reason))
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
        }
        val route = routeOf(method, path) ?: return send(socket, 404, JSONObject().put("reason", "not-found"))
        val body = CompanionHttpProtocol.readBodyBytes(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
            ?: return send(socket, 413, JSONObject().put("reason", "too-large"))

        slowdown.delayFor(address).takeIf { it > 0 }?.let { delay(it) }
        val caller = headers[LocalAutoSyncAuth.HEADER_DEVICE].orEmpty()
        val device = paired.current().firstOrNull { it.id == caller }
        val verdict = verifier.verify(
            secret = device?.secret,
            method = method,
            path = path,
            time = headers[LocalAutoSyncAuth.HEADER_TIME]?.toLongOrNull() ?: 0L,
            nonce = headers[LocalAutoSyncAuth.HEADER_NONCE].orEmpty(),
            body = body,
            signature = headers[LocalAutoSyncAuth.HEADER_SIGNATURE].orEmpty(),
        )
        if (verdict != LocalAutoSyncAuth.Verdict.OK) {
            slowdown.failed(address)
            return refuse(socket, refusalFor(verdict, hasAgreement = false, route = route) ?: AutoSyncRefusal.UNPAIRED)
        }
        slowdown.succeeded(address)
        device!!
        // Every request it signed says where it is now: a new address from the router is learnt here.
        if (device.address != address) paired.updateAddress(device.id, address)
        // Read per request, never a snapshot: an agreement turned off here refuses the very next one.
        val agreement = store.get(device.id)
        refusalFor(verdict, hasAgreement = agreement != null, route = route)?.let { return refuse(socket, it) }
        when (route) {
            Route.HELLO -> send(
                socket, 200,
                JSONObject()
                    .put("device", paired.selfId())
                    .put("name", deviceName())
                    .put("port", AUTO_SYNC_PORT)
                    .put("sections", JSONArray(agreement!!.sections.map { it.name })),
            )
            Route.EXPORT -> withLock(socket, device.id) {
                val file = transfer.exportFor(agreement!!, device.secret)
                try {
                    sendBytes(socket, 200, "application/octet-stream", file.readBytes())
                } finally {
                    file.parentFile?.deleteRecursively()
                }
            }
            Route.IMPORT -> withLock(socket, device.id) {
                val file = transfer.incomingFile()
                try {
                    file.writeBytes(body)
                    val items = transfer.importFrom(agreement!!, file, device.secret)
                    // The other device completes its pass with this: it counts as one here too.
                    store.recordAttempt(device.id, clock(), success = true)
                    send(socket, 200, JSONObject().put("items", items))
                } finally {
                    file.delete()
                }
            }
            Route.NOTICE -> {
                if (agreement != null) store.turnedOffByThem(device.id, clock())
                send(socket, 200, JSONObject().put("ok", true))
            }
        }
    }

    private suspend fun withLock(socket: Socket, deviceId: String, block: suspend () -> Unit) {
        if (!locks.tryLock(deviceId)) return refuse(socket, AutoSyncRefusal.BUSY)
        try {
            runCatching { block() }.onFailure {
                Log.w(TAG, "auto-sync request failed", it)
                send(socket, 500, JSONObject().put("reason", "failed"))
            }
        } finally {
            locks.unlock(deviceId)
        }
    }

    private fun refuse(socket: Socket, refusal: AutoSyncRefusal) =
        send(socket, refusal.code, JSONObject().put("reason", refusal.reason))

    private fun send(socket: Socket, code: Int, json: JSONObject) =
        sendBytes(socket, code, "application/json; charset=utf-8", json.toString().toByteArray(StandardCharsets.UTF_8))

    private fun sendBytes(socket: Socket, code: Int, type: String, bytes: ByteArray) {
        runCatching {
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $code ${STATUS[code] ?: "OK"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            if (next == '\n'.code) break
            if (next != '\r'.code) buffer.write(next)
            if (buffer.size() > MAX_LINE) return null
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    /** The four things a paired device may ask. */
    enum class Route { HELLO, EXPORT, IMPORT, NOTICE }

    companion object {
        private const val TAG = "LocalAutoSync"
        private const val MAX_IN_FLIGHT = 4
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE = 8 * 1024

        /** The route for a request line; null (answered 404) for anything else — the PIN and pairing routes included. */
        fun routeOf(method: String, path: String): Route? = ROUTES[method.uppercase() to path]

        /**
         * Why a request from the local network is refused, or null when it may go ahead: an unknown
         * caller or a signature that does not match is "unpaired", a stale or reused one a bad request,
         * and anything but a turn-off notice needs an agreement here.
         */
        fun refusalFor(verdict: LocalAutoSyncAuth.Verdict, hasAgreement: Boolean, route: Route): AutoSyncRefusal? = when (verdict) {
            LocalAutoSyncAuth.Verdict.UNKNOWN_DEVICE, LocalAutoSyncAuth.Verdict.BAD_SIGNATURE -> AutoSyncRefusal.UNPAIRED
            LocalAutoSyncAuth.Verdict.STALE, LocalAutoSyncAuth.Verdict.REPLAY -> AutoSyncRefusal.BAD_REQUEST
            LocalAutoSyncAuth.Verdict.OK -> if (route != Route.NOTICE && !hasAgreement) AutoSyncRefusal.NO_AGREEMENT else null
        }

        private val ROUTES = mapOf(
            ("GET" to LocalAutoSyncClient.PATH_HELLO) to Route.HELLO,
            ("GET" to LocalAutoSyncClient.PATH_EXPORT) to Route.EXPORT,
            ("POST" to LocalAutoSyncClient.PATH_IMPORT) to Route.IMPORT,
            ("POST" to LocalAutoSyncClient.PATH_NOTICE) to Route.NOTICE,
        )

        private val STATUS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 409 to "Conflict", 413 to "Payload Too Large", 500 to "Internal Server Error",
        )

        /** A peer on this network: a private, link-local or loopback address. */
        fun isLocal(address: InetAddress?): Boolean =
            address != null && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address !is Inet4Address && isUniqueLocalV6(address)))

        /** IPv6 unique local addresses (fc00::/7), the v6 counterpart of a private range. */
        private fun isUniqueLocalV6(address: InetAddress): Boolean = (address.address.firstOrNull()?.toInt()?.and(0xfe)) == 0xfc
    }
}
