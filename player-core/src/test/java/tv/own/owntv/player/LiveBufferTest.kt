package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency

/**
 * The Live latency / Pre-buffer numbers, and the equality that makes them take effect.
 *
 * `LivePreviewEngine` keeps ONE ExoPlayer alive across tunes, and a `LoadControl` is fixed when the
 * player is constructed — so the engine rebuilds the player whenever `loadControlFor` returns something
 * different from what the live player was built with. That comparison is plain data-class equality,
 * which makes "does changing the setting actually change anything?" a question this test can answer:
 * the "Pre-buffer does nothing" report was exactly this returning an equal value.
 */
class LiveBufferTest {

    @Test
    fun `the defaults are byte-identical to the values that were hardcoded before the setting existed`() {
        // Balanced + Pre-buffer Off must never be a behaviour change for a user who touched nothing.
        val lc = LiveBuffer.loadControlFor(bufferSecs = null, prerollSecs = LiveBuffer.PREROLL_OFF)
        assertEquals(8_000, lc.minBufferMs)
        assertEquals(10_000, lc.maxBufferMs)
        assertEquals(LiveBuffer.DEFAULT_START_MS, lc.bufferForPlaybackMs)
        assertEquals(LiveBuffer.DEFAULT_RESTART_MS, lc.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun `the socket idle window stays two seconds at every preset`() {
        // max − min is the wall-clock window a raw-TS socket can sit idle, not the buffer depth. Provider
        // restreamers cull a socket parked longer, and the EOF that follows costs a visible reconnect.
        val presets = listOf(null, LiveBuffer.LOW_SECS, LiveBuffer.STABLE_SECS, LiveBuffer.CUSTOM_MIN, LiveBuffer.CUSTOM_MAX)
        for (secs in presets) {
            val lc = LiveBuffer.loadControlFor(secs, LiveBuffer.PREROLL_OFF)
            assertEquals("idle window for $secs s", 2_000, lc.maxBufferMs - lc.minBufferMs)
        }
    }

    @Test
    fun `each latency preset moves the same shape`() {
        assertEquals(2_000, LiveBuffer.loadControlFor(LiveBuffer.LOW_SECS, 0).minBufferMs)
        assertEquals(8_000, LiveBuffer.loadControlFor(null, 0).minBufferMs)
        assertEquals(15_000, LiveBuffer.loadControlFor(LiveBuffer.STABLE_SECS, 0).minBufferMs)
    }

    @Test
    fun `a pre-roll raises the buffer floor instead of throwing`() {
        // DefaultLoadControl requires minBufferMs >= bufferForPlayback*, so asking to buffer 10 s before
        // starting means the buffer must be allowed to hold 10 s — a deep pre-roll wins over a shallow
        // preset rather than being rejected.
        val lc = LiveBuffer.loadControlFor(LiveBuffer.LOW_SECS, prerollSecs = 10)
        assertEquals(10_000, lc.bufferForPlaybackMs)
        assertEquals(10_000, lc.bufferForPlaybackAfterRebufferMs)
        assertEquals(10_000, lc.minBufferMs)
        assertEquals(12_000, lc.maxBufferMs)
        assertTrue(lc.minBufferMs >= lc.bufferForPlaybackMs)
        assertTrue(lc.minBufferMs >= lc.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun `a pre-roll shallower than the preset leaves the depth alone`() {
        val lc = LiveBuffer.loadControlFor(LiveBuffer.STABLE_SECS, prerollSecs = 2)
        assertEquals(15_000, lc.minBufferMs)
        assertEquals(2_000, lc.bufferForPlaybackMs)
    }

    @Test
    fun `every offered Pre-buffer choice produces a distinct load control`() {
        // This is what makes the setting reach the player at all: the engine only rebuilds when the
        // resolved numbers differ from the ones it was built with. Two choices resolving equal would be
        // "Pre-buffer does nothing" all over again.
        val resolved = LiveBuffer.PREROLL_CHOICES.map { LiveBuffer.loadControlFor(null, it) }
        assertEquals(LiveBuffer.PREROLL_CHOICES.size, resolved.toSet().size)
    }

    @Test
    fun `changing either half of the pair changes the load control, and changing neither does not`() {
        val base = LiveBuffer.loadControlFor(null, 0)
        assertEquals(base, LiveBuffer.loadControlFor(null, 0)) // same pair → no needless player rebuild
        assertNotEquals(base, LiveBuffer.loadControlFor(LiveBuffer.LOW_SECS, 0)) // latency changed
        assertNotEquals(base, LiveBuffer.loadControlFor(null, 5)) // pre-buffer changed
    }

    @Test
    fun `Balanced is the only preset that overrides nothing`() {
        assertNull(LiveBuffer.effectiveSeconds(LiveLatency.BALANCED, LiveBuffer.CUSTOM_DEFAULT))
        assertEquals(LiveBuffer.LOW_SECS, LiveBuffer.effectiveSeconds(LiveLatency.LOW, LiveBuffer.CUSTOM_DEFAULT))
        assertEquals(LiveBuffer.STABLE_SECS, LiveBuffer.effectiveSeconds(LiveLatency.STABLE, LiveBuffer.CUSTOM_DEFAULT))
        assertEquals(20, LiveBuffer.effectiveSeconds(LiveLatency.CUSTOM, 20))
        assertEquals(LiveLatency.BALANCED, LiveLatency.DEFAULT)
    }

    @Test
    fun `a custom buffer is clamped to a range the engines can honour`() {
        assertEquals(LiveBuffer.CUSTOM_MIN, LiveBuffer.effectiveSeconds(LiveLatency.CUSTOM, 0))
        assertEquals(LiveBuffer.CUSTOM_MAX, LiveBuffer.effectiveSeconds(LiveLatency.CUSTOM, 9_999))
        assertEquals(LiveBuffer.CUSTOM_MIN, LiveBuffer.clampCustom(-5))
    }

    @Test
    fun `only a real below-Balanced buffer earns the low-latency warning`() {
        assertTrue(LiveBuffer.isLowLatency(LiveBuffer.LOW_SECS))
        assertTrue(!LiveBuffer.isLowLatency(null)) // Balanced overrides nothing, so it warns about nothing
        assertTrue(!LiveBuffer.isLowLatency(LiveBuffer.STABLE_SECS))
        assertTrue(!LiveBuffer.isLowLatency(LiveBuffer.WARN_BELOW_SECS))
    }

    @Test
    fun `the byte cap grows with the requested depth, bounded at three times the default`() {
        // A user who asked for 15 s would otherwise silently get ~7 on a 25 Mbps UHD stream, because the
        // default cap is reached long before the time floor is.
        val default = 24 * 1024 * 1024
        assertEquals(default, LiveBuffer.targetBufferBytes(null, 0, default))
        assertEquals(default, LiveBuffer.targetBufferBytes(LiveBuffer.LOW_SECS, 0, default))
        assertEquals(default * 15 / LiveBuffer.BALANCED_SECS, LiveBuffer.targetBufferBytes(15, 0, default))
        assertEquals(default * 3, LiveBuffer.targetBufferBytes(LiveBuffer.CUSTOM_MAX, 0, default))
    }

    @Test
    fun `a deep pre-roll counts towards the byte cap even at a shallow preset`() {
        // loadControlFor raises the time floor to hold the pre-roll, so a cap left at the Balanced size
        // would stop the load before that floor could ever be reached.
        val default = 24 * 1024 * 1024
        assertEquals(default * 10 / LiveBuffer.BALANCED_SECS, LiveBuffer.targetBufferBytes(LiveBuffer.LOW_SECS, 10, default))
    }

    @Test
    fun `a 2 GB TV scales the byte cap to twice the default at most`() {
        val lowRam = 16 * 1024 * 1024
        assertEquals(lowRam * 2, LiveBuffer.targetBufferBytes(LiveBuffer.CUSTOM_MAX, 0, lowRam, lowSpec = true))
        // Below the ceiling nothing changes.
        val justDeeper = LiveBuffer.BALANCED_SECS + 1
        assertEquals(
            (lowRam.toLong() * justDeeper / LiveBuffer.BALANCED_SECS).toInt(),
            LiveBuffer.targetBufferBytes(justDeeper, 0, lowRam, lowSpec = true),
        )
    }
}
