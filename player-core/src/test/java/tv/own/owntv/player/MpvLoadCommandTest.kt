package tv.own.owntv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A resumed film opens at its position in the load itself, not with a seek after it. */
class MpvLoadCommandTest {

    @Test
    fun `no position clears the start`() {
        assertEquals("none", MpvLoadCommand.startOption(0))
    }

    @Test
    fun `a position is given to the millisecond`() {
        assertEquals("2893.457", MpvLoadCommand.startOption(2_893_457))
        assertEquals("0.050", MpvLoadCommand.startOption(50))
    }

    @Test
    fun `the seconds never depend on the device language`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma
            assertEquals("1.500", MpvLoadCommand.startOption(1_500))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `the fallback seek is to the millisecond too`() {
        assertArrayEquals(arrayOf("seek", "2893.457", "absolute"), MpvLoadCommand.seekTo(2_893_457))
    }
}
