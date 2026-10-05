package tv.own.owntv.player

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deadline for each live request's response headers: a network interceptor, so every hop of a redirect
 * (the panel, then the media server it picked) gets its own [deadlineMs].
 *
 * The case it exists for: a media server that accepts the connection and the request, then never
 * answers. Nothing else bounds that wait short of the read timeout (20 s), which must stay long because
 * a live TS body can legitimately pause and resume. Measured: a panel's redirect landed on a server that
 * sent nothing for 10.8 s after an outage, until the next reconnect went elsewhere; previews of another
 * channel hit silent servers of the same group three times in an afternoon, none of which ever answered.
 *
 * On a miss the call is cancelled and fails as a timeout, which Media3 retries like any connection
 * failure. Once the headers are in, the deadline is gone — the body keeps the read timeout.
 */
class ResponseDeadline(
    private val deadlineMs: Long,
    /** A request ran out of time, with its host. Called on the scheduler thread. */
    private val onMissed: (host: String) -> Unit,
    /** A request was answered in time, other than by a redirect: a panel's 302 always answers before a
     *  silent server behind it, so counting it would hide that server. Called on the request's thread. */
    private val onAnswered: () -> Unit,
    private val scheduler: ScheduledExecutorService = SCHEDULER,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        // WAITING → ANSWERED or MISSED, exactly once: a deadline that fires as the headers arrive must not
        // cancel a stream that has already started.
        val state = AtomicInteger(WAITING)
        val timer = scheduler.schedule({
            if (state.compareAndSet(WAITING, MISSED)) call.cancel()
        }, deadlineMs, TimeUnit.MILLISECONDS)
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            timer.cancel(false)
            if (state.get() == MISSED) throw missed(chain, e)
            throw e
        }
        timer.cancel(false)
        if (!state.compareAndSet(WAITING, ANSWERED)) {
            response.close()
            throw missed(chain, null)
        }
        if (!response.isRedirect) onAnswered()
        return response
    }

    private fun missed(chain: Interceptor.Chain, cause: IOException?): IOException {
        onMissed(chain.request().url.host)
        return SocketTimeoutException("no response headers within ${deadlineMs}ms").apply { cause?.let { initCause(it) } }
    }

    private companion object {
        const val WAITING = 0
        const val ANSWERED = 1
        const val MISSED = 2

        val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "owntv-response-deadline").apply { isDaemon = true }
        }
    }
}
