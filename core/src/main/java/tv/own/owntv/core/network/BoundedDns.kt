package tv.own.owntv.core.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DNS for live streams: a lookup that answers within [timeoutMs] — or [staleTimeoutMs] once there is a
 * last good answer to fall back on — and that last good answer when it cannot.
 *
 * The platform resolver has no deadline a caller can set. With the uplink down but the local link up,
 * one lookup was measured hanging for 80 s, and every reconnect queued behind it — the stream could
 * not even try again until DNS gave up. Bounding the wait keeps the reconnects moving.
 *
 * The fallback matters as much: during an outage every lookup fails, so the first connect after the
 * uplink returns would wait for the resolver to recover as well. Handing back the addresses that
 * worked a moment ago lets that connect go out straight away. A fresh answer always wins; a stale one
 * is used only when there is no fresh one at all.
 *
 * A lookup that times out keeps running on its thread until the resolver gives up — the platform call
 * cannot be interrupted — so concurrent lookups of the same host share one in-flight query instead of
 * stacking threads.
 */
class BoundedDns(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** The wait when a last good answer is already in hand — short, since falling back costs nothing. */
    private val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
    /** The resolver this bounds — the client's own, so the user's custom DNS / DoH setting still applies. */
    val delegate: Dns = Dns.SYSTEM,
    /** One line per fallback, for the caller's diagnostics log. The hostname is masked ([HttpClient.redactHost]). */
    private val log: (String) -> Unit = {},
) : Dns {
    private val lastGood = ConcurrentHashMap<String, List<InetAddress>>()
    private val inFlight = ConcurrentHashMap<String, Future<List<InetAddress>>>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "owntv-dns").apply { isDaemon = true }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Registered before it runs, and it removes only itself when done — so a finished query can never
        // be left in the map to answer every later lookup with its old result. done() runs only after the
        // waiters are released, so a lookup straight after another can still find it there: a finished
        // query is replaced, never joined.
        val mine = object : FutureTask<List<InetAddress>>(Callable { delegate.lookup(hostname) }) {
            override fun done() { inFlight.remove(hostname, this) }
        }
        val pending = inFlight.compute(hostname) { _, current -> if (current == null || current.isDone) mine else current }!!
        if (pending === mine) executor.execute(mine)
        // Always an UnknownHostException: it is the only failure OkHttp's Dns contract lets through.
        val failure: UnknownHostException = try {
            val waitMs = if (lastGood.containsKey(hostname)) staleTimeoutMs else timeoutMs
            return pending.get(waitMs, TimeUnit.MILLISECONDS).also { lastGood[hostname] = it }
        } catch (e: TimeoutException) {
            UnknownHostException("${HttpClient.redactHost(hostname)}: no DNS answer in time")
        } catch (e: ExecutionException) {
            e.cause as? UnknownHostException ?: UnknownHostException(hostname).apply { initCause(e.cause) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            UnknownHostException(hostname)
        }
        lastGood[hostname]?.let { stale ->
            log("dns ${HttpClient.redactHost(hostname)} failed (${failure.javaClass.simpleName}) — using its last known addresses")
            return stale
        }
        throw failure
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        /** Measured outage: with 5s here, every reconnect spent 5s on DNS before it could even connect. */
        const val DEFAULT_STALE_TIMEOUT_MS = 1_000L
    }
}
