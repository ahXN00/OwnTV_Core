package tv.own.owntv.core.sports

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A fetch not made because ESPN asked for a pause (403/429) a moment ago. */
class SportsPausedException : IOException("paused")

/**
 * What every ESPN call shares, whichever screen makes it: at most [MAX_PARALLEL] at once, and the
 * 15-minute stop after a 403 or 429 ("Scores paused"). The board, the calendar and the team pages all
 * go through one of these, so a pause one of them earned stops the others too.
 */
class SportsGate(private val policy: SportsRefreshPolicy, private val clock: () -> Long = System::currentTimeMillis) {

    val permits = Semaphore(MAX_PARALLEL)

    private val until = AtomicLong(0L)

    /** When the current pause ends (0 or past: none). */
    val pausedUntil: Long get() = until.get()

    fun paused(): Boolean = pausedUntil > clock()

    /** A failed call answered [code]: start a pause if the code asks for one. */
    fun failed(code: Int?) {
        val end = code?.let { policy.pauseAfter(it, clock()) } ?: return
        until.accumulateAndGet(end, ::maxOf)
    }

    /** [call] under a permit; throws [SportsPausedException] during a pause, and starts one on 403/429. */
    suspend fun <T> call(call: suspend () -> T): T = permits.withPermit {
        if (paused()) throw SportsPausedException()
        try {
            call()
        } catch (e: SportsHttpException) {
            failed(e.code)
            throw e
        }
    }

    companion object {
        const val MAX_PARALLEL = 4
    }
}
