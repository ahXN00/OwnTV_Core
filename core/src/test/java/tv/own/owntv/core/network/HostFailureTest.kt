package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.HostPreflight.isHostClass
import tv.own.owntv.core.parser.XtreamClient
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** AC9 (request side and mpv): every A5 row; every never-class row produces no switch. */
class HostFailureTest {

    private fun code(c: Int) = HostFailure.classify(HttpClient.HttpStatusException(c, "HTTP $c"))

    @Test
    fun `before-headers failures are host-class`() {
        assertEquals(HostFailureClass.HOST, HostFailure.classify(UnknownHostException("x")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(UnknownHostException("•••: no DNS answer in time")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(ConnectException("refused")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(NoRouteToHostException()))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(SocketTimeoutException("connect timed out")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(SSLHandshakeException("handshake")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(SSLPeerUnverifiedException("certificate")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(CertificateException("certificate")))
        assertEquals(HostFailureClass.HOST, HostFailure.classify(IOException("wrapped", ConnectException("refused"))))
        // The per-host budget: no headers in time.
        assertEquals(HostFailureClass.HOST, HostFailure.classify(SocketTimeoutException("timeout"), beforeHeaders = true))
        listOf(500, 502, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530).forEach { assertEquals("$it", HostFailureClass.HOST, code(it)) }
    }

    @Test
    fun `after-headers failures and refusals never switch`() {
        assertEquals(HostFailureClass.NEVER, HostFailure.classify(SocketTimeoutException("timeout"), beforeHeaders = false))
        assertEquals(HostFailureClass.NEVER, HostFailure.classify(SocketTimeoutException("Read timed out")))
        // EOF / reset after a 2xx: the server answered. A bare EOF with no header evidence stays unknown.
        assertEquals(HostFailureClass.NEVER, HostFailure.classify(EOFException(), beforeHeaders = false))
        assertEquals(HostFailureClass.NEVER, HostFailure.classify(SocketException("Connection reset"), beforeHeaders = false))
        assertEquals(HostFailureClass.NEVER, HostFailure.classify(SocketException("Broken pipe"), beforeHeaders = false))
        assertEquals(HostFailureClass.UNKNOWN, HostFailure.classify(EOFException()))
        listOf(503, 401, 402, 403, 404, 429, 200).forEach { assertEquals("$it", HostFailureClass.NEVER, code(it)) }
        assertEquals(HostFailureClass.UNKNOWN, HostFailure.classify(IllegalStateException("format")))
    }

    @Test
    fun `session-limit answers wait on the same server`() {
        listOf(458, 407, 509).forEach { assertEquals("$it", HostFailureClass.WAIT, code(it)) }
    }

    @Test
    fun `the mpv message table`() {
        val host = listOf(
            "[ffmpeg] tcp: Failed to resolve hostname pro.example.top: Name or service not known",
            "[ffmpeg] tcp: Connection to tcp://10.255.255.1:80 failed: Connection timed out",
            "[ffmpeg] tcp: Connection to tcp://h:80 failed: Connection refused",
            "[ffmpeg] tcp: Connection to tcp://h:80 failed: No route to host",
            "[ffmpeg] tls: TLS handshake failed",
            "[ffmpeg] http: HTTP error 502 Bad Gateway",
            "[ffmpeg] http: HTTP error 522 <none>",
        )
        host.forEach { assertEquals(it, HostFailureClass.HOST, HostFailure.classifyMpv(it)) }
        val never = listOf(
            "[ffmpeg] http: HTTP error 503 Service Unavailable",
            "[ffmpeg] http: HTTP error 403 Forbidden",
            "[ffmpeg] Server returned 404 Not Found",
            "[ffmpeg/demuxer] lavf: Failed to open: Invalid data found when processing input",
            "[ffmpeg] http: Stream ends prematurely at 1234, should be 99999",
            "[ffmpeg] http: Will reconnect at 1234 in 0 second(s), error=End of file.",
            "no bytes yet before the first frame",
            "[ffmpeg/demuxer] lavf: Unrecognized file format",
        )
        never.forEach { assertEquals(it, HostFailureClass.NEVER, HostFailure.classifyMpv(it)) }
        listOf(
            "[ffmpeg] http: HTTP error 458 <none>",
            "[ffmpeg] http: HTTP error 509 Bandwidth Limit Exceeded",
            "max connections reached",
        ).forEach { assertEquals(it, HostFailureClass.WAIT, HostFailure.classifyMpv(it)) }
        // No evidence: never switches by itself (a preflight decides).
        assertEquals(HostFailureClass.UNKNOWN, HostFailure.classifyMpv("[cplayer] loading failed"))
        assertEquals(HostFailureClass.UNKNOWN, HostFailure.classifyMpv("Server returned 5XX Server Error reply"))
    }

    @Test
    fun `the mpv fixtures classify as captured`() {
        val dir = java.io.File(javaClass.classLoader!!.getResource("mpv-errors")!!.toURI())
        val files = dir.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val expected = HostFailureClass.valueOf(f.nameWithoutExtension.substringBefore('-').uppercase())
            f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach {
                assertEquals("${f.name}: $it", expected, HostFailure.classifyMpv(it))
            }
        }
    }

    @Test
    fun `a redirect target outside the pool is rejoined before it counts against the panel`() {
        val panel = "http://a.example.top"
        val cdn = "http://cdn.example.net"
        assertEquals(HostFailureClass.NEVER, HostFailure.forPoolOrigin(HostFailureClass.HOST, panel, cdn, rejoined = false))
        assertEquals(HostFailureClass.HOST, HostFailure.forPoolOrigin(HostFailureClass.HOST, panel, cdn, rejoined = true))
        // The hop we opened, or a hop we can't place, is the panel's own failure.
        assertEquals(HostFailureClass.HOST, HostFailure.forPoolOrigin(HostFailureClass.HOST, panel, panel, rejoined = false))
        assertEquals(HostFailureClass.HOST, HostFailure.forPoolOrigin(HostFailureClass.HOST, panel, null, rejoined = false))
        // A never-class or a wait on the CDN stays that: it must not become a switch.
        assertEquals(HostFailureClass.NEVER, HostFailure.forPoolOrigin(HostFailureClass.NEVER, panel, cdn, rejoined = true))
        assertEquals(HostFailureClass.WAIT, HostFailure.forPoolOrigin(HostFailureClass.WAIT, panel, cdn, rejoined = false))
    }

    @Test
    fun `Cloudflare's challenge is recognised by header or body, only on a 403`() {
        assertTrue(HostFailure.isCloudflareChallenge(403, "challenge", null))
        assertTrue(HostFailure.isCloudflareChallenge(403, null, "<title>Just a moment...</title>"))
        assertFalse(HostFailure.isCloudflareChallenge(403, null, "Forbidden"))
        assertFalse(HostFailure.isCloudflareChallenge(503, "challenge", null))
    }

    private fun details(reply: XtreamClient.AccountReply = XtreamClient.AccountReply.USER_INFO, auth: Boolean = true,
                        status: String? = "Active", exp: Long? = null, max: Int = 1, active: Int = 0) =
        XtreamClient.XtAccountDetails(expiryMs = exp, maxConnections = max, activeConnections = active, status = status, authOk = auth, reply = reply)

    @Test
    fun `preflight replies`() {
        assertTrue(HostPreflight.interpret(details(), 120) is HostPreflight.Result.Ok)
        // A parked domain's HTML 200 is host-class for that candidate only.
        val invalid = HostPreflight.interpret(details(reply = XtreamClient.AccountReply.NOT_JSON), 50)
        assertEquals(HostPreflight.Result.Invalid, invalid)
        assertTrue(invalid.isHostClass())
        // These end the round: the next server shares the account.
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.AUTH), HostPreflight.interpret(details(auth = false), 1))
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.EMPTY), HostPreflight.interpret(details(reply = XtreamClient.AccountReply.EMPTY), 1))
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.EXPIRED), HostPreflight.interpret(details(status = "Expired"), 1))
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.EXPIRED), HostPreflight.interpret(details(status = "Banned"), 1))
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.EXPIRED), HostPreflight.interpret(details(exp = 10), 1, nowMs = 20))
        // Our own just-closed session still counted: wait, don't cycle.
        val busy = HostPreflight.interpret(details(max = 1, active = 1), 1)
        assertEquals(HostPreflight.Result.Busy(1, 1, 1), busy)
        assertFalse(busy.isHostClass())
    }

    @Test
    fun `a full-connections reply carries the elapsed ms`() {
        assertEquals(HostPreflight.Result.Busy(120, 2, 2), HostPreflight.interpret(details(max = 2, active = 2), 120))
    }

    @Test
    fun `preflight failures`() {
        assertTrue(HostPreflight.interpretFailure(ConnectException("x")).isHostClass())
        assertTrue(HostPreflight.interpretFailure(HttpClient.HttpStatusException(502, "x")).isHostClass())
        assertEquals(HostPreflight.Result.Blocked, HostPreflight.interpretFailure(HttpClient.HttpStatusException(403, "x", cloudflareChallenge = true)))
        assertFalse(HostPreflight.Result.Blocked.isHostClass())
        assertFalse(HostPreflight.interpretFailure(HttpClient.HttpStatusException(503, "x")).isHostClass())
        assertFalse(HostPreflight.interpretFailure(HttpClient.HttpStatusException(403, "x")).isHostClass())
        assertTrue(HostPreflight.interpretFailure(HttpClient.HttpStatusException(458, "x")) is HostPreflight.Result.Busy)
        assertEquals(HostPreflight.Result.AccountEnded(HostPreflight.Reason.AUTH), HostPreflight.interpretFailure(HttpClient.HttpStatusException(401, "x")))
    }
}
