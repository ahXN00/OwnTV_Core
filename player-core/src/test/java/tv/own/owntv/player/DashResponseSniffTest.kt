package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LivePreviewEngine.isDashResponse] — recognising a DASH manifest from the response the request
 * actually ended at, rather than from the URL that was submitted.
 *
 * This is the rung that closes the reported bug without needing a re-sync, and the only one available
 * to Stalker and Xtream, whose URLs can never carry a `manifest_type` declaration.
 */
class DashResponseSniffTest {

    /** The reported channel, verbatim from the diagnostics: submitted extensionless, redirected to a
     *  signed `.mpd`. The token arrives as a query parameter *after* the extension, which is why the
     *  path is tested before the query rather than the whole string. */
    @Test
    fun `the reported redirect target is recognised`() {
        assertTrue(
            LivePreviewEngine.isDashResponse(
                "https://jiotv.example.de/render.mpd?auth=SECRET&channel_id=173&q=auto",
                null,
            ),
        )
    }

    @Test
    fun `the dash content types are recognised`() {
        assertTrue(LivePreviewEngine.isDashResponse("https://host/stream", "application/dash+xml"))
        assertTrue(LivePreviewEngine.isDashResponse("https://host/stream", "video/vnd.mpeg.dash.mpd"))
    }

    /** Servers routinely append a charset; the parameter must not defeat the match. */
    @Test
    fun `a content type with parameters still matches`() {
        assertTrue(LivePreviewEngine.isDashResponse("https://host/s", "application/dash+xml; charset=utf-8"))
        assertTrue(LivePreviewEngine.isDashResponse("https://host/s", "APPLICATION/DASH+XML"))
    }

    // --- and must not fire on anything else ---

    /** The overwhelming majority of live: raw MPEG-TS. Firing here would send a working channel to a
     *  media source that cannot open it. */
    @Test
    fun `a raw ts stream is not dash`() {
        assertFalse(LivePreviewEngine.isDashResponse("https://host/live/u/p/1.ts", "video/mp2t"))
    }

    @Test
    fun `an hls manifest is not dash`() {
        assertFalse(LivePreviewEngine.isDashResponse("https://host/live/u/p/1.m3u8", "application/x-mpegURL"))
    }

    /** The extensionless URL as SUBMITTED says nothing — only the response it ends at does. That is
     *  exactly why the sniff reads the final URL and not the requested one. */
    @Test
    fun `the submitted extensionless url alone is not enough`() {
        assertFalse(LivePreviewEngine.isDashResponse("https://jiotv.example.de/live/mpd/173", null))
    }

    /** `.mpd` must be the path's extension, not merely somewhere in the string — `/live/mpd/173` and a
     *  query parameter both contain the letters. */
    @Test
    fun `mpd elsewhere in the url does not match`() {
        assertFalse(LivePreviewEngine.isDashResponse("https://host/mpd/173", null))
        assertFalse(LivePreviewEngine.isDashResponse("https://host/live/1.ts?type=mpd", null))
    }

    /** The two sniffs must never both claim the same response, or the ladder's rungs would fight. */
    @Test
    fun `the hls and dash sniffs are mutually exclusive on real responses`() {
        listOf(
            "https://host/render.mpd?auth=x" to "application/dash+xml",
            "https://host/play.m3u8" to "application/x-mpegurl",
            "https://host/live/1.ts" to "video/mp2t",
        ).forEach { (url, type) ->
            assertFalse(
                "both sniffs matched $url",
                LivePreviewEngine.isDashResponse(url, type) && LivePreviewEngine.isHlsResponse(url, type),
            )
        }
    }
}
