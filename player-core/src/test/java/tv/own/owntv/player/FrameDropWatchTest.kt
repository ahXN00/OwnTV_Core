package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDropWatchTest {

    /** Feeds per-poll increments and returns the verdict of every poll. */
    private fun run(w: FrameDropWatch, steps: List<Pair<Long, Long>>): List<Boolean> {
        var f = 0L; var d = 0L
        return steps.map { (df, dd) -> f += df; d += dd; w.poll(f, d) }
    }

    @Test
    fun `a decoder dropping far more than it shows hands over after a full window`() {
        // Four 2.5 s deltas need five samples: the fifth poll is the first with a full window.
        val verdicts = run(FrameDropWatch(), List(5) { 20L to 500L })
        assertFalse(verdicts.take(FrameDropWatch.WINDOW_POLLS).any { it })
        assertTrue(verdicts.last())
    }

    @Test
    fun `a healthy 60 fps channel with a few joining drops never hands over`() {
        val steps = listOf(150L to 19L) + List(20) { 150L to 0L }
        assertFalse(run(FrameDropWatch(), steps).any { it })
    }

    @Test
    fun `heavy drops while still showing more frames than dropped is not a slideshow`() {
        assertFalse(run(FrameDropWatch(), List(10) { 150L to 120L }).any { it })
    }

    @Test
    fun `fewer than the minimum drops never hands over even with nothing shown`() {
        assertFalse(run(FrameDropWatch(), List(10) { 0L to 20L }).any { it })
    }

    @Test
    fun `a counter reset starts the window over`() {
        val w = FrameDropWatch()
        run(w, List(4) { 20L to 500L })
        assertFalse(w.poll(0, 0)) // new decoder session
        assertFalse(run(w, List(3) { 20L to 500L }).any { it })
    }

    @Test
    fun `reset forgets the window`() {
        val w = FrameDropWatch()
        run(w, List(4) { 20L to 500L })
        w.reset()
        assertFalse(run(w, List(4) { 20L to 500L }).any { it })
    }
}
