package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frame rate from presentation timestamps: precise enough to tell 59.94 from 60 and 23.976 from 24. */
class PtsFrameRateTest {

    /** Feeds [frames] timestamps of a [fps] stream as a TS demuxer delivers them (90 kHz ticks). */
    private fun PtsFrameRate.feed(
        fps: Double, frames: Int, startUs: Long = 1_000_000L, skipEvery: Int = 0, wholeMs: Boolean = false,
        phaseMs: Double = 0.0,
    ) {
        for (i in 0 until frames) {
            if (skipEvery > 0 && i % skipEvery == skipEvery - 1) continue // a dropped frame never reaches the listener
            val us = if (wholeMs) Math.round(i * 1000.0 / fps + phaseMs) * 1000L else Math.round(i * 90_000.0 / fps) * 1_000_000L / 90_000L
            add(startUs + us)
        }
    }

    @Test
    fun `not enough frames gives no reading`() {
        val r = PtsFrameRate()
        r.feed(25.0, 30)
        assertNull(r.rate())
    }

    @Test
    fun `NTSC rates are told apart from the round ones`() {
        for ((fps, want) in listOf(
            60_000.0 / 1001 to 59.94f, 60.0 to 60f,
            30_000.0 / 1001 to 29.97f, 30.0 to 30f,
            24_000.0 / 1001 to 23.976f, 24.0 to 24f,
            25.0 to 25f, 50.0 to 50f,
        )) {
            val r = PtsFrameRate()
            r.feed(fps, 400)
            assertEquals("fps $fps", want, r.rate()!!, 0.0001f)
        }
    }

    @Test
    fun `timestamps rounded to whole milliseconds still tell 59_94 from 60`() {
        // Some providers remux with a 1 ms clock: every gap reads 16 or 17 ms.
        for ((fps, want) in listOf(60_000.0 / 1001 to 59.94f, 60.0 to 60f, 30_000.0 / 1001 to 29.97f)) {
            for (phase in listOf(0.0, 0.3, 0.5, 0.8)) {
                val r = PtsFrameRate()
                r.feed(fps, 400, wholeMs = true, phaseMs = phase)
                assertEquals("fps $fps phase $phase", want, r.rate()!!, 0.0001f)
            }
        }
    }

    @Test
    fun `no reading is given before the span can resolve it`() {
        // One second of whole-millisecond stamps cannot separate 59.94 from 60; an answer then must not be 60.
        for (frames in 50..400 step 7) {
            for (phase in listOf(0.0, 0.3, 0.5, 0.8)) {
                val r = PtsFrameRate()
                r.feed(60_000.0 / 1001, frames, wholeMs = true, phaseMs = phase)
                r.rate()?.let { assertEquals("frames $frames phase $phase", 59.94f, it, 0.0001f) }
            }
        }
    }

    @Test
    fun `dropped frames do not lower the reading`() {
        val r = PtsFrameRate()
        r.feed(60_000.0 / 1001, 400, skipEvery = 7)
        assertEquals(59.94f, r.rate()!!, 0.0001f)
    }

    @Test
    fun `a timestamp jump or reset is ignored`() {
        val r = PtsFrameRate()
        r.feed(25.0, 80)
        r.feed(25.0, 80, startUs = 50_000L) // the feed restarted its clock mid-stream
        r.feed(25.0, 150, startUs = 900_000_000L)
        assertEquals(25f, r.rate()!!, 0.0001f)
    }

    @Test
    fun `the previous channel's last frames do not bend the reading`() {
        val r = PtsFrameRate()
        r.feed(50.0, 4)
        r.feed(60_000.0 / 1001, 300, startUs = 7_300_000_000L) // another channel's clock
        assertEquals(59.94f, r.rate()!!, 0.0001f)
    }

    @Test
    fun `an unusual rate is returned as measured`() {
        val r = PtsFrameRate()
        r.feed(12.5, 80)
        assertEquals(12.5f, r.rate()!!, 0.01f)
    }

    @Test
    fun `reset forgets the previous stream`() {
        val r = PtsFrameRate()
        r.feed(50.0, 300)
        r.reset()
        r.feed(25.0, 30)
        assertNull(r.rate())
    }
}
