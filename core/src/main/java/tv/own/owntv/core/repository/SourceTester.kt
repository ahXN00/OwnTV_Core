package tv.own.owntv.core.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.stalkerCredentials
import tv.own.owntv.core.stalker.stalkerExpiryOf

/** What a "Test source" run found out. */
sealed interface SourceTestResult {
    /**
     * The host answered and accepted the credentials. Every field below is optional because the three
     * source types report wildly different amounts: an Xtream panel usually gives all of it, a plain
     * M3U link gives none of it (reaching the playlist at all is the whole answer).
     */
    data class Ok(
        /** Subscription end, or null when the provider reports none/unlimited/nothing. */
        val expiryMs: Long? = null,
        /**
         * Subscription end as the provider wrote it, for portals that give a date rather than a
         * timestamp. Shown verbatim — re-parsing a portal's own wording invents wrong dates — and
         * preferred over [expiryMs] when both somehow exist.
         */
        val expiryText: String? = null,
        /** The provider's own status word ("Active", "Expired", …) — shown as-is, never translated. */
        val status: String? = null,
        val trial: Boolean = false,
        /** Streams open right now; -1 when the provider doesn't say. */
        val activeConnections: Int = -1,
        /** Streams the account may open at once; 0 when the provider doesn't say. */
        val maxConnections: Int = 0,
    ) : SourceTestResult

    /** The host answered and rejected the credentials (bad login, MAC not authorised, 401/403). */
    data object AuthFailed : SourceTestResult

    /** The subscription itself has run out — the host is fine, the line is not. */
    data class Expired(val expiryMs: Long?) : SourceTestResult

    /** The host could not be reached at all: DNS, timeout, TLS, 5xx. [detail] is technical, for the log line. */
    data class Unreachable(val detail: String?) : SourceTestResult
}

/** One server's line in Test all servers (design §9). */
data class HostTestRow(
    /** The server, an origin from the pool. */
    val host: String,
    /** 1-based place in the list (the primary is 1). */
    val position: Int,
    val status: HostTestStatus,
    /** Time to the panel's answer, for [HostTestStatus.OK] only. */
    val ms: Long? = null,
)

enum class HostTestStatus {
    OK,
    UNREACHABLE,

    /**
     * Our own network, proxy or DoH failed. Says nothing about the server.
     * Never picked by Use fastest.
     */
    NETWORK_PROBLEM,

    /** A 200 that isn't a panel (HTML, parked domain): never picked by Use fastest. */
    INVALID_RESPONSE,

    /** A Cloudflare challenge: never picked. */
    BLOCKED,
    AUTH_FAILED,
    EXPIRED,

    /** Skip pressed before this server's turn. */
    NOT_TESTED,
    ;

    /** Only a server that answered as a panel may be chosen (OK on a row, Use fastest). */
    val choosable: Boolean get() = this == OK
}

/**
 * One-shot reachability + subscription check for a saved source, behind the Test button on the
 * playlist row.
 *
 * Deliberately read-only and side-effect free: it opens no session that outlives the call, writes
 * nothing to the database and does not touch the catalog. Testing a playlist can therefore never
 * disturb what is already synced.
 *
 * It also **costs a connection slot on Xtream** for the instant the call runs, which is why the
 * result reports `active_cons` — a user who is told "2 of 2 in use" is usually being told why their
 * next channel won't open.
 */
