package tv.own.owntv.player

import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.HostFailover
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight

/**
 * The part of a failover round live and VOD share (design §7): after the release gate has run, walk
 * [round] from [failed] — backoff, one preflight, wait out a busy account on the same server (R4) —
 * until a server may be opened, the round is spent, or the account itself is refused. An offline device
 * pauses the round and spends no host. A local proxy or DoH failure refuses the round the same way.
 * Strictly one request at a time; nothing here opens a stream.
 */
internal object ServerRound {

    sealed interface Next {
        /** Open the stream on [server] ([position] of [total]). */
        data class Open(val server: String, val position: Int, val total: Int) : Next
        data class Exhausted(val total: Int) : Next
        /** The panel refused the account, or a challenge stands in front of it: the next server says the same. */
        data class Refused(val detail: String) : Next
        /** The caller's item changed while waiting. */
        data object Superseded : Next
    }

    /** How often a busy account is asked again while its old session expires (R4). */
    const val SESSION_RECHECK_MS = 5_000L

    suspend fun next(
        round: HostFailover,
        source: SourceEntity,
        failed: String,
        preflight: suspend (SourceEntity, String) -> HostPreflight.Result,
        stillCurrent: () -> Boolean,
        log: (String) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        /** False while the device has no path out: pause, do not spend a host. */
        isOnline: () -> Boolean = { true },
        /** Suspends until [isOnline] is true again. */
        awaitOnline: suspend () -> Unit = {},
        /** Called once when the round pauses because the device is offline. */
        onOffline: () -> Unit = {},
    ): Next {
        val pool = HostPool.pool(source)
        var lastFailed = failed
        suspend fun waitIfOffline(): Boolean {
            if (isOnline()) return true
            log("offline: round paused, no server used")
            onOffline()
            awaitOnline()
            return stillCurrent()
        }
        if (!isOnline()) {
            if (!waitIfOffline()) return Next.Superseded
            val check = preflight(source, failed)
            log("${HostPool.describe(pool, failed)}: ${check::class.simpleName}")
            if (check is HostPreflight.Result.Ok || check is HostPreflight.Result.Busy) {
                return Next.Open(failed, HostPool.position(pool, failed), pool.size)
            }
        }
        while (true) {
            val step = round.onHostFailure(lastFailed)
            if (step is HostFailover.Step.Exhausted) return Next.Exhausted(step.total)
            step as HostFailover.Step.Try
            if (step.delayMs > 0) pause(step.delayMs)
            if (!stillCurrent()) return Next.Superseded
            if (!waitIfOffline()) return Next.Superseded
            var offlineRetries = 0
            var check: HostPreflight.Result
            while (true) {
                check = preflight(source, step.host)
                log("${HostPool.describe(pool, step.host)}: ${check::class.simpleName}")
                var waited = 0L
                while (check is HostPreflight.Result.Busy && waited < HostFailover.SESSION_WAIT_MS) {
                    pause(SESSION_RECHECK_MS)
                    waited += SESSION_RECHECK_MS
                    if (!stillCurrent()) return Next.Superseded
                    if (!waitIfOffline()) return Next.Superseded
                    check = preflight(source, step.host)
                }
                if (check is HostPreflight.Result.Down && !isOnline() && offlineRetries < 3) {
                    offlineRetries++
                    log("offline after a failed check: not counting it")
                    if (!waitIfOffline()) return Next.Superseded
                    continue
                }
                break
            }
            when (check) {
                is HostPreflight.Result.Down, HostPreflight.Result.Invalid -> lastFailed = step.host
                is HostPreflight.Result.AccountEnded -> return Next.Refused("the provider ended the account (${check.reason.name.lowercase()})")
                HostPreflight.Result.Blocked -> return Next.Refused("the provider's server is behind a challenge page")
                is HostPreflight.Result.LocalPath -> return Next.Refused(
                    if (check.kind == HostPreflight.Result.LocalPath.Kind.PROXY) "the proxy failed"
                    else "the DNS-over-HTTPS resolver failed",
                )
                // OK, still busy after the wait, or an answer that isn't the server's (403/404/429): open there.
                else -> return Next.Open(step.host, step.position, step.total)
            }
        }
    }
}
