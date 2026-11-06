package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveResumeTest {

    private val limit = LiveResume.LIVE_EDGE_AFTER_PAUSE_MS

    @Test
    fun `a long pause on a live channel goes back to the live edge`() {
        assertTrue(LiveResume.backToLiveEdge(live = true, fromSavedCopy = false, pausedAtMs = 1_000, nowMs = 1_001 + limit))
    }

    @Test
    fun `a short pause resumes where it was`() {
        assertFalse(LiveResume.backToLiveEdge(live = true, fromSavedCopy = false, pausedAtMs = 1_000, nowMs = 1_000 + limit))
        assertFalse(LiveResume.backToLiveEdge(live = true, fromSavedCopy = false, pausedAtMs = 1_000, nowMs = 11_000))
    }

    @Test
    fun `the saved copy, films and an unpaused load never jump`() {
        assertFalse(LiveResume.backToLiveEdge(live = true, fromSavedCopy = true, pausedAtMs = 1_000, nowMs = 10 * limit))
        assertFalse(LiveResume.backToLiveEdge(live = false, fromSavedCopy = false, pausedAtMs = 1_000, nowMs = 10 * limit))
        assertFalse(LiveResume.backToLiveEdge(live = true, fromSavedCopy = false, pausedAtMs = 0, nowMs = 10 * limit))
    }
}
