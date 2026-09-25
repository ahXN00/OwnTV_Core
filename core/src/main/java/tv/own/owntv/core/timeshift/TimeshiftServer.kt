package tv.own.owntv.core.timeshift

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Serves timeshift buffers to the players over loopback HTTP (N4, P16b).
 *
 * Both engines already play a live `.ts` (or fragmented-MP4) stream over HTTP better than anything
 * else, so the saved copy is handed to them in exactly that shape: `GET /<token>/<piece>` streams the
 * buffer from that piece onwards and never ends while the channel is saved. A rewind is a new request
 * at an earlier piece — the same "open the stream at a point" the catch-up rewind already does.
 *
 * Bound to 127.0.0.1 only and every URL carries the session's unguessable token, so nothing else on the
 * network, and no stale URL, can read a buffer. One thread per request; there are at most a handful
 * (the player, its occasional reopen).
 */
object TimeshiftServer {

    private val sessions = ConcurrentHashMap<String, TimeshiftSession>()
    @Volatile private var socket: ServerSocket? = null

    fun register(session: TimeshiftSession) {
        sessions[session.token] = session
        ensureStarted()
    }

    fun unregister(session: TimeshiftSession) {
        sessions.remove(session.token, session)
    }

    /** The address a player opens to watch [session] from piece [fromIndex], or from the live edge (null). */
    fun urlFor(session: TimeshiftSession, fromIndex: Long?): String {
        val port = ensureStarted()
        return "http://$HOST:$port/${session.token}/${fromIndex ?: LIVE}.${session.container.extension}"
    }

    /** True for an address this server hands out — so a proxy or a URL rewrite leaves it alone. */
    fun isLocal(url: String?): Boolean = url != null && url.startsWith("http://$HOST:")

    /** Two addresses of the same buffer (different start pieces) — one channel continuing, not a zap. */
    fun sameBuffer(a: String?, b: String?): Boolean =
        isLocal(a) && isLocal(b) && a!!.substringBeforeLast('/') == b!!.substringBeforeLast('/')

    @Synchronized
    private fun ensureStarted(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName(HOST))
        socket = server
        thread(name = "owntv-timeshift-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                thread(name = "owntv-timeshift-client", isDaemon = true) { serve(client) }
            }
        }
        return server.localPort
    }

    private fun serve(client: Socket) {
        client.use { s ->
            runCatching {
                s.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                // Drain the headers; nothing in them changes the answer (a Range is answered with the whole stream).
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val out = s.getOutputStream()
                val target = parts.getOrNull(1)?.trim('/')?.split('/')
                val session = target?.getOrNull(0)?.let { sessions[it] }
                val name = target?.getOrNull(1)?.substringBefore('.')
                val from = name?.toLongOrNull()
                if (parts.firstOrNull() !in METHODS || session == null || (from == null && name != LIVE) || session.isClosed) {
                    out.write(NOT_FOUND.toByteArray(Charsets.ISO_8859_1))
                    return
                }
                val header = "HTTP/1.1 200 OK\r\nContent-Type: ${session.container.mimeType}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                if (parts[0] == HEAD_METHOD) return
                session.open(from).use { input ->
                    val buffer = ByteArray(COPY_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        out.flush()
                    }
                }
            }
            // A player that closes its socket (a seek, a zap, a stop) ends here with a broken pipe: normal.
        }
    }

    private const val HOST = "127.0.0.1"
    private const val LIVE = "live"
    private const val BACKLOG = 8
    private const val COPY_BYTES = 64 * 1024
    private const val HEAD_METHOD = "HEAD"
    private val METHODS = setOf("GET", HEAD_METHOD)
    private const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
}
