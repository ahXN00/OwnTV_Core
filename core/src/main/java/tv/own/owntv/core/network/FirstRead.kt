package tv.own.owntv.core.network

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Holds the network path until the stored proxy / DNS setting has been read once (S6).
 *
 * The holders start from "off" and learn the real value from DataStore's first emission. A cold start
 * with Last-channel autoplay could tune before that arrived, so the first stream went out directly: an
 * IP leak for someone using the proxy for privacy, and a failed tune for someone using it to reach the
 * provider at all. Only callers on network threads wait (OkHttp's proxy selector and DNS, mpv's load
 * on its own executor) — never the main thread — and only until the first read, [timeoutMs] at most.
 */
internal class FirstRead(private val name: String, open: Boolean = false) {

    private val latch = CountDownLatch(if (open) 0 else 1)

    fun arrived() = latch.countDown()

    /** Waits for the first read; on a timeout it gives up for good, so a stuck store costs one wait. */
    fun await(timeoutMs: Long = TIMEOUT_MS) {
        if (latch.count == 0L) return
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w("OwnTV-Network", "$name setting not read after ${timeoutMs}ms; going on without it")
            latch.countDown()
        }
    }

    companion object {
        /** DataStore's first read takes tens of milliseconds; this only bounds a store that never answers. */
        const val TIMEOUT_MS = 2_000L
    }
}
