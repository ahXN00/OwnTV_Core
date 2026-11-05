package tv.own.owntv.core.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.CatchupUrl
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient

/**
 * Xtream stream URLs carry the credentials as path segments. A password the API login accepts must
 * give a URL that reaches the same account, and a plain one must give exactly the URL it always did.
 */
class XtreamStreamUrlTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun source(user: String, pass: String) =
        SourceEntity(name = "t", type = SourceType.XTREAM, url = "http://panel.invalid:8080/", username = user, password = pass)

    @Test
    fun `plain credentials keep the URL they always had`() {
        val s = source("alice", "s3cret")
        assertEquals("http://panel.invalid:8080/live/alice/s3cret/7.ts", client.liveUrl(s, "7"))
        assertEquals("http://panel.invalid:8080/movie/alice/s3cret/7.mkv", client.movieUrl(s, "7", "mkv"))
        assertEquals("http://panel.invalid:8080/series/alice/s3cret/7.mp4", client.seriesEpisodeUrl(s, "7", null))
    }

    @Test
    fun `characters that would cut the URL short are encoded`() {
        val s = source("alice", "p#ss/w?rd%1")
        assertEquals("http://panel.invalid:8080/live/alice/p%23ss%2Fw%3Frd%251/7.ts", client.liveUrl(s, "7"))
    }

    @Test
    fun `spaces and non-ASCII are encoded, path-legal punctuation is not`() {
        assertEquals("a%20b", xtreamPathSegment("a b"))
        assertEquals("%C3%A9t%C3%A9", xtreamPathSegment("été"))
        assertEquals("-._~!$&'()*+,;=:@", xtreamPathSegment("-._~!$&'()*+,;=:@"))
        assertEquals("", xtreamPathSegment(null))
    }

    @Test
    fun `the timeshift PHP form carries the decoded credentials as query values`() {
        val s = source("alice", "a+b&c#d")
        val path = client.timeshiftUrl(s, "9", startMs = 0L, durationMinutes = 60)
        assertEquals("http://panel.invalid:8080/timeshift/alice/a+b&c%23d/60/1970-01-01:00-00/9.ts", path)
        assertEquals(
            "http://panel.invalid:8080/streaming/timeshift.php?username=alice&password=a%2Bb%26c%23d&stream=9&start=1970-01-01:00-00&duration=60",
            CatchupUrl.timeshiftPhpAlternate(path),
        )
    }
}
