package tv.own.owntv.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Immutable snapshot of the global custom DNS config — a sibling to the global proxy.
 * Two modes: a plain DNS-over-UDP IP (host + port), or a DNS-over-HTTPS (DoH) URL.
 *
 * DNS resolution happens BEFORE a request is sent, so this is a per-OkHttpClient setting,
 * not a per-request one. The DNS is resolved by the singleton client, which reads the
 * live snapshot from [DnsConfigHolder.current] on every lookup.
 */
data class DnsConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DNS_DEFAULT_PORT,
    val dohUrl: String = "",
) {
    /** Host:port mode enabled with a sane host/port. */
    val plainUsable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535

    /** DoH mode enabled with a URL. */
    val dohUsable: Boolean get() = enabled && dohUrl.isNotBlank()

    companion object {
        const val DNS_DEFAULT_PORT = 53
    }
}

/**
 * Well-known DNS-over-HTTPS endpoints — offered as one-tap presets in the settings UI.
 *
 * Asked in the standard binary form (RFC 8484, `?dns=`), which all three answer. The JSON form these
 * URLs were once used with works on Cloudflare only: Google and Quad9 answer it with HTTP 400 on
 * `/dns-query` (tested 2026-09-23).
 */
object DohPresets {
    val GOOGLE = "https://dns.google/dns-query"
    val CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
    val QUAD9 = "https://dns.quad9.net/dns-query"

    val all = listOf(
        "Google" to GOOGLE,
        "Cloudflare" to CLOUDFLARE,
        "Quad9" to QUAD9,
    )
}

/**
 * Live holder for the custom DNS config — same pattern as [ProxyConfigHolder].
 * Provides an OkHttp [Dns] that reads the live snapshot so DNS can be toggled at
 * runtime without rebuilding the singleton [OkHttpClient].
 *
 * DNS-over-HTTPS bootstraps itself: the DoH requests go through a separate bootstrap
 * OkHttpClient that uses system DNS so there is no infinite loop.
 */
