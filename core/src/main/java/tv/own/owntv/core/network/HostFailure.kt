package tv.own.owntv.core.network

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What a failed request or stream open says about the **server** (design §5, appendix A5).
 *
 * Only [HOST] moves to the next server. The split is "before any response headers" versus "after": a
 * server that never answered is dead or unreachable from here; one that answered and then misbehaved
 * (a slow cold restream, a read timeout, an HTML page, a 503 refusal) is alive, and every server of the
 * pool shares its backend, so the next one would answer the same.
 */
enum class HostFailureClass {
    /** Dead or unreachable before headers: try the next server. */
    HOST,

    /** The account's one session is still held (458/407/509/"max connections"): wait on the same server. */
    WAIT,

    /** Not the server's fault, or not a fault the next server would fix: never switch. */
    NEVER,

    /** No typed evidence either way (an unclassified mpv "loading failed"): never switch by itself, but run
     *  one sequential preflight after the release gate and act on that. */
    UNKNOWN,
}

object HostFailure {

    /** Codes that mean the server, a proxy in front of it, or Cloudflare could not reach the origin. */
    fun classifyCode(code: Int): HostFailureClass = when (code) {
        500, 502, 504, in 520..527, 530 -> HostFailureClass.HOST
        458, 407, 509 -> HostFailureClass.WAIT
        else -> HostFailureClass.NEVER // 503 on every host (D2 superseded by R1), 401/402/403/404/429, 2xx…
    }

    /**
     * A JVM failure from the request side (OkHttp, the panel API, the preflight). [beforeHeaders] tells a
     * timeout waiting for headers (the per-host budget, [HostFailureClass.HOST]) from a read timeout in a
     * body that had already started ([HostFailureClass.NEVER]); pass null when the caller can't know, and a
     * plain timeout then counts by its message (OkHttp says "connect timed out" for the connect phase).
     */
    fun classify(t: Throwable?, beforeHeaders: Boolean? = null): HostFailureClass {
        var cause = t
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
            when (cause) {
                is HttpClient.HttpStatusException -> return classifyCode(cause.code)
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                    return HostFailureClass.HOST
                is SSLHandshakeException, is SSLPeerUnverifiedException, is CertificateException -> return HostFailureClass.HOST
                is SocketTimeoutException -> {
                    val connectPhase = cause.message?.lowercase()?.contains("connect") == true
                    return if (connectPhase || beforeHeaders == true) HostFailureClass.HOST else HostFailureClass.NEVER
                }
                // EOF or a reset after a 2xx is the body dying, not the server (A5). With no headers yet
                // there is no evidence either way, so it stays unknown and a preflight decides.
                is EOFException -> return if (beforeHeaders == false) HostFailureClass.NEVER else HostFailureClass.UNKNOWN
                is SocketException -> {
                    val msg = cause.message?.lowercase().orEmpty()
                    if (beforeHeaders == false && ("reset" in msg || "broken pipe" in msg)) return HostFailureClass.NEVER
                }
            }
            if (cause.cause === cause) break
            cause = cause.cause
        }
        return HostFailureClass.UNKNOWN
    }

    /**
     * An mpv/FFmpeg error line. Exact phrases only (from FFmpeg's `http.c`/`tcp.c`/`tls_*.c` and mpv's
     * stream layer), because the old substring classifier read "timeout" as both a connect and a read
     * timeout. Anything not in the table is [HostFailureClass.UNKNOWN].
     */
    fun classifyMpv(line: String): HostFailureClass {
        val l = line.lowercase()
        HTTP_ERROR_RX.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { return classifyCode(it) }
        SERVER_RETURNED_RX.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { return classifyCode(it) }
        return when {
            MPV_NEVER.any { it in l } -> HostFailureClass.NEVER
            MPV_WAIT.any { it in l } -> HostFailureClass.WAIT
            MPV_HOST.any { it in l } -> HostFailureClass.HOST
            else -> HostFailureClass.UNKNOWN
        }
    }

    /**
     * A redirect target outside the pool (a CDN, a media server) is not the panel (A5). The first such
     * failure is [HostFailureClass.NEVER], so the existing rejoin-through-panel runs and nothing switches.
     * Once that rejoin has been spent ([rejoined]), the same host-class failure counts against the pool
     * origin we opened. A hop we can't place, or one that is the origin we opened, is left as [cls].
     */
    fun forPoolOrigin(
        cls: HostFailureClass,
        openedOrigin: String?,
        hopOrigin: String?,
        rejoined: Boolean,
    ): HostFailureClass {
        if (cls != HostFailureClass.HOST) return cls
        if (hopOrigin == null || openedOrigin == null || hopOrigin == openedOrigin) return cls
        return if (rejoined) cls else HostFailureClass.NEVER
    }

    /** A Cloudflare challenge page (403 + `cf-mitigated: challenge`, or its "Just a moment…" body): "Blocked". */
    fun isCloudflareChallenge(code: Int, mitigatedHeader: String?, bodyStart: String?): Boolean =
        code == 403 && (mitigatedHeader.equals("challenge", ignoreCase = true) ||
            bodyStart?.contains("just a moment", ignoreCase = true) == true)

    private const val MAX_CAUSE_DEPTH = 12

    /** FFmpeg's `HTTP error 502 Bad Gateway` (http.c logs it for every non-2xx). */
    private val HTTP_ERROR_RX = Regex("""http error (\d{3})""")

    /** FFmpeg's `Server returned 404 Not Found`; the generic "5XX" form has no number and stays unknown. */
    private val SERVER_RETURNED_RX = Regex("""server returned (\d{3})\b""")

    private val MPV_HOST = listOf(
        "failed to resolve hostname",
        "name or service not known",
        "no address associated with hostname",
        "connection refused",
        "no route to host",
        "network is unreachable",
        "connection timed out", // tcp.c connect(): ETIMEDOUT, i.e. no SYN-ACK
        "tls handshake failed",
        "ssl_do_handshake",
        "certificate verify failed",
        "unable to verify the first certificate",
    )

    private val MPV_WAIT = listOf("max connections", "maximum connections", "too many connections")

    /** After a response: the server is alive. "end of file" covers EOF/reset after a 200. */
    private val MPV_NEVER = listOf(
        "invalid data found when processing input",
        "unrecognized file format",
        "stream ends prematurely",
        "end of file",
        "connection reset by peer",
        "read timed out",
        "will reconnect at",
        // Cold restream: headers came back and the body has not started. Not a dead server.
        "no bytes yet",
    )
}
