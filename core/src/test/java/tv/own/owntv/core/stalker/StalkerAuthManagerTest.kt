package tv.own.owntv.core.stalker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * StalkerAuthManager unit tests — the §5.2 auth contract: sessions are cached per source,
 * `withAuthRetry` re-handshakes exactly ONCE on an auth failure, and a second failure propagates
 * to the caller instead of looping. A fake client counts handshakes so the tests can assert how
 * many round trips actually happened (no network).
 */
class StalkerAuthManagerTest {

    private val creds = StalkerCredentials(
        sourceId = 7L,
        portalUrl = "http://portal/c/",
        mac = "00:1A:79:AA:BB:CC",
    )

    /** Counts handshakes; each one hands out a new token ("t1", "t2", …). */
    private class FakeClient : StalkerClient(okhttp3.OkHttpClient()) {
        var handshakes = 0
        override suspend fun resolveHandshake(portalUrl: String, mac: String, userAgent: String?): Handshake {
            handshakes++
            return Handshake(apiBase = "http://portal/portal.php", token = "t$handshakes")
        }

        override suspend fun getProfile(
            apiBase: String, mac: String, token: String, userAgent: String?, identity: StalkerDeviceIdentity,
        ): Map<String, String> =
            mapOf("status" to "1")
    }

    // --- session caching ---

    @Test fun sessionFor_cachesUntilInvalidated() = runBlocking {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        val first = auth.sessionFor(creds)
        val second = auth.sessionFor(creds)
        assertSame("fresh session must be reused while valid", first, second)
        assertEquals(1, client.handshakes)

        auth.invalidate(creds.sourceId)
        val third = auth.sessionFor(creds)
        assertEquals("invalidate must force a re-handshake", 2, client.handshakes)
        assertEquals("t2", third.token)
    }

    @Test fun testConnection_alwaysHandshakesFresh() = runBlocking {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        auth.sessionFor(creds)
        auth.testConnection(creds)
        assertEquals("Test connection must not reuse the cache", 2, client.handshakes)
    }

    // --- withAuthRetry: the one-shot re-handshake ---

    @Test fun withAuthRetry_authFailureRetriesExactlyOnceWithFreshSession() = runBlocking {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        var calls = 0
        val tokensSeen = ArrayList<String>()
        val result = auth.withAuthRetry(creds) { session ->
            calls++
            tokensSeen += session.token
            // First call: the token "died" mid-session (portal answered {"js":false}).
            if (calls == 1) throw StalkerClient.StalkerAuthException("Portal returned an empty payload")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals("retry must run with a FRESH handshake, not the dead token", listOf("t1", "t2"), tokensSeen)
        assertEquals(2, client.handshakes)
    }

    @Test fun withAuthRetry_secondAuthFailurePropagates() {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        var calls = 0
        assertThrows(StalkerClient.StalkerAuthException::class.java) {
            runBlocking {
                auth.withAuthRetry(creds) { _ ->
                    calls++
                    throw StalkerClient.StalkerAuthException("MAC not authorized")
                }
            }
        }
        assertEquals("exactly one retry — a genuinely refused MAC must not loop", 2, calls)
    }

    @Test fun withAuthRetry_nonAuthErrorDoesNotRetry() {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        var calls = 0
        assertThrows(StalkerClient.StalkerHttpException::class.java) {
            runBlocking {
                auth.withAuthRetry(creds) { _ ->
                    calls++
                    throw StalkerClient.StalkerHttpException(503, "HTTP 503")
                }
            }
        }
        assertEquals("transient HTTP errors are the syncer's retryTransient job, not an auth retry", 1, calls)
        assertEquals(1, client.handshakes)
    }

    /**
     * The handshake storm. A sync has several requests in flight, so one expired token produces a
     * BURST of auth failures — and the later ones arrive after the first has already handshaken a
     * replacement. Invalidating unconditionally threw that fresh session away once per straggler and
     * handshaked another, hammering a portal that is usually already unhappy. One dead session must
     * cost exactly one handshake however many callers tripped over it.
     */
    @Test fun withAuthRetry_aBurstOfAuthFailuresHandshakesOnlyOnce() = runBlocking {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        val callers = 4
        val holding = AtomicInteger()
        val allHolding = CompletableDeferred<Unit>()
        val tokensSeenOnRetry = Collections.synchronizedList(ArrayList<String>())

        (1..callers).map {
            async {
                var firstAttempt = true
                auth.withAuthRetry(creds) { session ->
                    if (firstAttempt) {
                        firstAttempt = false
                        // Don't die until every caller is holding the same session — which is what a
                        // token expiring mid-sync actually looks like.
                        if (holding.incrementAndGet() == callers) allHolding.complete(Unit)
                        allHolding.await()
                        throw StalkerClient.StalkerAuthException("Portal returned an empty payload")
                    }
                    tokensSeenOnRetry += session.token
                }
            }
        }.awaitAll()

        assertEquals("one dead session must cost one handshake, not one per caller", 2, client.handshakes)
        assertEquals(List(callers) { "t2" }, tokensSeenOnRetry.toList())
    }

    // --- session lifetime: the portal's own watchdog_timeout ---

    @Test fun sessionTtl_defaultsWhenThePortalStatesNothingUsable() {
        val d = StalkerAuthManager.DEFAULT_SESSION_TTL_MS
        assertEquals(d, StalkerAuthManager.sessionTtlMs(emptyMap()))
        assertEquals(d, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "")))
        assertEquals(d, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "not a number")))
        assertEquals(d, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "0")))
        assertEquals(d, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "-120")))
    }

    @Test fun sessionTtl_followsThePortalAndStaysWithinSaneBounds() {
        assertEquals(120_000L, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "120")))
        assertEquals(120_000L, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to " 120 ")))
        // A panel that says "ten seconds" must not have us handshaking ten times a minute…
        assertEquals(StalkerAuthManager.MIN_SESSION_TTL_MS, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "10")))
        // …nor one that says "a day" have us trusting a token that long.
        assertEquals(StalkerAuthManager.MAX_SESSION_TTL_MS, StalkerAuthManager.sessionTtlMs(mapOf("watchdog_timeout" to "86400")))
    }
}
