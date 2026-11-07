package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreProfileNameTest {

    @Test
    fun `keep both uses the name it was given while it is free`() {
        assertEquals("B (restored)", freeName("B (restored)", setOf("b", "m")))
    }

    @Test
    fun `a taken name is numbered, ignoring case`() {
        assertEquals("B (restored) 2", freeName("B (restored)", setOf("b (restored)")))
        assertEquals("B (restored) 3", freeName("B (restored)", setOf("b (restored)", "b (restored) 2")))
    }
}
