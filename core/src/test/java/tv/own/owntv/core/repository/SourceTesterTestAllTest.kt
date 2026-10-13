package tv.own.owntv.core.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient

/** AC12/AC13: Test all is strictly sequential, Skip keeps partial results, and only OK rows are choosable. */
class SourceTesterTestAllTest {

    private val pool = (1..5).map { "http://h$it.example.top" }

    @Test
    fun `one request in flight, list order, every status kept`() = runBlocking {
        var inFlight = 0
        var maxInFlight = 0
        val progress = mutableListOf<Int>()
        val statuses = listOf(
            HostTestStatus.OK to 300L, HostTestStatus.UNREACHABLE to null, HostTestStatus.INVALID_RESPONSE to null,
            HostTestStatus.BLOCKED to null, HostTestStatus.OK to 140L,
        )
        val rows = SourceTester.runSequential(pool, skip = { false }, onProgress = { k, _, _ -> progress += k }) { host ->
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            delay(5)
            inFlight--
            statuses[pool.indexOf(host)]
        }
        assertEquals(1, maxInFlight)
        assertEquals(listOf(1, 2, 3, 4, 5), progress)
        assertEquals(statuses.map { it.first }, rows.map { it.status })
        assertEquals(pool, rows.map { it.host })
        assertEquals("http://h5.example.top", SourceTester.fastest(rows)?.host)
    }

    @Test
    fun `Skip stops before the next server and keeps what was tested`() = runBlocking {
        var tested = 0
        val rows = SourceTester.runSequential(pool, skip = { tested >= 2 }, onProgress = { _, _, _ -> }) {
            tested++
            HostTestStatus.OK to 100L
        }
        assertEquals(2, tested)
        assertEquals(
            listOf(HostTestStatus.OK, HostTestStatus.OK, HostTestStatus.NOT_TESTED, HostTestStatus.NOT_TESTED, HostTestStatus.NOT_TESTED),
            rows.map { it.status },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.position })
    }

    @Test
    fun `Invalid response and Blocked can never be chosen`() {
        assertFalse(HostTestStatus.INVALID_RESPONSE.choosable)
        assertFalse(HostTestStatus.BLOCKED.choosable)
        val rows = listOf(
            HostTestRow("a", 1, HostTestStatus.INVALID_RESPONSE),
            HostTestRow("b", 2, HostTestStatus.BLOCKED),
            HostTestRow("c", 3, HostTestStatus.UNREACHABLE),
        )
        assertNull(SourceTester.fastest(rows))
    }

    @Test
    fun `test all returns nothing while a measurement holds the probe`() = runBlocking {
        val selector = HostSelector { _, _ -> }
        val http = HttpClient(OkHttpClient())
        val tester = SourceTester(
            http,
            XtreamClient(http),
            StalkerAuthManager(StalkerClient(OkHttpClient())),
            selector,
        )
        val source = SourceEntity(
            id = 1, name = "X", type = SourceType.XTREAM, url = "http://a.example.top",
            username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top")),
        )
        val holder = async {
            selector.probeLock.lock()
            try {
                delay(30_000)
            } finally {
                selector.probeLock.unlock()
            }
        }
        while (!selector.probeLock.isLocked) yield()
        try {
            assertNull(tester.testAll(source))
        } finally {
            holder.cancel()
        }
    }

    @Test
    fun `choosing the fastest writes only the in-use server`() = runBlocking {
        val writes = mutableListOf<Pair<Long, String?>>()
        val selector = HostSelector { id, active -> writes += id to active }
        val source = SourceEntity(
            id = 4, name = "X", type = SourceType.XTREAM, url = "http://a.example.top:8080",
            username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
        )
        val rows = listOf(
            HostTestRow("http://a.example.top:8080", 1, HostTestStatus.OK, 400),
            HostTestRow("http://b.example.top", 2, HostTestStatus.OK, 120),
            HostTestRow("http://c.example.top", 3, HostTestStatus.UNREACHABLE),
        )
        val urlBefore = source.url
        val backupsBefore = source.backupUrls
        val poolBefore = HostPool.pool(source)
        selector.use(source, SourceTester.fastest(rows)!!.host)
        assertEquals("http://b.example.top", selector.current(source))
        assertEquals(urlBefore, source.url)
        assertEquals(backupsBefore, source.backupUrls)
        assertEquals(poolBefore, HostPool.pool(source))
        assertEquals(listOf(4L to "http://b.example.top"), writes)
    }

    @Test
    fun `gap d offline marks every row Network problem without requests`() = runBlocking {
        var requests = 0
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            chain.proceed(chain.request())
        }.build()
        val http = HttpClient(ok)
        val tester = SourceTester(
            http,
            XtreamClient(http),
            StalkerAuthManager(StalkerClient(ok)),
            HostSelector { _, _ -> },
            isOnline = { false },
        )
        val source = SourceEntity(
            id = 1, name = "X", type = SourceType.XTREAM, url = "http://a.example.top",
            username = "u", password = "p",
            backupUrls = HostPool.encode(listOf("http://b.example.top")),
        )
        val rows = tester.testAll(source)!!
        assertEquals(List(2) { HostTestStatus.NETWORK_PROBLEM }, rows.map { it.status })
        assertEquals(0, requests)
    }
}
