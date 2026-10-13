package tv.own.owntv.core.network

import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The OkHttp client the **playback** engines stream through.
 *
 * It is derived from the app-wide singleton with [OkHttpClient.newBuilder], so it keeps every piece of
 * shared configuration — proxy selector/authenticator, custom DNS, timeouts, HTTP/1.1, the default
 * User-Agent interceptor — and picks up runtime proxy/DNS changes exactly as before. What it does *not*
 * share is the **connection pool**: this one owns its sockets.
 *
 * That split is the whole point (F28). A panel that allows one session per account refuses the second
 * client, so after an engine handoff a pooled ExoPlayer connection can lock mpv out of the channel the
 * user just asked for — the live engine therefore evicts its idle sockets on every stop. Evicting the
 * *singleton's* pool did that to the whole app: EPG downloads, panel API calls, TMDB metadata and Coil
 * image loads all lost keep-alive on every zap and paid a fresh TCP+TLS handshake afterwards.
 * [evictAll] here touches stream connections only.
 */
class StreamingHttpClient(base: OkHttpClient) {

    /** Sized for the handful of concurrent stream/segment connections one playback session opens; the
     *  idle keep-alive matches OkHttp's own default, since a zap back to the previous channel benefits
     *  from a warm socket and only a *stop* clears the pool. */
    private val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

    private val monitor = CallMonitor()

    /** Unlike the shared client, OkHttp may retry a connection failure here (E18): a stream GET is
     *  idempotent, and a pooled socket the panel half-closed would otherwise fail the first playlist or
     *  segment request after a zap back. Sync keeps its own retries, so the shared client stays off. */
    val client: OkHttpClient = base.newBuilder()
        .connectionPool(pool)
        .retryOnConnectionFailure(true)
        .eventListener(monitor)
        .build()

    /** Close the idle stream sockets. Connections with a call in flight are untouched — OkHttp only
     *  evicts what nothing is using. */
    fun evictAll() {
        pool.evictAll()
    }

    /** Stream requests in flight right now (sync and async, as OkHttp's dispatcher counts them). */
    val callsInFlight: Int get() = client.dispatcher.runningCallsCount()

    /**
     * The release gate's last step before another server is opened (host failover, A6 step 4): wait,
     * bounded by [timeoutMs], until no stream request is running on this client, then close every idle
     * socket. ExoPlayer's `stop()` only asks its loader thread to cancel; this is what proves it did.
     * Returns false when calls were still running at the deadline; those in-flight calls are cancelled
     * and idle sockets are closed either way (the caller logs the timeout).
     */
    suspend fun awaitQuiescent(timeoutMs: Long = QUIESCENT_TIMEOUT_MS): Boolean {
        var waited = 0L
        while (callsInFlight > 0) {
            if (waited >= timeoutMs) {
                client.dispatcher.cancelAll()
                evictAll()
                return false
            }
            delay(QUIESCENT_POLL_MS)
            waited += QUIESCENT_POLL_MS
        }
        evictAll()
        return true
    }

    /**
     * AC6 evidence: how many stream calls were open at once since [markPeak]. A one-connection account
     * must never see two across a server switch; the switch logs this after the new server played.
     */
    fun peakCallsSinceMark(): Int = monitor.peak.get()

    fun markPeak() {
        monitor.peak.set(monitor.open.get())
    }

    /** Counts open stream calls for [peakCallsSinceMark]. Cheap: two atomics per call. */
    private class CallMonitor : EventListener() {
        val open = AtomicInteger(0)
        val peak = AtomicInteger(0)
        override fun callStart(call: Call) {
            val now = open.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        }
        override fun callEnd(call: Call) { open.decrementAndGet() }
        override fun callFailed(call: Call, ioe: IOException) { open.decrementAndGet() }
        override fun canceled(call: Call) = Unit
    }

    private companion object {
        const val MAX_IDLE_CONNECTIONS = 5
        const val KEEP_ALIVE_MINUTES = 5L
        const val QUIESCENT_TIMEOUT_MS = 2_000L
        const val QUIESCENT_POLL_MS = 25L
    }
}
