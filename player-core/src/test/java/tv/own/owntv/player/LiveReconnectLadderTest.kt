package tv.own.owntv.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 — the two decisions that keep a live channel alive through a provider hiccup:
 * how long we wait between reconnects (X3), and whether an early END_FILE means "corrupt file"
 * or "live stream being live" (P1).
 */
class LiveReconnectLadderTest {

    @Test
    fun `the ladder widens instead of flat-lining at four seconds`() {
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(1))
        assertEquals(3_000L, LivePreviewEngine.reconnectDelayMs(2))
        assertEquals(6_000L, LivePreviewEngine.reconnectDelayMs(3))
        assertEquals(10_000L, LivePreviewEngine.reconnectDelayMs(4))
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(5))
    }

    @Test
    fun `the engine's death verdict is its slowest watchdog plus the first reconnect`() {
        // Live TV's handoff deadline is derived from this, so the two can no longer drift apart the way
        // they had: a flat 30s handoff against a 12s verdict meant sitting through two of the engine's
        // own reconnect attempts before the channel was ever offered to the other player.
        // 12s buffering stall (the slowest of the three checks) + 1.5s before the first retry.
        assertEquals(13_500L, LivePreviewEngine.DEATH_VERDICT_MS)
        assertEquals(12_000L + LivePreviewEngine.reconnectDelayMs(1), LivePreviewEngine.DEATH_VERDICT_MS)
    }

    @Test
    fun `attempts past the ladder hold at the last step`() {
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(6))
        assertEquals(15_000L, LivePreviewEngine.reconnectDelayMs(8))
    }

    @Test
    fun `a defensive out-of-range attempt still yields the first step`() {
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(0))
        assertEquals(1_500L, LivePreviewEngine.reconnectDelayMs(-3))
    }

    @Test
    fun `the ladder outlives a minute-long outage`() {
        // The pre-fix rule (1500 * n capped at 4 s) spent its eight attempts in ~26 s, so a router
        // reboot always ended in "Lost connection". Five attempts alone now span ~35 s.
        val fiveAttempts = (1..5).sumOf { LivePreviewEngine.reconnectDelayMs(it) }
        assertEquals(35_500L, fiveAttempts)
        assertTrue((1..8).sumOf { LivePreviewEngine.reconnectDelayMs(it) } > 60_000L)
    }

    @Test
    fun `recovery is only credited after a sustained healthy window`() {
        assertEquals(60_000L, LivePreviewEngine.HEALTHY_MS)
    }

    @Test
    fun `fatal HLS HTTP recovery stays short instead of widening to fifteen seconds`() {
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(1))
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(5))
        assertEquals(1_500L, LivePreviewEngine.hlsHttpReconnectDelayMs(8))
    }

    @Test
    fun `redirected playlist is recognized from final URL or response content type`() {
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7.m3u8?token=x", "application/octet-stream"))
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7", "application/x-mpegURL; charset=UTF-8"))
        assertTrue(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7", "application/vnd.apple.mpegurl"))
        assertFalse(LivePreviewEngine.isHlsResponse("http://cdn.test/live/7.ts", "video/MP2T"))
    }

    @Test
    fun `a played DASH channel rides out an outage on segments and the MPD, like raw TS`() {
        assertTrue(LivePreviewEngine.retriesOutageForever(StreamRoute.DASH, C.DATA_TYPE_MEDIA))
        assertTrue(LivePreviewEngine.retriesOutageForever(StreamRoute.DASH, C.DATA_TYPE_MANIFEST))
        assertTrue(LivePreviewEngine.retriesOutageForever(StreamRoute.PROGRESSIVE, C.DATA_TYPE_MEDIA))
        assertTrue(LivePreviewEngine.retriesOutageForever(StreamRoute.PROGRESSIVE, C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
        // Licence and init loads keep the stock count; HLS has its own policy.
        assertFalse(LivePreviewEngine.retriesOutageForever(StreamRoute.DASH, C.DATA_TYPE_DRM))
        assertFalse(LivePreviewEngine.retriesOutageForever(StreamRoute.PROGRESSIVE, C.DATA_TYPE_MANIFEST))
        assertFalse(LivePreviewEngine.retriesOutageForever(StreamRoute.HLS, C.DATA_TYPE_MEDIA))
    }

    @Test
    fun `a refused segment is retried once, not hammered until fatal`() {
        assertEquals(LivePreviewEngine.EDGE_REFUSAL_RETRY_MS, LivePreviewEngine.edgeRefusalRetryDelayMs(1))
        assertEquals(C.TIME_UNSET, LivePreviewEngine.edgeRefusalRetryDelayMs(2))
        assertEquals(C.TIME_UNSET, LivePreviewEngine.edgeRefusalRetryDelayMs(5))
    }

    @Test
    fun `only raw live MPEG-TS gets reconnect_at_eof — everything else keeps the plain reconnect set`() {
        // Live HLS keeps the shipped reconnect options: dropping them was an unproven experiment and one
        // provider's mpv playback stopped working under it.
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.m3u8?token=x", live = true, hls = true),
        )
        assertEquals(
            "${OwnTVPlayer.STREAM_RECONNECT_OPTIONS},${OwnTVPlayer.LIVE_TS_RECONNECT_OPTIONS}",
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = false),
        )
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/movie/7.m3u8", live = false, hls = false),
        )
    }

    @Test
    fun `live raw TS retries 458 in FFmpeg, and gives up inside the open timeout`() {
        val opts = OwnTVPlayer.LIVE_TS_RECONNECT_OPTIONS
        // `[…]` keeps FFmpeg's comma list as one mpv value; unquoted, `458` would become a key of its own.
        assertTrue(opts.contains("reconnect_on_http_error=[5xx,458]"))
        // FFmpeg sleeps 0, 1, 3, 7… s between tries (delay = 1 + 2 × previous).
        val retries = Regex("reconnect_max_retries=(\\d+)").find(opts)!!.groupValues[1].toInt()
        val sleptMs = generateSequence(0) { 1 + 2 * it }.take(retries).sum() * 1000L
        assertTrue("$sleptMs ms", sleptMs < OwnTVPlayer.LIVE_OPEN_TIMEOUT_MS)
    }

    @Test
    fun `a redirecting ts URL is treated as HLS by mpv, not as a raw stream`() {
        // The permanent-black-screen case: mpv reconnected to the same 1.8 KB manifest forever because
        // the URL said `.ts`. Nothing about the URL changes — only what we learned about the panel.
        assertEquals(
            "${OwnTVPlayer.STREAM_RECONNECT_OPTIONS},${OwnTVPlayer.LIVE_TS_RECONNECT_OPTIONS}",
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = false),
        )
        // Learned to be HLS → the manifest's EOF is legitimate, so no reconnect_at_eof.
        assertEquals(
            OwnTVPlayer.STREAM_RECONNECT_OPTIONS,
            OwnTVPlayer.streamLavfOptionsFor("http://panel/live/7.ts", live = true, hls = true),
        )
    }

    @Test
    fun `a numeric Retry-After becomes the wait, anything else becomes no wait at all`() {
        // The panel counts the seconds down across successive refusals ("13", "12", "10"), and each one
        // replaces the pending countdown — see LivePreviewEngine.maybeBackOffForProvider.
        assertEquals(13, LivePreviewEngine.retryAfterSecs("13"))
        assertEquals(10, LivePreviewEngine.retryAfterSecs(" 10 "))
        // "come back now" still costs one tick, so the countdown always has something to show.
        assertEquals(1, LivePreviewEngine.retryAfterSecs("0"))
        // Longer than the ceiling reads as a hang behind a spinner — wait the ceiling, then let the user decide.
        assertEquals(LivePreviewEngine.MAX_RETRY_AFTER_SECS, LivePreviewEngine.retryAfterSecs("3600"))
        // The HTTP-date form and junk name no deadline we can honestly count down, so there is no wait.
        assertNull(LivePreviewEngine.retryAfterSecs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(LivePreviewEngine.retryAfterSecs("-5"))
        assertNull(LivePreviewEngine.retryAfterSecs(""))
        assertNull(LivePreviewEngine.retryAfterSecs(null))
    }

    @Test
    fun `an HTTP refusal stops the identical-request retries but leaves the fallbacks armed`() {
        // A panel that refuses outright cannot be talked round by the same request, so only one repeat
        // is allowed — enough for the format/User-Agent fallbacks, which need autoRetries >= 1, to
        // still get their turn.
        assertTrue(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 403 Forbidden"))
        assertTrue(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 404 Not Found"))
        assertFalse(OwnTVPlayer.isHardHttpRefusal("ffmpeg: http: HTTP error 502 Bad Gateway"))
        assertFalse(OwnTVPlayer.isHardHttpRefusal("stream: Failed to open http://panel/live/7.ts."))
        assertFalse(OwnTVPlayer.isHardHttpRefusal(null))
        assertEquals(1, OwnTVPlayer.HARD_REFUSAL_MAX_RETRIES)
    }

    @Test
    fun `busy is not refusal — 458 429 408 keep their retries`() {
        // F29: "the account's one session is already in use" (the non-standard 458 Xtream panels use),
        // a rate limit and a request timeout all mean the stream is fine and we should ask again after
        // a back-off — which is what the ExoPlayer side already does via LiveStreamQuirks.isSessionLimit.
        // Treating them as hard refusals made mpv give up on panels ExoPlayer reconnects to.
        assertEquals(OwnTVPlayer.HttpRefusal.BUSY, OwnTVPlayer.httpRefusalKind("ffmpeg: http: HTTP error 458 <none>"))
        assertEquals(OwnTVPlayer.HttpRefusal.BUSY, OwnTVPlayer.httpRefusalKind("ffmpeg: http: HTTP error 429 Too Many Requests"))
        assertEquals(OwnTVPlayer.HttpRefusal.BUSY, OwnTVPlayer.httpRefusalKind("ffmpeg: http: HTTP error 408 Request Timeout"))
        assertEquals(OwnTVPlayer.HttpRefusal.HARD, OwnTVPlayer.httpRefusalKind("ffmpeg: http: HTTP error 401 Unauthorized"))
        assertEquals(OwnTVPlayer.HttpRefusal.NONE, OwnTVPlayer.httpRefusalKind("ffmpeg: http: HTTP error 503 Unavailable"))
        assertEquals(OwnTVPlayer.HttpRefusal.NONE, OwnTVPlayer.httpRefusalKind(null))
    }

    @Test
    fun `mpv always keeps FFmpeg's default live start`() {
        // Regression guard: pinning live_start_index back (-5) to dodge the traced panel's 403s only
        // asked for staler signed segment URLs. Live HLS must carry no demuxer option at all.
        assertEquals(
            "",
            OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = false, tolerant = false),
        )
        assertEquals(
            "fflags=+nobuffer+genpts",
            OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = true, tolerant = false),
        )
    }

    @Test
    fun `tolerant demuxing is a retry rung, never the first attempt`() {
        // The tolerance flags are what lets mpv finish a stream with corrupt packets or missing
        // timestamps (the "VLC plays it" class). They cost accuracy, so they only ever appear on a
        // retry — the first open of any stream must stay strict.
        assertEquals(
            "fflags=+discardcorrupt+genpts,err_detect=ignore_err",
            OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = false, tolerant = true),
        )
        // Combined with the fast-zap trimmed probe: one fflags list, both option sets kept.
        assertEquals(
            "fflags=+nobuffer+genpts+discardcorrupt,err_detect=ignore_err",
            OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = true, tolerant = true),
        )
        assertEquals(3, OwnTVPlayer.TOLERANT_DEMUX_AFTER_RECONNECTS)
    }

    @Test
    fun `mpv live opening loop is bounded`() {
        assertEquals(10_000L, OwnTVPlayer.LIVE_OPEN_TIMEOUT_MS)
    }

    @Test
    fun `an early end-file hard-resets a VOD but never a live channel`() {
        assertTrue(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = true, isLive = false))
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = true, isLive = true))
    }

    @Test
    fun `an end-file after the file loaded is never an early end-file`() {
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = true, expectingPlayback = true, isLive = false))
        assertFalse(OwnTVPlayer.shouldHardResetOnEarlyEndFile(fileLoaded = false, expectingPlayback = false, isLive = false))
    }
}
