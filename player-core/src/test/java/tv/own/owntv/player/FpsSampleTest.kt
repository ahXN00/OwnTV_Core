package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 24-vs-25 confusion, pinned.
 *
 * A 25fps channel measured over a one-second window reads 24.x whenever the window catches a frame of
 * slack, and 24 is a standard rate too — so the old "it snapped, therefore it is right" test published
 * "24 FPS" on a channel the decoder was demonstrably rendering at 25/s. Confidence now takes two
 * windows that agree.
 */
class FpsSampleTest {

    @Test
    fun `a single snapped window is not yet confident`() {
        val s = FpsSample()
        assertEquals(25f, s.accept(25.0f))
        assertFalse(s.confident)
    }

    @Test
    fun `two windows agreeing on the same rate are confident`() {
        val s = FpsSample()
        s.accept(24.9f)
        assertEquals(25f, s.accept(25.1f))
        assertTrue(s.confident)
    }

    @Test
    fun `a 25fps channel reading low once does not settle on 24`() {
        val s = FpsSample()
        // Short window, one frame of slack: 24 is nearer than 25, so it still snaps — to the wrong rate.
        assertEquals(24f, s.accept(24.4f))
        assertFalse("one off-by-a-frame window must not be trusted", s.confident)
        s.accept(25.0f)
        assertFalse("disagreeing windows stay unconfident", s.confident)
        s.accept(25.0f)
        assertTrue(s.confident)
    }

    @Test
    fun `an off-rate window is never confident however often it repeats`() {
        val s = FpsSample()
        s.accept(41f)
        assertEquals(41f, s.accept(41f)) // nothing standard is within 5%, so it passes through raw
        assertFalse(s.confident)
    }

    @Test
    fun `a new tune cannot inherit the previous channel's agreement`() {
        val s = FpsSample()
        s.accept(50f)
        s.accept(50f)
        assertTrue(s.confident)
        s.resetWindow()
        assertFalse("resetWindow drops the history", s.confident)
        s.accept(50f)
        assertFalse("the first window after a reset stands alone", s.confident)
    }
}
