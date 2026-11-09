package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fit's shape: the display size once known, the stored frame until then. */
class DisplayAspectTest {

    @Test
    fun `an anamorphic wide film keeps its wide shape, not its 16 by 9 frame`() {
        // 1920×1080 stored, flagged to be shown 2.39 times as wide as tall.
        assertEquals(2.39f, displayAspect(1920, 1080, 2581, 1080)!!, 0.01f)
    }

    @Test
    fun `square pixels give the same shape either way`() {
        assertEquals(2.4f, displayAspect(1920, 800, 1920, 800)!!, 0.001f)
        assertEquals(16f / 9f, displayAspect(3840, 2160, 3840, 2160)!!, 0.001f)
    }

    @Test
    fun `the stored frame stands in until the display size arrives`() {
        assertEquals(16f / 9f, displayAspect(1920, 1080, 0, 0)!!, 0.001f)
        assertEquals(16f / 9f, displayAspect(1920, 1080, 1920, 0)!!, 0.001f)
        assertNull(displayAspect(0, 0, 0, 0))
    }
}
