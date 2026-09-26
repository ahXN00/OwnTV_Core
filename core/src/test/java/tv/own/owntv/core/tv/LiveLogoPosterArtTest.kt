package tv.own.owntv.core.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLogoPosterArtTest {

    @Test
    fun `a 16 by 9 card is a fixed 640 by 360 canvas`() {
        assertEquals(CanvasSize(640, 360), LiveLogoPosterArt.canvasSize(0))
    }

    @Test
    fun `square logo is scaled to the margin and centred inside a 16 by 9 canvas`() {
        // Height-bound: 360 * 0.9 = 324.
        assertEquals(Bounds(158, 18, 482, 342), LiveLogoPosterArt.fitInsideBounds(100, 100, 0))
    }

    @Test
    fun `small logo is scaled up rather than left tiny`() {
        assertEquals(Bounds(158, 18, 482, 342), LiveLogoPosterArt.fitInsideBounds(32, 32, 0))
    }

    @Test
    fun `wide logo is width-bound and centred vertically`() {
        // Width-bound: 640 * 0.9 = 576, so 576 by 144.
        assertEquals(Bounds(32, 108, 608, 252), LiveLogoPosterArt.fitInsideBounds(400, 100, 0))
    }

    @Test
    fun `square card keeps the whole logo inside a square canvas`() {
        assertEquals(Bounds(32, 32, 608, 608), LiveLogoPosterArt.fitInsideBounds(100, 100, 3))
    }
}
