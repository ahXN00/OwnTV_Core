package tv.own.owntv.core.brand

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconTest {

    @Test
    fun `stored names round-trip`() {
        AppIcon.entries.forEach { assertEquals(it, AppIcon.fromStored(it.name)) }
    }

    @Test
    fun `nothing stored or an unknown name falls back to eggshell`() {
        assertEquals(AppIcon.EGGSHELL, AppIcon.fromStored(null))
        assertEquals(AppIcon.EGGSHELL, AppIcon.fromStored("VIOLET"))
        assertEquals(AppIcon.EGGSHELL, AppIcon.DEFAULT)
    }

    @Test
    fun `eggshell is MainActivity itself and every colour has its own activity`() {
        // Upgrading users' home-screen icons point at MainActivity, so the default must keep that name.
        assertEquals("", AppIcon.EGGSHELL.activitySuffix)
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.activitySuffix }.toSet().size)
    }
}
