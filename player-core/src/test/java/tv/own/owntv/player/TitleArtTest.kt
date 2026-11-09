package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 4K badge and the key a title logo is matched on. */
class TitleArtTest {

    @Test
    fun `a cinemascope 4K film gets the badge, 1440p and 1080p do not`() {
        assertTrue(isUltraHd(classifyResolution(3840, 1600)))
        assertTrue(isUltraHd(classifyResolution(3840, 2160)))
        assertFalse(isUltraHd(classifyResolution(2560, 1440)))
        assertFalse(isUltraHd(classifyResolution(1920, 800)))
        assertFalse(isUltraHd(null))
    }

    @Test
    fun `an episode's art is keyed on its show, a film's on itself`() {
        assertEquals("show", MediaMeta(contentKey = "episode", trackKey = "show").artKey)
        assertEquals("film", MediaMeta(contentKey = "film").artKey)
    }
}
