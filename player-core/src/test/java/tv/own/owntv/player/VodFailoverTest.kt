package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.parser.XtreamClient

/** AC5 (VOD): the gate runs before any request, unknown errors are checked once, and a refusal is final. */
class VodFailoverTest {

    private val order = mutableListOf<String>()
    private val writes = mutableListOf<String?>()
    private val selector = HostSelector { _, url -> writes += url }
    private val source = SourceEntity(
        id = 2, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
        backupUrls = HostPool.encode(listOf("http://b.example.top")),
    )
    private val answers = mutableMapOf<String, HostPreflight.Result>()
    private var held = 0
    private val vod = VodFailover(
        hosts = selector,
        gate = { order += "gate"; ReleaseGate.Result.Released(false, true) },
        preflight = { _, h -> order += "preflight:$h"; answers[h] ?: HostPreflight.Result.Ok(5, XtreamClient.XtAccountDetails()) },
        pause = {},
        reserve = { _ -> held++; { held-- } },
    )

    @Test
    fun `a dead server reopens the film on the next one, persisted only once it plays`() = runBlocking {
        val out = vod.onFailure(source, "[ffmpeg] tcp: Connection to tcp://a:80 failed: Connection refused") { true }
        assertEquals(VodFailover.Outcome.Reopen("http://b.example.top", 2, 2), out)
        assertEquals(1, held)
        assertEquals(listOf("gate", "preflight:http://b.example.top"), order)
        assertTrue(writes.isEmpty())
        assertEquals("http://b.example.top", vod.played(source))
        assertEquals(0, held)
        assertEquals(listOf<String?>("http://b.example.top"), writes)
    }

    @Test
    fun `P4 abandonPending after a reopen drops the unconfirmed server`() = runBlocking {
        val out = vod.onFailure(source, "[ffmpeg] tcp: Connection to tcp://a:80 failed: Connection refused") { true }
        assertEquals(VodFailover.Outcome.Reopen("http://b.example.top", 2, 2), out)
        assertEquals("http://b.example.top", selector.current(source))
        assertTrue(writes.isEmpty())
        vod.abandonPending()
        assertEquals("http://a.example.top", selector.current(source))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `an unclassified mpv failure checks the failed server first and leaves a live one alone`() = runBlocking {
        val out = vod.onFailure(source, "[cplayer] loading failed") { true }
        assertEquals(VodFailover.Outcome.NotServer, out)
        assertEquals(0, held)
        assertEquals(listOf("gate", "preflight:http://a.example.top"), order)
    }

    @Test
    fun `a never-class error does nothing at all`() = runBlocking {
        assertEquals(VodFailover.Outcome.NotServer, vod.onFailure(source, "[ffmpeg] http: HTTP error 503 Service Unavailable") { true })
        assertTrue(order.isEmpty())
    }

    @Test
    fun `every server dead ends the round`() = runBlocking {
        answers["http://b.example.top"] = HostPreflight.Result.Down(null)
        assertEquals(VodFailover.Outcome.Exhausted(2), vod.onFailure(source, "Failed to resolve hostname a") { true })
        // No automatic retry: the next failure of the same item does nothing.
        order.clear()
        assertEquals(VodFailover.Outcome.NotServer, vod.onFailure(source, "Failed to resolve hostname a") { true })
        assertTrue(order.isEmpty())
    }

    @Test
    fun `offline mid-round pauses without using a host`() = runBlocking {
        var online = false
        val back = CompletableDeferred<Unit>()
        var offlineCalls = 0
        val seen = mutableListOf<String>()
        val pool = SourceEntity(
            id = 2, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
        )
        val paused = VodFailover(
            hosts = selector,
            gate = { seen += "gate"; ReleaseGate.Result.Released(false, true) },
            preflight = { _, h -> seen += "preflight:$h"; HostPreflight.Result.Ok(5, XtreamClient.XtAccountDetails()) },
            pause = {},
            isOnline = { online },
            awaitOnline = { back.await(); online = true },
            onOffline = { offlineCalls++ },
        )
        val job = async {
            paused.onFailure(pool, "[ffmpeg] tcp: Connection to tcp://a:80 failed: Connection refused") { true }
        }
        withTimeout(2_000) { while (offlineCalls == 0) yield() }
        assertEquals(1, offlineCalls)
        assertEquals(listOf("gate"), seen)
        back.complete(Unit)
        assertEquals(VodFailover.Outcome.Reopen("http://a.example.top", 1, 3), withTimeout(2_000) { job.await() })
        assertEquals(listOf("gate", "preflight:http://a.example.top"), seen)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `LocalPath preflight never marks a host down`() = runBlocking {
        val pool = SourceEntity(
            id = 2, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top")),
        )
        val local = VodFailover(
            hosts = selector,
            gate = { ReleaseGate.Result.Released(false, true) },
            preflight = { _, _ -> HostPreflight.Result.LocalPath(HostPreflight.Result.LocalPath.Kind.PROXY) },
            pause = {},
        )
        val out = local.onFailure(pool, "[ffmpeg] tcp: Connection to tcp://a:80 failed: Connection refused") { true }
        assertEquals(VodFailover.Outcome.Refused("the proxy failed"), out)
        assertEquals("http://a.example.top", selector.current(pool))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `F4 a Down while the network drops is retried on the same host`() = runBlocking {
        var online = true
        val back = CompletableDeferred<Unit>()
        var offlineCalls = 0
        val seen = mutableListOf<String>()
        var bChecks = 0
        val pool = SourceEntity(
            id = 2, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
        )
        val retried = VodFailover(
            hosts = selector,
            gate = { seen += "gate"; ReleaseGate.Result.Released(false, true) },
            preflight = { _, h ->
                seen += "preflight:$h"
                if (h == "http://b.example.top" && bChecks++ == 0) {
                    online = false
                    HostPreflight.Result.Down(null)
                } else {
                    HostPreflight.Result.Ok(5, XtreamClient.XtAccountDetails())
                }
            },
            pause = {},
            isOnline = { online },
            awaitOnline = { back.await(); online = true },
            onOffline = { offlineCalls++ },
        )
        val job = async {
            retried.onFailure(pool, "[ffmpeg] tcp: Connection to tcp://a:80 failed: Connection refused") { true }
        }
        withTimeout(2_000) { while (offlineCalls == 0) yield() }
        back.complete(Unit)
        assertEquals(VodFailover.Outcome.Reopen("http://b.example.top", 2, 3), withTimeout(2_000) { job.await() })
        assertEquals(listOf("gate", "preflight:http://b.example.top", "preflight:http://b.example.top"), seen)
    }
}
