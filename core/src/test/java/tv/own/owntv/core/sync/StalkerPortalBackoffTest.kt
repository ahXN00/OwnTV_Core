package tv.own.owntv.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.stalker.StalkerClient
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * How a Stalker portal's refusals are classified, and what the adaptive gate does with them.
 *
 * The case that matters is **403**. Ministra and its reseller panels answer 403 for "this MAC has
 * too many connections open", not for a refused login, so it has to be retried after a backoff and
 * has to shrink the concurrency budget. Before this, 403 became an auth failure: no retry, no
 * backoff, and a re-handshake — which is what produced the 403 storms users reported.
 */
class StalkerPortalBackoffTest {

    private fun http(code: Int) = StalkerClient.StalkerHttpException(code, "HTTP $code")

    // ---- retry classification ----

    @Test
    fun transient_includes403AlongsideThrottleAndOverload() {
        assertTrue("403 = too many connections, worth one more try", StalkerSyncer.isTransientPortalError(http(403)))
        assertTrue(StalkerSyncer.isTransientPortalError(http(429)))
        assertTrue(StalkerSyncer.isTransientPortalError(http(503)))
    }

    @Test
    fun transient_excludesAnswersThatWouldSayTheSameTwice() {
        assertFalse("a refused login is deterministic", StalkerSyncer.isTransientPortalError(StalkerClient.StalkerAuthException("MAC not authorized")))
        assertFalse(StalkerSyncer.isTransientPortalError(http(404)))
        assertFalse(StalkerSyncer.isTransientPortalError(http(400)))
    }

    @Test
    fun transient_stillCoversBrokenConnections() {
        assertTrue(StalkerSyncer.isTransientPortalError(java.net.SocketException("Connection reset")))
        assertTrue(StalkerSyncer.isTransientPortalError(SocketTimeoutException("timeout")))
        assertTrue(StalkerSyncer.isTransientPortalError(java.net.UnknownHostException("dns")))
        assertTrue("closed mid-response arrives wrapped", StalkerSyncer.isTransientPortalError(IOException("unexpected end of stream", EOFException())))
        assertFalse(StalkerSyncer.isTransientPortalError(IOException("Portal response has no \"js\" payload")))
    }

    // ---- throttle classification (shrinks the budget) ----

    @Test
    fun throttle_includes403() {
        assertTrue("403 means our concurrency is too high", StalkerSyncer.isPortalThrottle(http(403)))
        assertTrue(StalkerSyncer.isPortalThrottle(http(429)))
        assertTrue(StalkerSyncer.isPortalThrottle(http(500)))
        assertTrue(StalkerSyncer.isPortalThrottle(SocketTimeoutException("timeout")))
    }

    @Test
    fun throttle_excludesAuthAndPlainNotFound() {
        assertFalse(StalkerSyncer.isPortalThrottle(StalkerClient.StalkerAuthException("dead token")))
        assertFalse(StalkerSyncer.isPortalThrottle(http(404)))
    }

    // ---- what the gate then does ----

    @Test
    fun limiter_startsTimidAndNeverExceedsItsCeiling() {
        assertEquals(3, AdaptivePortalLimiter.DEFAULT_START)
        assertEquals(1, AdaptivePortalLimiter.DEFAULT_MIN)
        assertEquals(8, AdaptivePortalLimiter.DEFAULT_MAX)
    }

    @Test
    fun limiter_a403HalvesTheBudget() = runBlocking {
        val gate = AdaptivePortalLimiter(start = 8, isThrottle = StalkerSyncer::isPortalThrottle)
        runCatching { gate.withPermit { throw http(403) } }
        assertEquals("a 403 must back the crawl off, not be ignored", 4, gate.currentLimit)
        runCatching { gate.withPermit { throw http(403) } }
        assertEquals(2, gate.currentLimit)
    }

    @Test
    fun limiter_neverRunsMoreThanTheLimitAtOnce() = runBlocking {
        val gate = AdaptivePortalLimiter(start = 2, max = 2, isThrottle = { false })
        var inFlight = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        (1..6).map {
            async {
                gate.withPermit {
                    inFlight++
                    peak = maxOf(peak, inFlight)
                    if (inFlight == 2) release.complete(Unit)
                    release.await()
                    inFlight--
                }
            }
        }.awaitAll()
        assertEquals("the portal must never see more than the gate allows", 2, peak)
    }
}
