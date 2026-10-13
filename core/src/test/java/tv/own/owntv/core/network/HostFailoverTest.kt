package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC8 / A8: one round, list order, backoff 0/1/2/4 s, then EXHAUSTED with no automatic retry. */
class HostFailoverTest {

    private val pool = (1..6).map { "http://h$it.example.top" }

    @Test
    fun `a round walks the list once with backoff 0, 1, 2, then 4 s capped`() {
        val f = HostFailover(pool, pool[0])
        val steps = mutableListOf<HostFailover.Step>()
        var failed = pool[0]
        repeat(5) {
            val step = f.onHostFailure(failed) as HostFailover.Step.Try
            steps += step
            failed = step.host
        }
        assertEquals(pool.drop(1), steps.map { (it as HostFailover.Step.Try).host })
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 4_000L), steps.map { (it as HostFailover.Step.Try).delayMs })
        assertEquals(listOf(2, 3, 4, 5, 6), steps.map { (it as HostFailover.Step.Try).position })
        assertEquals(HostFailover.Step.Exhausted(6), f.onHostFailure(failed))
        assertTrue(f.exhausted)
        assertEquals(6, f.attempts)
        // Exhausted stays exhausted: nothing more is tried until a reset.
        assertEquals(HostFailover.Step.Exhausted(6), f.onHostFailure(failed))
    }

    @Test
    fun `a round that starts mid-list wraps and still visits each server once`() {
        val f = HostFailover(pool, pool[4])
        val hosts = mutableListOf<String>()
        var failed = pool[4]
        while (true) {
            val step = f.onHostFailure(failed)
            if (step is HostFailover.Step.Exhausted) break
            failed = (step as HostFailover.Step.Try).host
            hosts += failed
        }
        assertEquals(listOf(pool[5], pool[0], pool[1], pool[2], pool[3]), hosts)
    }

    @Test
    fun `a stale failure report for a replaced attempt changes nothing`() {
        val f = HostFailover(pool, pool[0])
        f.onHostFailure(pool[0])
        val again = f.onHostFailure(pool[0]) as HostFailover.Step.Try
        assertEquals(pool[1], again.host)
        assertEquals(0L, again.delayMs)
        assertEquals(2, f.attempts)
    }

    @Test
    fun `reset starts a fresh round from the given server`() {
        val f = HostFailover(pool.take(2), pool[0])
        f.onHostFailure(pool[0])
        assertEquals(HostFailover.Step.Exhausted(2), f.onHostFailure(pool[1]))
        f.reset(pool[1])
        assertEquals(pool[0], (f.onHostFailure(pool[1]) as HostFailover.Step.Try).host)
    }

    @Test
    fun `AC20 capMs`() {
        assertEquals(15_000L, HostFailover.capMs(0))
        assertEquals(5_000L, HostFailover.capMs(10))
        assertEquals(15_000L, HostFailover.capMs(30))
        assertEquals(15_000L, HostFailover.capMs(60))
        assertEquals(5_000L, HostFailover.capMs(4))
    }

    @Test
    fun `AC28 host budget unchanged`() {
        assertEquals(12_000L, HostFailover.HOST_BUDGET_MS)
    }
}