class DnsConfigHolder(
    configFlow: Flow<DnsConfig>,
    /** A config to use from the start (the settings screens' Test button). Without one, lookups wait
     *  for the stored setting's first read — see [FirstRead]. */
    initialConfig: DnsConfig? = null,
    private val fallbackToSystem: Boolean = true,
) {

    @Volatile
    private var current: DnsConfig = initialConfig ?: DnsConfig()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val firstRead = FirstRead("dns", open = initialConfig != null)

    private val cache = DnsCache()

    /** Bootstrap client for DoH requests — always uses system DNS, never our custom DNS. */
    private val bootstrapClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    init {
        configFlow.onEach { current = it; firstRead.arrived() }.launchIn(scope)
    }

    fun snapshot(): DnsConfig = current

    /**
     * The [Dns] implementation fed to OkHttpClient.Builder. On every lookup it reads
     * the live [current] snapshot and routes to the appropriate backend, falling back
     * to system DNS when custom DNS is disabled or errors occur.
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            firstRead.await()
            val cfg = current
            if (!cfg.dohUsable && !cfg.plainUsable) return Dns.SYSTEM.lookup(hostname)
            // The stream pool is emptied on every live stop, so each zap opens a new connection and
            // asks again; a minute's memory keeps the lookup off the zap.
            val key = "${cfg.dohUrl}|${cfg.host}:${cfg.port}|$hostname"
            cache.get(key)?.let { return it }
            val result = if (cfg.dohUsable) resolveViaDoH(hostname, cfg.dohUrl) else resolveViaUdp(hostname, cfg.host, cfg.port)
            cache.put(key, result)
            return result
        }
    }

    /**
     * A and AAAA at the same time. Once A has answered, AAAA gets [AAAA_GRACE_MS] more: a server that
     * drops AAAA questions used to add its whole 5 s timeout to every new connection. An AAAA failure
     * never costs the A answers; an A failure is the lookup's failure, as before.
     */
    private fun bothFamilies(ask: (Int) -> List<InetAddress>): List<InetAddress> {
        val aaaa = lookupPool.submit<List<InetAddress>> { ask(DNS_TYPE_AAAA) }
        val a = try {
            ask(DNS_TYPE_A)
        } catch (e: Exception) {
            aaaa.cancel(true)
            throw e
        }
        val v6 = runCatching {
            if (a.isEmpty()) aaaa.get() else aaaa.get(AAAA_GRACE_MS, TimeUnit.MILLISECONDS)
        }.getOrElse { aaaa.cancel(true); emptyList() }
        return a + v6
    }

    // --- DNS-over-HTTPS (RFC 8484) via manual HTTP requests ---

    private fun resolveViaDoH(hostname: String, dohUrl: String): List<InetAddress> {
        return try {
            val results = bothFamilies { qtype -> dohLookup(hostname, dohUrl, qtype) }
            if (results.isNotEmpty()) {
                Log.d(TAG, "DoH lookup $hostname → ${results.map { it.hostAddress }} (server=$dohUrl)")
                results
            } else {
                if (!fallbackToSystem) return emptyList()
                Log.w(TAG, "DoH lookup returned no addresses for $hostname, falling back to system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
        } catch (e: Exception) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "DoH lookup failed for $hostname, falling back to system DNS", e)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** The standard binary query first; a server that does not answer it gets the JSON form. */
    private fun dohLookup(hostname: String, dohUrl: String, qtype: Int): List<InetAddress> {
        val wire = Request.Builder().url(dohWireUrl(dohUrl, hostname, qtype))
            .header("Accept", DNS_MESSAGE)
            .build()
        bootstrapClient.newCall(wire).execute().use { resp ->
            if (resp.isSuccessful && resp.body.contentType()?.toString()?.startsWith(DNS_MESSAGE) == true) {
                return parseDnsResponse(resp.body.bytes(), expectedId = 0)
            }
        }
        val type = if (qtype == DNS_TYPE_AAAA) "AAAA" else "A"
        val json = Request.Builder().url("${dohUrl.trimEnd('/')}?name=$hostname&type=$type")
            .header("Accept", "application/dns-json")
            .build()
        return bootstrapClient.newCall(json).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("DoH HTTP ${resp.code}")
            parseDohJson(resp.body.string())
        }
    }

    // --- Plain DNS-over-UDP resolver ---

    private fun resolveViaUdp(hostname: String, server: String, port: Int): List<InetAddress> {
        try {
            // Both families, like the DoH path: asking only for A left an AAAA-only host unresolvable
            // whenever custom plain DNS was in use. A failure of the AAAA half never costs the A answers.
            val result = bothFamilies { qtype -> askUdp(hostname, qtype, server, port) }
            Log.d(TAG, "UDP DNS lookup $hostname → ${result.map { it.hostAddress }} (server=$server:$port)")
            return if (result.isNotEmpty() || !fallbackToSystem) result else Dns.SYSTEM.lookup(hostname)
        } catch (e: SocketTimeoutException) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "UDP DNS timeout for $hostname via $server:$port, falling back to system DNS")
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: Exception) {
            if (!fallbackToSystem) throw e
            Log.w(TAG, "UDP DNS lookup failed for $hostname via $server:$port, falling back to system DNS", e)
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** One question of [qtype], with its own random ID checked against the answer. */
    @Throws(Exception::class)
    private fun askUdp(hostname: String, qtype: Int, server: String, port: Int): List<InetAddress> {
        val id = randomQueryId()
        val response = sendUdpQuery(buildDnsQuery(hostname, qtype, id), server, port)
        return parseDnsResponse(response, id)
    }

    @Throws(Exception::class)
    private fun sendUdpQuery(query: ByteArray, server: String, port: Int): ByteArray {
        val sock = DatagramSocket()
        sock.soTimeout = REQUEST_TIMEOUT_MS
        try {
            val addr = InetSocketAddress(server, port)
            val req = DatagramPacket(query, query.size, addr)
            sock.send(req)
            val buf = ByteArray(RESPONSE_BUFFER_SIZE)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return resp.data.copyOf(resp.length)
        } finally {
            sock.close()
        }
    }

    companion object {
        private const val TAG = "DnsConfig"
        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val RESPONSE_BUFFER_SIZE = 1024

        // DNS header offsets (12 bytes)
        private const val DNS_HEADER_LEN = 12
        private const val DNS_FLAG_QR_MASK = 0x8000
        private const val DNS_FLAG_OPCODE_MASK = 0x7800
        private const val DNS_FLAG_RCODE_MASK = 0x000F
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_CLASS_IN = 1
        private const val DNS_MESSAGE = "application/dns-message"
        private const val AAAA_GRACE_MS = 500L

        /** Runs the AAAA half of each lookup beside the A half. Daemon threads, created on demand. */
        private val lookupPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "owntv-dns").apply { isDaemon = true }
        }

        /** RFC 8484 GET: the query with ID 0, as the RFC asks, in base64url without padding. */
        fun dohWireUrl(dohUrl: String, hostname: String, qtype: Int): String {
            val query = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(buildDnsQuery(hostname, qtype, id = 0))
            val base = dohUrl.trimEnd('/')
            return "$base${if ('?' in base) '&' else '?'}dns=$query"
        }

        /**
         * The JSON form's addresses. Only A (1) and AAAA (28) answers count: a CNAME's `data` is a host
         * name, and handing that to [InetAddress.getByName] resolved it through the *system* DNS — the
         * very thing custom DNS is there to avoid, on every CDN host. Anything that is not an IP literal
         * is skipped for the same reason.
         */
        fun parseDohJson(json: String): List<InetAddress> {
            val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
            val results = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                val data = a.optString("data", "")
                val literal = when (a.optInt("type")) {
                    DNS_TYPE_A -> IPV4_LITERAL.matches(data)
                    DNS_TYPE_AAAA -> ':' in data && data.all { it == ':' || it == '.' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    else -> false
                }
                if (literal) runCatching { InetAddress.getByName(data) }.getOrNull()?.let(results::add)
            }
            return results
        }

        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /**
         * One DNS question for [hostname].
         *
         * [qtype] is [DNS_TYPE_A] or [DNS_TYPE_AAAA] — the UDP resolver asks for both, because DoH always
         * did and a host that publishes only AAAA was unreachable through the plain-DNS path alone.
         *
         * [id] is random per query and checked on the way back. It used to be the constant 1, which makes
         * an off-path forged answer trivial to accept: anything arriving on the socket in time matched.
         */
        fun buildDnsQuery(hostname: String, qtype: Int = DNS_TYPE_A, id: Int = randomQueryId()): ByteArray {
            val labels = hostname.split('.')
            // Header (12) + labels + null terminator + QTYPE (2) + QCLASS (2)
            var size = DNS_HEADER_LEN + 1 + 4 // minimum
            for (label in labels) size += label.length + 1
            val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            // Header
            buf.putShort(id.toShort()) // ID — random, verified in parseDnsResponse
            buf.putShort(0x0100) // Flags: standard query, recursion desired
            buf.putShort(0x0001) // QDCOUNT = 1
            buf.putShort(0x0000) // ANCOUNT = 0
            buf.putShort(0x0000) // NSCOUNT = 0
            buf.putShort(0x0000) // ARCOUNT = 0
            // Question — QNAME
            for (label in labels) {
                buf.put(label.length.toByte())
                buf.put(label.encodeToByteArray())
            }
            buf.put(0x00.toByte()) // null terminator
            buf.putShort(qtype.toShort())
            buf.putShort(DNS_CLASS_IN.toShort())
            return buf.array()
        }

        /** A query ID in 1..65535 from a cryptographically strong source. */
        fun randomQueryId(): Int = java.security.SecureRandom().nextInt(0xFFFF) + 1

        fun parseDnsResponse(data: ByteArray, expectedId: Int? = null): List<InetAddress> {
            if (data.size < DNS_HEADER_LEN) return emptyList()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val id = buf.getShort()
            val flags = buf.getShort()
            if (expectedId != null && id != expectedId.toShort()) return emptyList()
            // Check QR (response), no error, standard query
            val qr = (flags.toInt() and DNS_FLAG_QR_MASK) != 0
            val rcode = flags.toInt() and DNS_FLAG_RCODE_MASK
            val opcode = (flags.toInt() and DNS_FLAG_OPCODE_MASK) ushr 11
            if (!qr || rcode != 0 || opcode != 0) return emptyList()
            val qdCount = buf.getShort().toInt() and 0xFFFF
            val anCount = buf.getShort().toInt() and 0xFFFF

            // Skip question section. It starts after the whole 12-byte header: NSCOUNT and ARCOUNT are not
            // read above, and starting at the read position (byte 8) misread every answer as nothing — so
            // custom plain DNS silently fell back to the system resolver on every lookup.
            var pos = DNS_HEADER_LEN
            for (q in 0 until qdCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0) return emptyList()
                pos += 4 // QTYPE + QCLASS
            }

            // Parse answer records
            val results = mutableListOf<InetAddress>()
            for (a in 0 until anCount) {
                pos = skipDnsName(data, pos)
                if (pos < 0) break
                if (pos + 10 > data.size) break
                val atype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val rdLen = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
                val rdPos = pos + 10
                if (rdPos + rdLen > data.size) break
                when (atype) {
                    DNS_TYPE_A -> {
                        if (rdLen == 4 && rdPos + 4 <= data.size) {
                            results.add(Inet4Address.getByAddress(data.copyOfRange(rdPos, rdPos + 4)))
                        }
                    }
                    DNS_TYPE_AAAA -> {
                        if (rdLen == 16 && rdPos + 16 <= data.size) {
                            results.add(Inet6Address.getByAddress(null, data.copyOfRange(rdPos, rdPos + 16)))
                        }
                    }
                }
                pos = rdPos + rdLen
            }
            return results
        }

        private fun skipDnsName(data: ByteArray, start: Int): Int {
            if (start >= data.size) return -1
            var pos = start
            while (pos < data.size) {
                val len = data[pos].toInt() and 0xFF
                if (len == 0) return pos + 1 // null terminator
                if (len and 0xC0 == 0xC0) return pos + 2 // pointer (2-byte)
                pos += 1 + len
            }
            return -1
        }
    }
}

/**
 * A minute's memory of custom-DNS answers, per server and host name. Deliberately not the records'
 * own TTL: the UDP parser does not read it, and a minute is short enough that a moved CDN host is
 * followed quickly. Empty answers are never kept, and the whole map is dropped once it is [MAX] long.
 */
internal class DnsCache(
    private val ttlMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val addresses: List<InetAddress>, val until: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<InetAddress>? {
        val e = entries[key] ?: return null
        if (e.until > now()) return e.addresses
        entries.remove(key, e)
        return null
    }

    fun put(key: String, addresses: List<InetAddress>) {
        if (addresses.isEmpty()) return
        if (entries.size >= MAX) entries.clear()
        entries[key] = Entry(addresses, now() + ttlMs)
    }

    private companion object {
        const val MAX = 256
    }
}
