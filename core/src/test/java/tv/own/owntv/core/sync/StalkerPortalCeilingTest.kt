package tv.own.owntv.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.stalker.StalkerClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the crawl behaves on a portal that refuses parallel requests, without slowing down one that
 * does not: a refused limit is never climbed back to in the same run, it is remembered for the next
 * run, and a run that stays clean at the cap earns it back one step at a time.
 */
class StalkerPortalCeilingTest {

    private fun http(code: Int) = StalkerClient.StalkerHttpException(code, "HTTP $code")

    private suspend fun succeed(gate: AdaptivePortalLimiter, times: Int) = repeat(times) { gate.withPermit { } }

    // ---- one run ----

    @Test
    fun aRefusedLimitIsNeverClimbedBackToInTheSameRun() = runBlocking {
        val gate = AdaptivePortalLimiter(start = 8, isThrottle = StalkerSyncer::isPortalThrottle)
        runCatching { gate.withPermit { throw http(429) } }
        assertEquals(4, gate.currentLimit)
        succeed(gate, 2_000)
        assertEquals("grows back up to just below the limit that was refused", 7, gate.currentLimit)
        assertEquals(8, gate.throttledAt)
    }

    @Test
    fun aPortalThatNeverRefusesStillReachesTheMaximum() = runBlocking {
        val gate = AdaptivePortalLimiter(isThrottle = StalkerSyncer::isPortalThrottle)
        succeed(gate, 2_000)
        assertEquals(AdaptivePortalLimiter.DEFAULT_MAX, gate.currentLimit)
        assertTrue(gate.reachedMax)
        assertNull(gate.throttledAt)
    }

    @Test
    fun oneBurstOfRefusalsHalvesOnce() = runBlocking {
        val gate = AdaptivePortalLimiter(start = 8, isThrottle = StalkerSyncer::isPortalThrottle)
        val allIn = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        // Eight requests sent at the same limit, all refused: one answer, not three halvings.
        withContext(Dispatchers.Default) {
            (1..8).map {
                async {
                    runCatching {
                        gate.withPermit {
                            if (entered.incrementAndGet() == 8) allIn.complete(Unit)
                            allIn.await()
                            throw http(429)
                        }
                    }
                }
            }.awaitAll()
        }
        assertEquals(4, gate.currentLimit)
    }

    @Test
    fun aGateStartsBelowTheLearnedCeiling() {
        val learned = AdaptivePortalLimiter.forCeiling(4, StalkerSyncer::isPortalThrottle)
        assertEquals(3, learned.max)
        assertEquals(3, learned.currentLimit)
        val strict = AdaptivePortalLimiter.forCeiling(1, StalkerSyncer::isPortalThrottle)
        assertEquals("a portal that refused two at once is asked one at a time", 1, strict.max)
        val fresh = AdaptivePortalLimiter.forCeiling(null, StalkerSyncer::isPortalThrottle)
        assertEquals(AdaptivePortalLimiter.DEFAULT_MAX, fresh.max)
        assertEquals(AdaptivePortalLimiter.DEFAULT_START, fresh.currentLimit)
    }

    // ---- across runs ----

    @Test
    fun aRefusalIsStored() {
        assertEquals(LearnedCeiling(6, 0), nextCeiling(null, throttledAt = 6, reachedMax = false))
        assertEquals(LearnedCeiling(3, 0), nextCeiling(LearnedCeiling(6, 1), throttledAt = 3, reachedMax = true))
    }

    @Test
    fun aPortalThatNeverRefusedStoresNothing() {
        assertNull(nextCeiling(null, throttledAt = null, reachedMax = true))
    }

    @Test
    fun cleanRunsAtTheCapRaiseItOneStepAtATime() {
        var stored: LearnedCeiling? = LearnedCeiling(5, 0)
        stored = nextCeiling(stored, null, reachedMax = true)
        assertEquals(LearnedCeiling(5, 1), stored)
        stored = nextCeiling(stored, null, reachedMax = true)
        assertEquals(LearnedCeiling(6, 0), stored)
        assertEquals("a run that never reached the cap proves nothing", stored, nextCeiling(stored, null, reachedMax = false))
    }

    @Test
    fun aCeilingAboveTheMaximumIsForgotten() {
        assertNull(nextCeiling(LearnedCeiling(8, CLEAN_RUNS_BEFORE_RAISE - 1), null, reachedMax = true))
    }

    // ---- backoff ----

    @Test
    fun backoffIsThreeSixTwelveSecondsWithJitter() {
        assertEquals(2_400, StalkerSyncer.backoffMs(1, null, 0.0))
        assertEquals(3_600, StalkerSyncer.backoffMs(1, null, 1.0))
        assertEquals(6_000, StalkerSyncer.backoffMs(2, null, 0.5))
        assertEquals(12_000, StalkerSyncer.backoffMs(3, null, 0.5))
    }

    @Test
    fun theWaitThePortalAsksForWins() {
        assertEquals(20_000, StalkerSyncer.backoffMs(1, 20_000, 0.5))
        assertEquals("never longer than a minute", 60_000, StalkerSyncer.backoffMs(1, 600_000, 0.5))
    }

    // ---- a strict portal end to end ----

    /**
     * A portal that refuses anything above three requests at once. Every page must still arrive —
     * through the same shape the sync uses, retries outside the gate — and the gate must end below
     * the portal's limit having learned it.
     */
    @Test
    fun aStrictPortalLosesNoPage() = runBlocking {
        val portalLimit = 3
        val gate = AdaptivePortalLimiter(isThrottle = StalkerSyncer::isPortalThrottle)
        val inFlight = AtomicInteger()
        val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        suspend fun fetch(page: Int) {
            var attempt = 1
            while (true) {
                try {
                    gate.withPermit {
                        try {
                            if (inFlight.incrementAndGet() > portalLimit) throw http(429)
                            delay(1)
                            delivered.add(page)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                    return
                } catch (e: IOException) {
                    if (!StalkerSyncer.isTransientPortalError(e) || attempt++ > 50) throw e
                    delay(1)
                }
            }
        }
        withContext(Dispatchers.Default) { (1..300).map { p -> async { fetch(p) } }.awaitAll() }
        assertEquals(300, delivered.size)
        assertTrue("the gate learned it must stay under the portal's limit", gate.currentLimit <= portalLimit)
        assertTrue(gate.throttledAt != null)
        assertFalse(gate.reachedMax)
    }
}
