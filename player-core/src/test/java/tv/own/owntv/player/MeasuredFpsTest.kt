package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A measured frame rate is published only when two readings in a row agree on a broadcast rate. */
class MeasuredFpsTest {

    @Test
    fun `two agreeing readings snap to the nearest standard rate`() {
        assertEquals(23.976f, MeasuredFps.settle(23.95f, 23.98f))
        assertEquals(50f, MeasuredFps.settle(49.8f, 50.05f))
    }

    @Test
    fun `a missing reading settles nothing`() {
        assertNull(MeasuredFps.settle(null, 24f))
        assertNull(MeasuredFps.settle(24f, null))
    }

    @Test
    fun `readings that disagree settle nothing`() {
        assertNull(MeasuredFps.settle(24f, 30f))
    }

    @Test
    fun `a reading far from every standard rate settles nothing`() {
        assertNull(MeasuredFps.settle(18f, 18.1f))
    }
}
