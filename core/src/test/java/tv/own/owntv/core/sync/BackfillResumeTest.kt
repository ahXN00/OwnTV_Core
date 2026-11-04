package tv.own.owntv.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.live.WatchSession

@OptIn(ExperimentalCoroutinesApi::class)
class BackfillResumeTest {

    @Test
    fun `a playlist that stops playing resumes its drain, one that keeps playing does not`() = runTest {
        val session = WatchSession()
        val resumed = mutableListOf<Long>()
        BackfillResume(session, { resumed += it }, backgroundScope)
        runCurrent()

        session.open(1)
        session.open(2)
        runCurrent()
        assertEquals(emptyList<Long>(), resumed)

        session.close(1)
        runCurrent()
        assertEquals(listOf(1L), resumed)

        session.closeAll()
        runCurrent()
        assertEquals(listOf(1L, 2L), resumed)
    }

    @Test
    fun `a failing resume does not stop the next one`() = runTest {
        val session = WatchSession()
        val resumed = mutableListOf<Long>()
        BackfillResume(session, { if (it == 1L) error("WorkManager unavailable") else resumed += it }, backgroundScope)
        runCurrent()

        session.open(1)
        runCurrent()
        session.close(1)
        runCurrent()
        session.open(2)
        runCurrent()
        session.close(2)
        runCurrent()
        assertEquals(listOf(2L), resumed)
    }
}
