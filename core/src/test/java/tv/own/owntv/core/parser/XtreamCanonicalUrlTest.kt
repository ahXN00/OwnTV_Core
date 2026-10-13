package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.network.HttpClient

/**
 * AC10 / A4: what sync stores (stream URLs, so content hashes) and the guide's identity stay on the
 * primary after a switch; only requests made now go to the in-use server.
 */
class XtreamCanonicalUrlTest {

    private val s = SourceEntity(
        id = 9, name = "X", type = SourceType.XTREAM, url = "http://a.example.top/", username = "u", password = "p",
        backupUrls = HostPool.encode(listOf("http://b.example.top:8080")),
    )
    private val selector = HostSelector { _, _ -> }
    private val client = XtreamClient(HttpClient(OkHttpClient()), selector)

    private fun stored() = listOf(
        client.liveUrl(s, "1"),
        client.movieUrl(s, "2", "mkv"),
        client.seriesEpisodeUrl(s, "3", null),
        client.xmltvUrl(s),
    )

    @Test
    fun `stored urls and the guide url do not change when a backup is in use`() = runBlocking {
        val before = stored()
        selector.confirm(s, "http://b.example.top:8080")
        assertEquals(before, stored())
        assertEquals("http://a.example.top/live/u/p/1.ts", before[0])
    }

    @Test
    fun `requests made now go to the in-use server`() = runBlocking {
        selector.confirm(s, "http://b.example.top:8080")
        assertEquals("http://b.example.top:8080/live/u/p/1.ts", client.requestUrl(s, client.liveUrl(s, "1")))
        assertEquals(
            "http://b.example.top:8080/timeshift/u/p/60/1970-01-01:00-00/1.ts",
            client.timeshiftUrl(s, "1", 0L, 60),
        )
    }

    @Test
    fun `without a selector every url stays on the primary`() {
        val plain = XtreamClient(HttpClient(OkHttpClient()))
        assertEquals("http://a.example.top/live/u/p/1.ts", plain.requestUrl(s, plain.liveUrl(s, "1")))
        assertEquals("http://a.example.top/timeshift/u/p/60/1970-01-01:00-00/1.ts", plain.timeshiftUrl(s, "1", 0L, 60))
    }
}
