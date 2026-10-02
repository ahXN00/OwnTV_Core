package tv.own.owntv.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConfigTest {

    private fun resolve(
        style: String? = null, look: String? = null, dim: Int? = null, blur: Int? = null,
        accent: Boolean? = null, path: String = "", glassOn: Boolean = true,
    ) = BackgroundConfig.resolve(style, look, dim, blur, accent, path, glassOn)

    @Test
    fun `an existing picture keeps today's look - Sharp with the old 15 percent darken`() {
        val c = resolve(path = "/data/bg.jpg")
        assertEquals(BackgroundStyle.PICTURE, c.style)
        assertEquals(PictureLook.SHARP, c.look)
        assertEquals(15, c.dimPct)
        assertEquals(0, c.blurPct)
        assertFalse(c.accentLight)
        assertTrue(c.showsPicture)
    }

    @Test
    fun `glass off without a picture becomes Plain, glass on the Stage colours`() {
        assertEquals(BackgroundStyle.PLAIN, resolve(glassOn = false).style)
        assertEquals(BackgroundStyle.STAGE, resolve().style)
        assertTrue(resolve().accentLight)
        assertFalse(resolve(glassOn = false).accentLight)
    }

    @Test
    fun `a stored look supplies its own defaults, overrides win`() {
        val soft = resolve(style = "PICTURE", look = "SOFT", path = "/p")
        assertEquals(50, soft.dimPct); assertEquals(80, soft.blurPct); assertTrue(soft.accentLight)
        val tuned = resolve(style = "PICTURE", look = "DARK", dim = 60, blur = 10, accent = true, path = "/p")
        assertEquals(60, tuned.dimPct); assertEquals(10, tuned.blurPct); assertTrue(tuned.accentLight)
    }

    @Test
    fun `picture style without a file draws no picture`() {
        assertFalse(resolve(style = "PICTURE").showsPicture)
    }

    @Test
    fun `values are clamped and unknown names fall back`() {
        val c = resolve(style = "NOPE", look = "NOPE", dim = 400, blur = -3)
        assertEquals(BackgroundStyle.STAGE, c.style)
        assertEquals(PictureLook.SOFT, c.look)
        assertEquals(BackgroundConfig.DIM_MAX, c.dimPct)
        assertEquals(0, c.blurPct)
    }
}
