package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LivePreviewEngine.shouldTryDirectSource] — the very last rung of the live ladder.
 *
 * The Xtream `direct_source` field is one every major IPTV client deliberately ignores, and for a good
 * reason: panels build it from the streaming server's configured domain and fall back to its raw IP,
 * so a misconfigured panel or load balancer publishes an address reachable only inside their own
 * network. Preferring it would break channels that work today.
 *
 * So the whole safety of this feature lives in *when* it fires, not in what it does. It is never
 * tuned first, never replaces the stored stream URL, and is reached only once every other rung is
 * spent — the point where the alternative is an error screen. These tests pin that contract; if one of
 * them starts failing, the field has been promoted out of last place and channels will regress.
 */
class DirectSourceRungTest {

    private val normalUrl = "http://panel.example/live/u/p/1.ts"
    private val direct = "http://cdn.example:8080/live/u/p/1.ts"

    @Test
    fun `a usable address is tried once everything else is spent`() {
        assertTrue(
            LivePreviewEngine.shouldTryDirectSource(direct, normalUrl, alreadyTried = false, httpStatus = null),
        )
    }

    /** One-shot, so the ladder always terminates — the same guarantee every other rung carries. */
    @Test
    fun `it is never tried twice in one tune`() {
        assertFalse(
            LivePreviewEngine.shouldTryDirectSource(direct, normalUrl, alreadyTried = true, httpStatus = null),
        )
    }

    /** The overwhelming majority of panels send `""`, which the reader stores as null. */
    @Test
    fun `no address means no rung`() {
        assertFalse(LivePreviewEngine.shouldTryDirectSource(null, normalUrl, false, null))
        assertFalse(LivePreviewEngine.shouldTryDirectSource("", normalUrl, false, null))
        assertFalse(LivePreviewEngine.shouldTryDirectSource("   ", normalUrl, false, null))
    }

    /** A panel that publishes honestly gives back the URL we already built — retrying it is pure delay. */
    @Test
    fun `an address identical to the one that just failed is not retried`() {
        assertFalse(LivePreviewEngine.shouldTryDirectSource(normalUrl, normalUrl, false, null))
    }

    /** Some panels put a bare host, a path, or junk in the field. Only an absolute http(s) URL is
     *  something this player can open at all. */
    @Test
    fun `a non-http address is refused`() {
        assertFalse(LivePreviewEngine.shouldTryDirectSource("rtmp://host/live/1", normalUrl, false, null))
        assertFalse(LivePreviewEngine.shouldTryDirectSource("cdn.example/live/1.ts", normalUrl, false, null))
        assertFalse(LivePreviewEngine.shouldTryDirectSource("/live/u/p/1.ts", normalUrl, false, null))
    }

    /**
     * The trap `isFormatFailure` already documents, one rung further down: a panel answering 429
     * "Channel limit has been reached" is refusing the *request*, not the address. A different URL
     * cannot answer it, and chasing one burns seconds before showing the same error — while the
     * original URL works the moment the account's other stream closes.
     */
    @Test
    fun `a request refusal is not answered by a different address`() {
        listOf(429, 458).forEach { status ->
            assertFalse(
                "status $status should not trigger the direct_source rung",
                LivePreviewEngine.shouldTryDirectSource(direct, normalUrl, false, status),
            )
        }
    }

    /** A genuinely dead endpoint is exactly what a second address can answer. */
    @Test
    fun `a not-found or server error still reaches the rung`() {
        listOf(404, 500, 502).forEach { status ->
            assertTrue(
                "status $status should reach the direct_source rung",
                LivePreviewEngine.shouldTryDirectSource(direct, normalUrl, false, status),
            )
        }
    }

    /** A failure with no HTTP status at all (a parse or decoder failure) is not a refusal. */
    @Test
    fun `an error carrying no http status still reaches the rung`() {
        assertTrue(LivePreviewEngine.shouldTryDirectSource(direct, normalUrl, false, null))
    }

    @Test
    fun `an https address is accepted as readily as http`() {
        assertTrue(LivePreviewEngine.shouldTryDirectSource("https://cdn.example/1.ts", normalUrl, false, null))
    }
}
