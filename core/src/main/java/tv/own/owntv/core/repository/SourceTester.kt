package tv.own.owntv.core.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.stalkerCredentials

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
) {

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
            val profile = stalkerAuth.testConnection(source.stalkerCredentials(mac)).profile
            SourceTestResult.Ok(
                status = profile["status"],
                // Portals scatter the same idea across several keys; the first one that looks real wins.
                maxConnections = profile.firstIntOf("max_online", "num_of_devices", "max_connections"),
            )
        } catch (e: Exception) {
            if (e is StalkerClient.StalkerAuthException) SourceTestResult.AuthFailed else classify(e)
        }
    }

    private fun classify(e: Exception): SourceTestResult = when {
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

    private companion object {
        const val TAG = "SourceTester"
        const val PLAYLIST_PROBE_BYTES = 1024
    }
}
