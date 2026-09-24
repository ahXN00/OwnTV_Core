package tv.own.owntv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sleep timer on virtual time: a deadline rather than a tick count, and dropped when playback ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val active = MutableStateFlow(true)
    private var stops = 0

    /** Extra time the "device" has lived through that the scheduler has not, e.g. a sleeping CPU. */
    private var sleptMs = 0L

    private fun TestScope.timer() =
        SleepTimer(active, clock = { testScheduler.currentTime + sleptMs }, scope = backgroundScope)
            .also { it.stopPlayback = { stops++ } }

    @Test
    fun `fires once at the deadline`() = runTest {
        val t = timer()
        t.start(60_000L)
        advanceTimeBy(59_000L); runCurrent()
        assertEquals(0, stops)
        assertEquals(1_000L, t.remainingMs.value)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(1, stops)
        assertNull(t.remainingMs.value)
        advanceTimeBy(120_000L); runCurrent()
        assertEquals(1, stops)
    }

    @Test
    fun `a late wake-up fires at once instead of running long`() = runTest {
        val t = timer()
        t.start(10 * 60_000L)
        advanceTimeBy(1_000L); runCurrent()
        sleptMs = 10 * 60_000L // the device slept through the rest
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(1, stops)
        assertNull(t.remainingMs.value)
    }

    @Test
    fun `playback ending drops the timer after the grace`() = runTest {
        val t = timer()
        t.start(60 * 60_000L)
        runCurrent()
        active.value = false
        advanceTimeBy(SleepTimer.IDLE_GRACE_MS + 1); runCurrent()
        assertNull(t.remainingMs.value)
        advanceTimeBy(2 * 60 * 60_000L); runCurrent()
        assertEquals(0, stops)
    }

    @Test
    fun `a brief detach while switching keeps the timer`() = runTest {
        val t = timer()
        t.start(60 * 60_000L)
        runCurrent()
        active.value = false
        advanceTimeBy(500L); runCurrent()
        active.value = true
        // To a whole second, where the once-a-second countdown is sampled.
        advanceTimeBy(SleepTimer.IDLE_GRACE_MS * 2 - 500L); runCurrent()
        assertEquals(60 * 60_000L - SleepTimer.IDLE_GRACE_MS * 2, t.remainingMs.value)
    }

    @Test
    fun `a timer started with nothing playing is dropped too`() = runTest {
        active.value = false
        val t = timer()
        runCurrent()
        t.start(60 * 60_000L)
        advanceTimeBy(SleepTimer.IDLE_GRACE_MS + 1); runCurrent()
        assertNull(t.remainingMs.value)
        assertEquals(0, stops)
    }

    @Test
    fun `restart replaces, cancel stops, zero stops now`() = runTest {
        val t = timer()
        t.start(60_000L)
        advanceTimeBy(30_000L); runCurrent()
        t.start(60_000L)
        advanceTimeBy(45_000L); runCurrent()
        assertEquals(0, stops)
        t.cancel()
        advanceTimeBy(60_000L); runCurrent()
        assertEquals(0, stops)
        t.start(0L)
        assertEquals(1, stops)
        assertNull(t.remainingMs.value)
    }
}
