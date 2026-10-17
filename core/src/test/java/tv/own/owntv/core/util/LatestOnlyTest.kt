package tv.own.owntv.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Progress updates reach their one consumer in order, the newest replacing any still waiting. */
class LatestOnlyTest {

    @Test
    fun valuesOfferedWhileTheConsumerIsBusyCollapseToTheNewest() {
        val consumed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val latest = LatestOnly<Int>(this) { consumed += it; if (it == 1) gate.await() }
                latest.offer(1)
                yield() // the consumer takes 1 and waits at the gate
                latest.offer(2)
                latest.offer(3)
                latest.offer(4)
                gate.complete(Unit)
                latest.close()
            }
        }
        assertEquals(listOf(1, 4), consumed)
    }

    @Test
    fun oneValueIsConsumedAtATime() {
        var running = 0
        var most = 0
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val latest = LatestOnly<Int>(this) { running++; most = maxOf(most, running); yield(); running-- }
                repeat(5) { latest.offer(it); yield() }
                latest.close()
            }
        }
        assertEquals(1, most)
    }

    @Test
    fun closingEndsTheConsumerSoItsScopeCanFinish() {
        val consumed = mutableListOf<Int>()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val latest = LatestOnly<Int>(this) { consumed += it }
                latest.close()
                latest.offer(9)
            }
        }
        assertEquals(emptyList<Int>(), consumed)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
