package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.player.PlayerBudget

class MpvLoadOptionsTest {

    private val budget = PlayerBudget("128MiB", "32MiB", readaheadSecs = "30", cacheSecs = "60", lowSpec = false)

    private fun input(
        url: String = "http://panel.example/live/u/p/1.ts",
        live: Boolean = true,
        knownHlsHost: Boolean = false,
        prerollSecs: Int = 0,
        budget: PlayerBudget? = this.budget,
        brokenTimestamps: Boolean = false,
        directRender: Boolean = true,
        archive: Boolean = false,
        forceFullProbe: Boolean = false,
        tolerant: Boolean = false,
        filmBufferSecs: Int = 0,
        filmNetworkTimeoutSecs: Int = 0,
    ) = MpvLoadOptions.Input(
        url = url, live = live, knownHlsHost = knownHlsHost, prerollSecs = prerollSecs, liveBufferSecs = null,
        filmBufferSecs = filmBufferSecs, filmNetworkTimeoutSecs = filmNetworkTimeoutSecs, budget = budget,
        cacheTrimmed = false, brokenTimestamps = brokenTimestamps, directRender = directRender, archive = archive,
        forceFullProbe = forceFullProbe, tolerant = tolerant,
    )

    private fun MpvLoadOptions.Result.value(name: String) = options.single { it.first == name }.second

    @Test
    fun `live raw TS gets the trimmed probe, live reconnects and the 2 s resume`() {
        val r = MpvLoadOptions.of(input())
        assertTrue(r.trimmed)
        assertFalse(r.effectiveHls)
        assertEquals("1000000", r.value("demuxer-lavf-probesize"))
        assertEquals("1.0", r.value("demuxer-lavf-analyzeduration"))
        assertEquals("fflags=+nobuffer+genpts", r.value("demuxer-lavf-o"))
        assertEquals(OwnTVPlayer.streamLavfOptionsFor(input().url, live = true, hls = false), r.value("stream-lavf-o"))
        assertEquals(OwnTVPlayer.LIVE_NETWORK_TIMEOUT_SECS.toString(), r.value("network-timeout"))
        assertEquals("no", r.value("cache-pause-initial"))
        assertEquals("2", r.value("cache-pause-wait"))
        assertEquals("yes", r.value("correct-pts"))
        assertEquals("audio", r.value("video-sync"))
        assertEquals("decoder+vo", r.value("framedrop"))
        assertEquals("", r.value("vd-lavc-o"))
        assertTrue(r.diagnostic.startsWith("mpv_open hls=false live=true probe=trimmed tolerant=false"))
        assertTrue(r.bufferLog!!.startsWith("live_buffer preroll=0s"))
    }

    @Test
    fun `options are set in the order the player always used`() {
        assertEquals(
            listOf(
                "stream-lavf-o", "network-timeout", "demuxer-readahead-secs", "cache-secs", "demuxer-max-bytes",
                "cache-pause-initial", "cache-pause-wait", "correct-pts", "video-sync", "framedrop", "vd-lavc-o",
                "demuxer-lavf-probesize", "demuxer-lavf-analyzeduration", "demuxer-lavf-o",
            ),
            MpvLoadOptions.of(input()).options.map { it.first },
        )
    }

    @Test
    fun `HLS, a known HLS host and a forced full probe all get the full probe`() {
        for (r in listOf(
            MpvLoadOptions.of(input(url = "http://panel.example/live/u/p/1.m3u8?t=1")),
            MpvLoadOptions.of(input(knownHlsHost = true)),
            MpvLoadOptions.of(input(forceFullProbe = true)),
        )) {
            assertFalse(r.trimmed)
            assertEquals("5000000", r.value("demuxer-lavf-probesize"))
            assertEquals("0", r.value("demuxer-lavf-analyzeduration"))
            assertEquals("", r.value("demuxer-lavf-o"))
        }
        assertTrue(MpvLoadOptions.of(input(knownHlsHost = true)).effectiveHls)
        // A known HLS host only counts for live: a catch-up .ts from the same panel is raw TS.
        assertFalse(MpvLoadOptions.of(input(live = false, knownHlsHost = true)).effectiveHls)
    }

    @Test
    fun `a catch-up archive is never trimmed and drops slices before the first keyframe`() {
        val r = MpvLoadOptions.of(input(url = "http://panel.example/timeshift/u/p/60/2026-10-21:10-00/1.ts", live = false, archive = true))
        assertFalse(r.trimmed)
        assertEquals("wait_keyframe=1", r.value("vd-lavc-o"))
        assertTrue(r.bufferLog!!.startsWith("film_buffer"))
    }

    @Test
    fun `broken timestamps free-run the video, only on live`() {
        val r = MpvLoadOptions.of(input(brokenTimestamps = true))
        assertEquals("no", r.value("correct-pts"))
        assertEquals("desync", r.value("video-sync"))
        assertEquals("no", r.value("framedrop"))
        assertEquals("yes", MpvLoadOptions.of(input(live = false, brokenTimestamps = true)).value("correct-pts"))
    }

    @Test
    fun `vo gpu resamples to the display`() {
        assertEquals("display-resample", MpvLoadOptions.of(input(directRender = false)).value("video-sync"))
    }

    @Test
    fun `pre-buffer holds the first picture on live only`() {
        val live = MpvLoadOptions.of(input(prerollSecs = 3))
        assertEquals("yes", live.value("cache-pause-initial"))
        assertEquals("3", live.value("cache-pause-wait"))
        assertEquals("no", MpvLoadOptions.of(input(live = false, prerollSecs = 3)).value("cache-pause-initial"))
    }

    @Test
    fun `a film uses its own network timeout and buffer`() {
        val r = MpvLoadOptions.of(input(url = "http://panel.example/movie/u/p/9.mkv", live = false, filmBufferSecs = 120, filmNetworkTimeoutSecs = 45))
        assertEquals("45", r.value("network-timeout"))
        assertEquals(FilmNetwork.bufferSecs(120, budget).toString(), r.value("cache-secs"))
        assertEquals(FilmNetwork.mpvResumeWaitSecs(120).toString(), r.value("cache-pause-wait"))
        // Live ignores the film values.
        assertEquals(OwnTVPlayer.LIVE_NETWORK_TIMEOUT_SECS.toString(), MpvLoadOptions.of(input(filmNetworkTimeoutSecs = 45)).value("network-timeout"))
    }

    @Test
    fun `no budget yet leaves the cache sizes alone`() {
        val r = MpvLoadOptions.of(input(budget = null))
        assertTrue(r.options.none { it.first in setOf("demuxer-readahead-secs", "cache-secs", "demuxer-max-bytes") })
        assertNull(r.bufferLog)
    }

    @Test
    fun `tolerant demux merges its flags into the trimmed probe`() {
        val r = MpvLoadOptions.of(input(tolerant = true))
        assertEquals(OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = true, tolerant = true), r.value("demuxer-lavf-o"))
        assertTrue(r.diagnostic.contains("tolerant=true"))
    }
}
