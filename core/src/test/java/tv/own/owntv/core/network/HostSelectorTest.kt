package tv.own.owntv.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import java.net.ConnectException
import java.util.Collections

/** AC7 / R5 / R6: one owner of the in-use server, one write per switch, bounded API retries. */
class HostSelectorTest {

    private val writes = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
    private val selector = HostSelector { id, url -> writes += id to url }
    private val s = SourceEntity(
        id = 4, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
        backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
    )
    private val pool = HostPool.pool(s)

    @Test
    fun `confirm writes a backup once`() = runBlocking {
        selector.attempt(s, "http://b.example.top")
        // Nothing is persisted until the new server actually plays.
        assertTrue(writes.isEmpty())
        selector.confirm(s, "http://b.example.top")
        selector.confirm(s, "http://b.example.top")
        assertEquals(listOf(4L to "http://b.example.top"), writes.toList())
        assertEquals("http://b.example.top", selector.current(s))
    }

    @Test
    fun `a failed round never writes and falls back to what was confirmed`() = runBlocking {
        selector.attempt(s, "http://c.example.top")
        selector.abandon(s)
        assertTrue(writes.isEmpty())
        assertEquals("http://a.example.top", selector.current(s))
    }

    @Test
    fun `a stored activeUrl is ignored until this process confirms a backup`() = runBlocking {
        val stored = s.copy(activeUrl = pool[1])
        assertEquals(pool.first(), selector.current(stored))
        selector.confirm(s, pool[1])
        assertEquals(pool[1], selector.current(stored))
    }

    @Test
    fun `a stale snapshot still requests through the in-use server`() = runBlocking {
        val stale = s.copy(activeUrl = null)
        selector.confirm(s, "http://c.example.top")
        assertEquals("http://c.example.top/live/u/p/1.ts", selector.forRequest("http://a.example.top/live/u/p/1.ts", stale))
        assertEquals("http://c.example.top", selector.activeBase(stale))
    }

    @Test
    fun `confirming the primary stores null, and only when it changes`() = runBlocking {
        val onC = s.copy(activeUrl = "http://c.example.top")
        selector.confirm(onC, "http://c.example.top")
        assertTrue(writes.isEmpty())
        selector.confirm(onC, "http://a.example.top")
        assertEquals(listOf(4L to null), writes.toList())
    }

    @Test
    fun `no pool, no switch`() = runBlocking {
        val m3u = s.copy(type = SourceType.M3U)
        assertNull(selector.current(m3u))
        assertEquals("http://a.example.top/x.m3u", selector.forRequest("http://a.example.top/x.m3u", m3u))
    }

    @Test
    fun `API calls try at most three servers from the in-use one, and only with failover on`() {
        val big = s.copy(backupUrls = HostPool.encode((1..10).map { "http://h$it.example.top" }))
        assertEquals(listOf("http://a.example.top", "http://h1.example.top", "http://h2.example.top"), selector.apiHosts(big))
        assertEquals(listOf("http://a.example.top"), selector.apiHosts(big.copy(failoverEnabled = false)))
    }

    @Test
    fun `tryHosts is sequential, moves on only after a host failure, and stops at the budget`() = runBlocking {
        var inFlight = 0
        var maxInFlight = 0
        val tried = mutableListOf<String>()
        val hosts = listOf("h1", "h2", "h3")
        val result = HostSelector.tryHosts(hosts, budgetMs = 60_000, now = { 0L }) { h ->
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            tried += h
            delay(5)
            inFlight--
            if (h != "h3") throw ConnectException("refused") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(hosts, tried)
        assertEquals(1, maxInFlight)

        // An answer that isn't a host failure (here a 401) is final: no second server.
        tried.clear()
        try {
            HostSelector.tryHosts(hosts, now = { 0L }) { h -> tried += h; throw HttpClient.HttpStatusException(401, "no") }
            fail()
        } catch (e: HttpClient.HttpStatusException) {
            assertEquals(401, e.code)
        }
        assertEquals(listOf("h1"), tried)

        // Past the 60 s budget no new server starts.
        tried.clear()
        var clock = 0L
        try {
            HostSelector.tryHosts(hosts, budgetMs = 60_000, now = { clock }) { h -> tried += h; clock += 61_000; throw ConnectException("x") }
            fail()
        } catch (_: ConnectException) {
        }
        assertEquals(listOf("h1"), tried)
    }
}
