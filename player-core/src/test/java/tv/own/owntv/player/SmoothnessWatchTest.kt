package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The softer tier below [FrameDropWatch]: judder that never becomes a slideshow. */
class SmoothnessWatchTest {

    /** Feeds [polls] health polls of [shownPerPoll] / [droppedPerPoll] and returns how many fired. */
    private fun SmoothnessWatch.run(polls: Int, shownPerPoll: Long, droppedPerPoll: Long, from: Pair<Long, Long> = 0L to 0L): Int {
        var (shown, dropped) = from
        var fired = 0
        repeat(polls) {
            shown += shownPerPoll; dropped += droppedPerPoll
            if (poll(shown, dropped)) fired++
        }
        return fired
    }

    @Test
    fun `a quarter of the frames dropped for 30 s fires once`() {
        val w = SmoothnessWatch()
        assertEquals(1, w.run(40, shownPerPoll = 112, droppedPerPoll = 38)) // 50p channel, ~25 % lost
    }

    @Test
    fun `not before 30 s of it`() {
        val w = SmoothnessWatch()
        assertEquals(0, w.run(SmoothnessWatch.WINDOW_POLLS, shownPerPoll = 112, droppedPerPoll = 38))
    }

    @Test
    fun `a clean or lightly dropping channel never fires`() {
        assertEquals(0, SmoothnessWatch().run(40, shownPerPoll = 150, droppedPerPoll = 0))
        assertEquals(0, SmoothnessWatch().run(40, shownPerPoll = 140, droppedPerPoll = 10)) // ~7 %
    }

    @Test
    fun `a burst while joining does not count`() {
        val w = SmoothnessWatch()
        // 45 frames dropped in the first poll, then clean: well under 20 % of 30 s.
        assertEquals(0, w.run(1, shownPerPoll = 100, droppedPerPoll = 45) + w.run(40, 150, 0, from = 100L to 45L))
    }

    @Test
    fun `a new decoder session starts the window again`() {
        val w = SmoothnessWatch()
        w.run(10, shownPerPoll = 112, droppedPerPoll = 38)
        // Counters back at zero: the old polls must not complete the window.
        assertEquals(0, w.run(SmoothnessWatch.WINDOW_POLLS - 2, shownPerPoll = 112, droppedPerPoll = 38))
    }

    @Test
    fun `reset arms it again`() {
        val w = SmoothnessWatch()
        w.run(40, shownPerPoll = 112, droppedPerPoll = 38)
        w.reset()
        assertEquals(1, w.run(40, shownPerPoll = 112, droppedPerPoll = 38))
    }
}
