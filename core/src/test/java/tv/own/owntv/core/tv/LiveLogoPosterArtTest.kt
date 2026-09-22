package tv.own.owntv.core.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLogoPosterArtTest {

    @Test
    fun `square logo is centered inside a 16 by 9 canvas without cropping`() {
        assertEquals(Bounds(49, 6, 149, 106), LiveLogoPosterArt.fitInsideBounds(100, 100, 0))
    }

    @Test
    fun `wide logo remains full width and is centered vertically`() {
        assertEquals(Bounds(22, 75, 422, 175), LiveLogoPosterArt.fitInsideBounds(400, 100, 0))
    }

    @Test
    fun `square poster art keeps the full logo inside a square canvas`() {
        assertEquals(Bounds(6, 6, 106, 106), LiveLogoPosterArt.fitInsideBounds(100, 100, 3))
    }
}
