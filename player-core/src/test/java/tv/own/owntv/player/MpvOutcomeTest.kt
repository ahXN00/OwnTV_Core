package tv.own.owntv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpvOutcomeTest {

    /** OwnTVPlayer live-load signals. buffering and videoRes are not an open. */
    private class LoadModel(private val scope: TestScope) {
        val buffering = MutableStateFlow(false)
        val videoRes = MutableStateFlow<String?>(null)
        val started = MutableStateFlow(-1)
        val error = MutableStateFlow<Any?>(null)
        val gen = 7

        /** App sets buffering, then mpv's stale paused-for-cache=false arrives. */
        fun load() {
            buffering.value = true
            scope.runCurrent()
            buffering.value = false
        }
    }

    @Test
    fun `stale paused-for-cache after the app sets buffering is not an open`() = runTest {
        val m = LoadModel(this)
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(pending.await())
    }

    @Test
    fun `open timeout error after the stale sequence is a failure`() = runTest {
        val m = LoadModel(this)
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        advanceTimeBy(12_000)
        runCurrent()
        m.error.value = "timed out"
        runCurrent()
        val outcome = pending.await()
        assertFalse(outcome!!.opened)
        assertEquals("timed out", outcome.error)
    }

    @Test
    fun `a started signal from this load opens`() = runTest {
        val m = LoadModel(this)
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        m.started.value = 7
        runCurrent()
        val outcome = pending.await()
        assertTrue(outcome!!.opened)
        assertNull(outcome.error)
    }

    @Test
    fun `a started signal from an older load is ignored`() = runTest {
        val m = LoadModel(this)
        m.started.value = 6
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        advanceTimeBy(20_000)
        runCurrent()
        assertNull(pending.await())
    }

    @Test
    fun `a retry load that succeeds opens`() = runTest {
        val m = LoadModel(this)
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        m.started.value = m.gen + 1
        runCurrent()
        val outcome = pending.await()
        assertTrue(outcome!!.opened)
        assertNull(outcome.error)
    }

    @Test
    fun `audio-only - playback-restart without a picture opens`() = runTest {
        val m = LoadModel(this)
        val pending = async { awaitMpvOutcomeOf(m.gen, m.started, m.error, 20_000) }
        runCurrent()
        m.load()
        assertNull(m.videoRes.value)
        m.started.value = 7
        runCurrent()
        val outcome = pending.await()
        assertTrue(outcome!!.opened)
        assertNull(outcome.error)
    }
}
