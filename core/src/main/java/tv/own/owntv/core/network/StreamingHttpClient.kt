package tv.own.owntv.core.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    /** Unlike the shared client, OkHttp may retry a connection failure here (E18): a stream GET is
     *  idempotent, and a pooled socket the panel half-closed would otherwise fail the first playlist or
     *  segment request after a zap back. Sync keeps its own retries, so the shared client stays off. */
    val client: OkHttpClient = base.newBuilder().connectionPool(pool).retryOnConnectionFailure(true).build()

    /** Close the idle stream sockets. Connections with a call in flight are untouched — OkHttp only
     *  evicts what nothing is using. */
    fun evictAll() {
        pool.evictAll()
    }

    private companion object {
        const val MAX_IDLE_CONNECTIONS = 5
        const val KEEP_ALIVE_MINUTES = 5L
    }
}
