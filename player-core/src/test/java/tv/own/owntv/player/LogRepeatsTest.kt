package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** A line mpv repeats is written once, then once more as a count when something else is said. */
class LogRepeatsTest {

    @Test
    fun `a new line passes straight through`() {
        val r = LogRepeats()
        assertEquals(listOf("a"), r.next("a"))
        assertEquals(listOf("b"), r.next("b"))
    }

    @Test
    fun `repeats are held back and summed up before the next different line`() {
        val r = LogRepeats()
        assertEquals(listOf("a"), r.next("a"))
        assertEquals(emptyList<String>(), r.next("a"))
        assertEquals(emptyList<String>(), r.next("a"))
        assertEquals(listOf("(repeated ×2 more)", "b"), r.next("b"))
    }

    @Test
    fun `lines that take turns are held back too`() {
        // mpv's per-frame warnings on a stream joined mid-way alternate between two NAL types.
        val r = LogRepeats()
        assertEquals(listOf("a"), r.next("a"))
        assertEquals(listOf("b"), r.next("b"))
        repeat(4) { assertEquals(emptyList<String>(), r.next(if (it % 2 == 0) "a" else "b")) }
        assertEquals(listOf("(repeated ×4 more)", "c"), r.next("c"))
    }

    @Test
    fun `a burst ends at its summary, so a line seen before it is new again`() {
        val r = LogRepeats()
        r.next("a"); r.next("a"); r.next("b")
        assertEquals(listOf("a"), r.next("a"))
    }
}
