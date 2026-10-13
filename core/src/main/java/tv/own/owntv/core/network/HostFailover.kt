package tv.own.owntv.core.network

/**
 * One failover round over a source's pool (design §7): pure, no I/O, no clock. The caller runs the
 * release gate, the preflight and the open; this only decides which server comes next, how long to wait
 * first, and when the round is over.
 *
 * - A round starts on the in-use server and visits every server at most once, in list order.
 * - Backoff before each new server: 0 s, 1 s, 2 s, then 4 s (cap). Waits go *between* hosts, after
 *   everything has been stopped, so they never overlap two connections.
 * - When the last server fails the round is [Step.Exhausted] and nothing retries automatically until
 *   the user presses Retry or tunes again ([reset]).
 */
class HostFailover(private val pool: List<String>, start: String) {

    sealed interface Step {
        /** Try [host] (1-based [position] of [total]) after waiting [delayMs]. */
        data class Try(val host: String, val delayMs: Long, val position: Int, val total: Int) : Step

        /** Every server failed in this round. */
        data class Exhausted(val total: Int) : Step
    }

    private val visited = LinkedHashSet<String>()
    private var current: String = HostPool.origin(start) ?: start
    private var hops = 0

    init {
        visited += current
    }

    /** The server being tried now. */
    val host: String get() = current

    /** Servers tried so far in this round, including the current one. */
    val attempts: Int get() = visited.size

    val exhausted: Boolean get() = visited.size >= pool.size

    /**
     * [failed] did not answer: the next unvisited server in list order after it, or [Step.Exhausted].
     * A report for a server other than the current one (a stale callback from an attempt already
     * replaced) changes nothing and repeats the current step.
     */
    fun onHostFailure(failed: String): Step {
        val f = HostPool.origin(failed) ?: failed
        if (f != current) return Step.Try(current, 0, HostPool.position(pool, current), pool.size)
        val start = pool.indexOf(current).coerceAtLeast(0)
        for (i in 1 until pool.size) {
            val candidate = pool[(start + i) % pool.size]
            if (candidate in visited) continue
            visited += candidate
            current = candidate
            val delay = BACKOFF_MS[hops.coerceAtMost(BACKOFF_MS.lastIndex)]
            hops++
            return Step.Try(candidate, delay, HostPool.position(pool, candidate), pool.size)
        }
        return Step.Exhausted(pool.size)
    }

    /** A fresh round from [start] (Retry, or a new tune). */
    fun reset(start: String) {
        visited.clear()
        current = HostPool.origin(start) ?: start
        visited += current
        hops = 0
    }

    companion object {
        val BACKOFF_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L)

        /** How long a post-switch session-limit answer is waited out on the same server (R4). */
        const val SESSION_WAIT_MS = 45_000L

        /** A server that sent no response headers within this counts as down (A5 per-host budget). */
        const val HOST_BUDGET_MS = 12_000L

        /** Live per-server cap C when "Give up after" is off. */
        const val SERVER_CAP_MS = 15_000L

        /** Floor for [capMs]: a silent server still gets a few seconds. */
        const val MIN_SERVER_CAP_MS = 5_000L

        /**
         * Live per-server cap C. C ≤ G/2 so a silent server switches before "Give up after".
         * G off (≤ 0) uses [SERVER_CAP_MS].
         */
        fun capMs(giveUpSecs: Int): Long =
            if (giveUpSecs <= 0) SERVER_CAP_MS else (giveUpSecs * 1000L / 2).coerceIn(MIN_SERVER_CAP_MS, SERVER_CAP_MS)
    }
}
