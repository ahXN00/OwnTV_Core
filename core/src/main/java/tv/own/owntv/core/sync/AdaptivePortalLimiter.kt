package tv.own.owntv.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AIMD (additive-increase / multiplicative-decrease) concurrency gate for portal page crawls.
 * Portals differ wildly in how many parallel requests they tolerate; a fixed pool either
 * under-uses tolerant portals or trips strict ones. This starts conservative, grows by one
 * permit per [growAfter] consecutive successes up to [max], and halves (never below [min])
 * whenever a request fails with a throttle signal ([isThrottle] — 429/5xx/timeouts).
 *
 * A limit that drew a throttle becomes this run's ceiling: the gate never grows back to it. Without
 * that, a strict portal was halved, climbed straight back to the limit that had just failed twenty
 * pages later, and was refused again — over and over for the whole crawl.
 *
 * One burst of refusals is learned from once. Requests already in flight when the first refusal
 * arrives were sent at the old limit, and halving again for each of them used to drop a gate at
 * eight straight to one.
 *
 * Callers wrap each raw request in [withPermit]; retries/backoff must live OUTSIDE it so a
 * backoff delay doesn't hold a slot and every throttled attempt is learned from.
 */
internal class AdaptivePortalLimiter(
    start: Int = DEFAULT_START,
    private val min: Int = DEFAULT_MIN,
    val max: Int = DEFAULT_MAX,
    private val growAfter: Int = DEFAULT_GROW_AFTER,
    private val isThrottle: (Throwable) -> Boolean,
) {
    private val mutex = Mutex()
    private var limit = start.coerceIn(min, max)
    private var inFlight = 0
    private var successStreak = 0
    private var generation = 0
    private var ceiling: Int? = null
    private var peak = limit
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** Snapshot for logging. */
    val currentLimit: Int get() = limit

    /** The lowest limit a throttle happened at in this run, or null when the portal never refused. */
    val throttledAt: Int? get() = ceiling

    /** Whether this run ever got all the way to [max]. */
    val reachedMax: Boolean get() = peak >= max

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val sentAt = acquire()
        try {
            val result = block()
            onSuccess()
            return result
        } catch (e: Throwable) {
            if (e !is CancellationException && isThrottle(e)) onThrottle(sentAt)
            throw e
        } finally {
            release()
        }
    }

    /** Takes a slot and returns the generation it was taken in. */
    private suspend fun acquire(): Int {
        while (true) {
            val waiter = mutex.withLock {
                if (inFlight < limit) {
                    inFlight++
                    return generation
                }
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
            try {
                waiter.await() // woken waiters re-check under the lock, so a spurious wake is safe
            } catch (c: CancellationException) {
                mutex.withLock { waiters.remove(waiter) }
                throw c
            }
        }
    }

    private suspend fun release() {
        mutex.withLock {
            inFlight--
            wakeLocked()
        }
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            val next = limit + 1
            if (++successStreak >= growAfter && limit < max && ceiling.let { it == null || next < it }) {
                limit = next
                if (limit > peak) peak = limit
                successStreak = 0
                wakeLocked()
            }
        }
    }

    private suspend fun onThrottle(sentAt: Int) {
        mutex.withLock {
            // Sent before the last back-off: already answered by it.
            if (sentAt != generation) return
            generation++
            successStreak = 0
            ceiling = minOf(ceiling ?: limit, limit)
            limit = (limit / 2).coerceAtLeast(min)
        }
    }

    private fun wakeLocked() {
        var free = limit - inFlight
        while (free-- > 0 && waiters.isNotEmpty()) waiters.removeFirst().complete(Unit)
    }

    companion object {
        /**
         * Deliberately timid. These were 6 / 2 / 16, chosen when a flush of the database stopped the
         * network dead and so paced the crawl for us; since that pause went away the same numbers put
         * a sustained sixteen requests on portals that cap a MAC at a handful and answer 403 above it.
         * A real set-top box asks for one page at a time, so starting at three and stopping at eight
         * is still generous — and [DEFAULT_MIN] of one lets a strict panel push us all the way down
         * to serial rather than leaving a floor it will keep refusing.
         */
        const val DEFAULT_START = 3
        const val DEFAULT_MIN = 1
        const val DEFAULT_MAX = 8
        /** Consecutive successful requests before adding one permit (~20 pages ≈ 300 items). */
        const val DEFAULT_GROW_AFTER = 20

        /** A gate for a portal whose stored ceiling is [learned] (null = never refused us). */
        fun forCeiling(learned: Int?, isThrottle: (Throwable) -> Boolean): AdaptivePortalLimiter {
            val max = learned?.let { (it - 1).coerceIn(DEFAULT_MIN, DEFAULT_MAX) } ?: DEFAULT_MAX
            return AdaptivePortalLimiter(start = minOf(DEFAULT_START, max), max = max, isThrottle = isThrottle)
        }
    }
}
