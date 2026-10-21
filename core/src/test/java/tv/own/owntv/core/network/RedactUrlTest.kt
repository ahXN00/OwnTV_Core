package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What [HttpClient.redactUrl] masks — it runs on every URL bound for a log line or the error log. */
class RedactUrlTest {

    private fun redact(url: String) = HttpClient.redactUrl(url)

    @Test
    fun `query credentials are masked`() {
        assertEquals(
            "http://panel.invalid/get.php?username=***&password=***&type=m3u_plus",
            redact("http://panel.invalid/get.php?username=alice&password=s3cret&type=m3u_plus"),
        )
    }

    @Test
    fun `typed Xtream paths are masked`() {
        assertEquals("http://panel.invalid:8080/live/•••/•••/123.ts", redact("http://panel.invalid:8080/live/alice/s3cret/123.ts"))
        assertEquals("http://panel.invalid/movie/•••/•••/55.mkv", redact("http://panel.invalid/movie/alice/s3cret/55.mkv"))
        assertEquals(
            "http://panel.invalid/timeshift/•••/•••/60/2026-01-01:20-00/9.ts",
            redact("http://panel.invalid/timeshift/alice/s3cret/60/2026-01-01:20-00/9.ts"),
        )
    }

    @Test
    fun `the short get-php live form is masked`() {
        assertEquals("http://panel.invalid:8080/•••/•••/123", redact("http://panel.invalid:8080/alice/s3cret/123"))
        assertEquals("http://panel.invalid:8080/•••/•••/123.m3u8", redact("http://panel.invalid:8080/alice/s3cret/123.m3u8"))
        assertEquals("https://panel.invalid/•••/•••/123.ts?x=1", redact("https://panel.invalid/alice/s3cret/123.ts?x=1"))
    }

    @Test
    fun `a URL inside an error message is masked without eating the text after it`() {
        assertEquals(
            "Failed to open http://panel.invalid/•••/•••/123.ts: 403 Forbidden",
            redact("Failed to open http://panel.invalid/alice/s3cret/123.ts: 403 Forbidden"),
        )
    }

    @Test
    fun `a Stalker MAC in the query is masked`() {
        val out = redact("http://portal.invalid/play/live.php?mac=00:1A:79:AA:BB:CC&stream=1&play_token=abc")
        assertFalse(out, out.contains("00:1A:79"))
        assertEquals("http://portal.invalid/play/live.php?mac=***&stream=1&play_token=***", out)
    }

    @Test
    fun `userinfo credentials are masked`() {
        assertEquals("http://***@proxy.invalid:3128/", redact("http://bob:hunter2@proxy.invalid:3128/"))
    }

    @Test
    fun `URLs without credentials are left alone`() {
        listOf(
            "http://cdn.invalid/hls/index.m3u8",
            "http://cdn.invalid/channel/123.m3u8",
            "http://cdn.invalid/a/b/c/123.ts",
        ).forEach { assertEquals(it, redact(it)) }
    }
}