class SourceTester(
    private val http: HttpClient,
    private val xtreamClient: XtreamClient,
    private val stalkerAuth: StalkerAuthManager,
    private val hostSelector: HostSelector? = null,
    private val isOnline: () -> Boolean = { true },
) {

    /**
     * Test all servers (design §9): every server of [source]'s pool, **one request at a time**, in list
     * order, each bounded at [HostPreflight.TIMEOUT_MS]. The caller must already have stopped playback
     * through the release gate. Holds [HostSelector.probeLock] so a connection measurement can't run
     * beside it; returns null without testing anything when one is running. [skip] stops the run before
     * the next server and keeps the rows so far (U3); the rest come back [HostTestStatus.NOT_TESTED].
     */
    suspend fun testAll(
        source: SourceEntity,
        skip: () -> Boolean = { false },
        onProgress: (position: Int, total: Int, host: String) -> Unit = { _, _, _ -> },
    ): List<HostTestRow>? = withContext(Dispatchers.IO) {
        val pool = HostPool.pool(source)
        if (pool.isEmpty()) return@withContext emptyList()
        val lock = hostSelector?.probeLock
        if (lock != null && !lock.tryLock()) return@withContext null
        try {
            runSequential(pool, skip, onProgress) { host -> probeHost(source, host) }.also { rows ->
                Log.i(TAG, "testAll sourceId=${source.id} " + rows.joinToString(" ") { "${it.position}/${pool.size}=${it.status}" })
            }
        } finally {
            lock?.unlock()
        }
    }

    private suspend fun probeHost(source: SourceEntity, host: String): Pair<HostTestStatus, Long?> {
        if (!isOnline()) return HostTestStatus.NETWORK_PROBLEM to null
        return when (val r = HostPreflight.run(xtreamClient, source, host)) {
            is HostPreflight.Result.Ok -> HostTestStatus.OK to r.ms
            // Every stream in use is still a panel answering for this account.
            is HostPreflight.Result.Busy -> HostTestStatus.OK to r.ms.takeIf { it >= 0 }
            // Dropped mid-call: still our network, not this host.
            is HostPreflight.Result.Down ->
                (if (!isOnline()) HostTestStatus.NETWORK_PROBLEM else HostTestStatus.UNREACHABLE) to null
            // Proxy or DoH failed, not the server. Nothing is persisted from it.
            is HostPreflight.Result.LocalPath -> HostTestStatus.NETWORK_PROBLEM to null
            HostPreflight.Result.Invalid -> HostTestStatus.INVALID_RESPONSE to null
            HostPreflight.Result.Blocked -> HostTestStatus.BLOCKED to null
            is HostPreflight.Result.AccountEnded ->
                (if (r.reason == HostPreflight.Reason.EXPIRED) HostTestStatus.EXPIRED else HostTestStatus.AUTH_FAILED) to null
            // A 403/404 and the like: the server answered, but not as a usable panel for this line.
            is HostPreflight.Result.Other -> HostTestStatus.AUTH_FAILED to null
        }
    }

    suspend fun test(source: SourceEntity): SourceTestResult = withContext(Dispatchers.IO) {
        val result = when (source.type) {
            SourceType.XTREAM -> testXtream(source)
            SourceType.M3U -> testM3u(source)
            SourceType.STALKER -> testStalker(source)
            // A restored backup has no host to reach — nothing to test, and nothing is wrong.
            SourceType.LOCAL_BACKUP -> SourceTestResult.Ok()
        }
        Log.i(TAG, "test sourceId=${source.id} type=${source.type} -> ${result::class.simpleName}")
        result
    }

    private suspend fun testXtream(source: SourceEntity): SourceTestResult = try {
        val info = xtreamClient.fetchAccountStatus(source)
        val expiry = info.expiryMs
        val expired = info.status?.equals("Expired", ignoreCase = true) == true ||
            (expiry != null && expiry <= System.currentTimeMillis())
        when {
            // R2: a 200 that isn't a panel (a parked domain's page) used to read as "Connection OK".
            info.reply == XtreamClient.AccountReply.NOT_JSON -> SourceTestResult.Unreachable("not a panel reply")
            !info.authOk -> SourceTestResult.AuthFailed
            expired -> SourceTestResult.Expired(info.expiryMs)
            else -> SourceTestResult.Ok(
                expiryMs = info.expiryMs,
                status = info.status,
                trial = info.trial,
                activeConnections = info.activeConnections,
                maxConnections = info.maxConnections,
            )
        }
    } catch (e: Exception) {
        classify(e)
    }

    /**
     * A plain M3U has no account API, so the test is "does the link still serve a playlist": fetch the
     * first bytes and check for the `#EXTM3U` header. Reading the whole file would download hundreds of
     * megabytes to answer a yes/no question.
     */
    private suspend fun testM3u(source: SourceEntity): SourceTestResult {
        // A local-file playlist has no host; if the sync could read it, there is nothing to reach.
        if (!source.url.startsWith("http", ignoreCase = true)) return SourceTestResult.Ok()
        return try {
            val head = http.get(source.url, source.userAgent) { input ->
                val buf = ByteArray(PLAYLIST_PROBE_BYTES)
                val read = input.read(buf).coerceAtLeast(0)
                String(buf, 0, read, Charsets.UTF_8)
            }
            // Some providers answer an expired line with an HTML error page instead of a status code.
            // U+FEFF is the byte-order mark, written as an escape: a literal one in the source is
            // both invisible to read and a lint error (ByteOrderMark).
            if (head.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U", ignoreCase = true)) {
                SourceTestResult.Ok()
            } else {
                SourceTestResult.AuthFailed
            }
        } catch (e: Exception) {
            classify(e)
        }
    }

    private suspend fun testStalker(source: SourceEntity): SourceTestResult {
        val mac = StalkerClient.canonicalizeMac(source.mac.orEmpty())
            ?: return SourceTestResult.AuthFailed
        return try {
            val creds = source.stalkerCredentials(mac)
            val session = stalkerAuth.testConnection(creds)
            val profile = session.profile
            // The expiry is worth a second request: portals keep it in `account_info`, not in the
            // profile, and the test said "no expiry date reported" for a portal that reports one.
            val account = stalkerAuth.accountInfo(session, creds)
            SourceTestResult.Ok(
                // A portal's `status` is often the flag `1`, and "Status: 1" tells the user nothing —
                // it reads as a fault code. Only a word is worth showing; "Connection OK" above has
                // already said the rest.
                status = profile["status"]?.takeIf { it.isNotBlank() && it.toIntOrNull() == null },
                expiryText = stalkerExpiryOf(account) ?: stalkerExpiryOf(profile),
                // Portals scatter the same idea across several keys; the first one that looks real wins.
                // Many report none at all, which is what the playlist's own Maximum connections is for.
                maxConnections = profile.firstIntOf("max_online", "num_of_devices", "max_connections"),
            )
        } catch (e: Exception) {
            if (e is StalkerClient.StalkerAuthException) SourceTestResult.AuthFailed else classify(e)
        }
    }

    private fun classify(e: Exception): SourceTestResult = when {
        // A Cloudflare challenge is not a login problem; the one-server test reports it as unreachable
        // rather than telling the user their password is wrong. Test all names it "Blocked".
        e is HttpClient.HttpStatusException && e.cloudflareChallenge -> SourceTestResult.Unreachable(e.message)
        // The host answered — it just said no. That is an account answer, not a network one.
        e is HttpClient.HttpStatusException && (e.code == 401 || e.code == 403) -> SourceTestResult.AuthFailed
        // Same answer from a portal. 403 is a throttle while *crawling* a portal (see
        // `StalkerClient.httpFailure`), but the one request this test makes cannot be throttling
        // anybody — here it means the portal refused this MAC, which is what the user needs told.
        e is StalkerClient.StalkerHttpException && (e.code == 401 || e.code == 403) -> SourceTestResult.AuthFailed
        else -> SourceTestResult.Unreachable(e.message)
    }

    private fun Map<String, String>.firstIntOf(vararg keys: String): Int =
        keys.firstNotNullOfOrNull { this[it]?.trim()?.toIntOrNull()?.takeIf { n -> n > 0 } } ?: 0

    internal companion object {
        /**
         * The loop behind [testAll], apart so the "strictly one in flight" rule can be tested with a fake
         * probe: each [probe] call is awaited before the next one starts.
         */
        internal suspend fun runSequential(
            pool: List<String>,
            skip: () -> Boolean,
            onProgress: (Int, Int, String) -> Unit,
            probe: suspend (String) -> Pair<HostTestStatus, Long?>,
        ): List<HostTestRow> {
            val rows = ArrayList<HostTestRow>(pool.size)
            for ((i, host) in pool.withIndex()) {
                if (skip()) {
                    pool.drop(i).forEachIndexed { j, h -> rows += HostTestRow(h, i + j + 1, HostTestStatus.NOT_TESTED) }
                    break
                }
                onProgress(i + 1, pool.size, host)
                val (status, ms) = probe(host)
                rows += HostTestRow(host, i + 1, status, ms.takeIf { status == HostTestStatus.OK })
            }
            return rows
        }

        /** The fastest choosable row, or null when none answered. */
        fun fastest(rows: List<HostTestRow>): HostTestRow? =
            rows.filter { it.status.choosable && it.ms != null }.minByOrNull { it.ms!! }

        const val TAG = "SourceTester"
        const val PLAYLIST_PROBE_BYTES = 1024
    }
}
