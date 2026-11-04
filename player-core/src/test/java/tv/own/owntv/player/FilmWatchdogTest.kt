package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.own.owntv.player.FilmWatchdog.Step

class FilmWatchdogTest {

    private fun tick(
        now: Long = 100_000L,
        elapsedMs: Long = 1_000L,
        heightPx: Int = 0,
        positionMs: Long = 0L,
        playing: Boolean = true,
        buffering: Boolean = false,
        fileLoaded: Boolean = false,
        decoderHadTime: Boolean = false,
        noVideoTrack: Boolean = false,
        sessionRefused: Boolean = false,
        archive: Boolean = false,
        triedFrozenReset: Boolean = false,
        triedOpenReset: Boolean = false,
    ) = FilmWatchdog.Tick(now, elapsedMs, heightPx, positionMs, playing, buffering, fileLoaded, decoderHadTime,
        noVideoTrack, sessionRefused, archive, triedFrozenReset, triedOpenReset)

    private val bitrate: suspend () -> Boolean = { true }
    private val noBitrate: suspend () -> Boolean = { false }

    @Test
    fun `a picture that moves past the first two seconds stands the watchdog down`() = runBlocking {
        val w = FilmWatchdog()
        assertEquals(Step.WAIT, w.step(tick(heightPx = 720, positionMs = 60_000), bitrate))
        assertEquals(60_000L, w.openedAtPos)
        assertEquals(Step.WAIT, w.step(tick(heightPx = 720, positionMs = 62_000), bitrate))
        assertEquals(Step.PLAYING, w.step(tick(heightPx = 720, positionMs = 62_001), bitrate))
    }

    @Test
    fun `stuck on the first frame for eight seconds resets, then gives up`() = runBlocking {
        val w = FilmWatchdog()
        assertEquals(Step.WAIT, w.step(tick(now = 1_000, heightPx = 720, positionMs = 5_000), bitrate))
        assertEquals(Step.WAIT, w.step(tick(now = 8_999, heightPx = 720, positionMs = 5_000), bitrate))
        assertEquals(Step.FROZEN_RESET, w.step(tick(now = 9_000, heightPx = 720, positionMs = 5_000), bitrate))
        assertEquals(8_000L, w.stillForMs)
        val again = FilmWatchdog()
        again.step(tick(now = 1_000, heightPx = 720), bitrate)
        assertEquals(Step.FROZEN_AGAIN, again.step(tick(now = 10_000, heightPx = 720, triedFrozenReset = true), bitrate))
    }

    @Test
    fun `paused or buffering restarts the frozen count`() = runBlocking {
        val w = FilmWatchdog()
        w.step(tick(now = 0, heightPx = 720), bitrate)
        assertEquals(Step.WAIT, w.step(tick(now = 7_000, heightPx = 720, buffering = true), bitrate))
        assertEquals(Step.WAIT, w.step(tick(now = 8_000, heightPx = 720), bitrate))
        assertEquals(Step.WAIT, w.step(tick(now = 15_999, heightPx = 720), bitrate))
        assertEquals(Step.FROZEN_RESET, w.step(tick(now = 16_000, heightPx = 720), bitrate))
    }

    @Test
    fun `an item with no video track stands down once audio plays`() = runBlocking {
        val t = tick(elapsedMs = 5_001, positionMs = 1, fileLoaded = true, decoderHadTime = true, noVideoTrack = true)
        assertEquals(Step.NO_VIDEO_TRACK, FilmWatchdog().step(t, noBitrate))
        // Not before 5 s, and not before the clock moves: then the stages below judge it.
        assertEquals(Step.WAIT, FilmWatchdog().step(t.copy(elapsedMs = 5_000, decoderHadTime = false), noBitrate))
        assertEquals(Step.LOADED_NO_METADATA, FilmWatchdog().step(t.copy(positionMs = 0, elapsedMs = 6_001), noBitrate))
    }

    @Test
    fun `stage 1 - never opened after ten seconds`() = runBlocking {
        assertEquals(Step.WAIT, FilmWatchdog().step(tick(elapsedMs = 10_000), noBitrate))
        assertEquals(Step.OPEN_RESET, FilmWatchdog().step(tick(elapsedMs = 10_001), noBitrate))
        assertEquals(Step.OPEN_AGAIN, FilmWatchdog().step(tick(elapsedMs = 10_001, triedOpenReset = true), noBitrate))
        // A refused session already ended through END_FILE.
        assertEquals(Step.SESSION_REFUSED, FilmWatchdog().step(tick(elapsedMs = 10_001, sessionRefused = true), noBitrate))
    }

    @Test
    fun `stage 2 - loaded with no metadata, asks for the bitrate only when it matters`() = runBlocking {
        var asked = 0
        val count: suspend () -> Boolean = { asked++; false }
        FilmWatchdog().step(tick(elapsedMs = 6_000, fileLoaded = true, decoderHadTime = true), count)
        FilmWatchdog().step(tick(elapsedMs = 9_000, fileLoaded = true, decoderHadTime = false), count)
        assertEquals(0, asked)
        assertEquals(Step.LOADED_NO_METADATA, FilmWatchdog().step(tick(elapsedMs = 6_001, fileLoaded = true, decoderHadTime = true), count))
        assertEquals(1, asked)
    }

    @Test
    fun `stage 3 - loaded with a bitrate but no frame after seven seconds`() = runBlocking {
        val t = tick(elapsedMs = 7_000, fileLoaded = true, decoderHadTime = true)
        assertEquals(Step.WAIT, FilmWatchdog().step(t, bitrate))
        assertEquals(Step.NO_FRAME, FilmWatchdog().step(t.copy(elapsedMs = 7_001), bitrate))
    }

    @Test
    fun `nothing fires early`() = runBlocking {
        val w = FilmWatchdog()
        for (ms in 0L..5_000L step 1_000L) assertEquals(Step.WAIT, w.step(tick(elapsedMs = ms, fileLoaded = ms > 2_000), noBitrate))
        assertFalse(w.openedAtPos >= 0)
    }
}
