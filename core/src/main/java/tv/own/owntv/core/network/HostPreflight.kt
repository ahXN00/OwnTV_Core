package tv.own.owntv.core.network

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.parser.XtreamClient

/**
 * The one `player_api.php` call made against a candidate server **before** a stream is opened on it
 * (R2), and the same check Test all servers runs per host. It runs only after the release gate, so it is
 * the one request in flight, and its response is fully read and closed before anything else opens.
 */
object HostPreflight {

    /** Per-host bound for the call (design: 5-8 s). */
    const val TIMEOUT_MS = 8_000L

    sealed interface Result {
        /** A live panel answered for this account. [ms] = time to the answer. */
        data class Ok(val ms: Long, val details: XtreamClient.XtAccountDetails) : Result

        /** Dead before headers: DNS, refused, unreachable, connect/TLS failure, 5xx gateway, no answer in time. */
        data class Down(val detail: String?) : Result

        /** A 200 that isn't a panel reply (HTML, a parked domain, a captive page): host-class for this one. */
        data object Invalid : Result

        /** A Cloudflare challenge in front of the panel. Never cycles; shown as "Blocked". */
        data object Blocked : Result

        /** The panel said the account is unusable; the next server would say the same. Ends the round. */
        data class AccountEnded(val reason: Reason) : Result

        /** Every allowed stream is in use, typically our own just-closed session still counted: wait here. */
        data class Busy(val ms: Long, val active: Int, val max: Int) : Result

        /** Anything else (403, 404, 429…): not the server, not a reason to move. */
        data class Other(val detail: String?) : Result

        /** Our own proxy or DoH resolver failed, not the server: never counts against a host. */
        data class LocalPath(val kind: Kind) : Result {
            enum class Kind { PROXY, DOH }
        }
    }

    enum class Reason { AUTH, EXPIRED, EMPTY }

    /** Live proxy/DoH snapshot. Default is off, so a host name that merely contains "proxy" is not local. */
    data class LocalPathConfig(
        val proxyHost: String? = null,
        val proxyPort: Int = 0,
        val dohOn: Boolean = false,
    )

    @Volatile
    var localPath: () -> LocalPathConfig = { LocalPathConfig() }

    /** Whether this result moves a failover round on to the next server. */
    fun Result.isHostClass(): Boolean = this is Result.Down || this is Result.Invalid

    /** Reads the panel's reply (pure). */
    fun interpret(d: XtreamClient.XtAccountDetails, ms: Long, nowMs: Long = System.currentTimeMillis()): Result {
        if (d.reply == XtreamClient.AccountReply.NOT_JSON) return Result.Invalid
        if (d.reply == XtreamClient.AccountReply.EMPTY) return Result.AccountEnded(Reason.EMPTY)
        if (!d.authOk) return Result.AccountEnded(Reason.AUTH)
        val status = d.status?.lowercase()
        if (status == "expired" || status == "banned" || status == "disabled") return Result.AccountEnded(Reason.EXPIRED)
        if (d.expiryMs != null && d.expiryMs <= nowMs) return Result.AccountEnded(Reason.EXPIRED)
        if (d.maxConnections > 0 && d.activeConnections >= d.maxConnections) return Result.Busy(ms, d.activeConnections, d.maxConnections)
        return Result.Ok(ms, d)
    }

    /** Reads a failure (pure). */
    fun interpretFailure(e: Throwable, cfg: LocalPathConfig = localPath(), ms: Long = -1): Result {
        localPathKind(e, cfg)?.let { return Result.LocalPath(it) }
        if (e is HttpClient.HttpStatusException) {
            if (e.cloudflareChallenge) return Result.Blocked
            if (e.code == 401) return Result.AccountEnded(Reason.AUTH)
        }
        return when (HostFailure.classify(e, beforeHeaders = e is TimeoutCancellationException)) {
            HostFailureClass.HOST -> Result.Down(e.message)
            HostFailureClass.WAIT -> Result.Busy(ms, -1, -1)
            else -> if (e is TimeoutCancellationException) Result.Down("no answer in ${TIMEOUT_MS / 1000} s") else Result.Other(e.message)
        }
    }

    /** Proxy or DoH on our path, not the panel. Walks causes, max 8, stops on a self-cause. */
    internal fun localPathKind(e: Throwable, cfg: LocalPathConfig = localPath()): Result.LocalPath.Kind? {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            if (cfg.dohOn && cur is DohLookupException) return Result.LocalPath.Kind.DOH
            val msg = cur.message
            if (cfg.proxyHost != null && msg != null) {
                if ((cur is java.net.ProtocolException || cur is java.io.IOException) &&
                    msg.startsWith("Unexpected response code for CONNECT")
                ) return Result.LocalPath.Kind.PROXY
                if (cur is java.net.ConnectException && msg.contains(cfg.proxyHost)) return Result.LocalPath.Kind.PROXY
            }
            val next = cur.cause
            if (next === cur) break
            cur = next
            depth++
        }
        return null
    }

    internal fun isLocalPathFailure(e: Throwable, cfg: LocalPathConfig = localPath()): Boolean =
        localPathKind(e, cfg) != null

    /** One bounded call against [host] (an origin from [s]'s pool). */
    suspend fun run(
        xtream: XtreamClient,
        s: SourceEntity,
        host: String,
        timeoutMs: Long = TIMEOUT_MS,
        now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ): Result {
        val started = now()
        return try {
            val d = withTimeout(timeoutMs) { xtream.fetchAccountStatus(s, host) }
            interpret(d, now() - started)
        } catch (e: TimeoutCancellationException) {
            interpretFailure(e, ms = now() - started)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            interpretFailure(e, ms = now() - started)
        }
    }
}
