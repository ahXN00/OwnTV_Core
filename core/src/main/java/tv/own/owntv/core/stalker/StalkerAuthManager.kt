package tv.own.owntv.core.stalker

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * What a Stalker call needs to authenticate (plan §5.2). Phase B maps a `SourceEntity` to this
 * (portal URL from `url`, MAC from the new `mac` column, per-source User-Agent override) — keeping
 * this type free of DB dependencies lets Phase A compile and be tested standalone.
 */
data class StalkerDeviceIdentity(
    val serialNumber: String? = null,
    val deviceId: String? = null,
    val deviceId2: String? = null,
    val signature: String? = null,
) {
    val hasAny: Boolean
        get() = !serialNumber.isNullOrBlank() || !deviceId.isNullOrBlank() ||
            !deviceId2.isNullOrBlank() || !signature.isNullOrBlank()
}

data class StalkerCredentials(
    val sourceId: Long,
    val portalUrl: String,
    /** Canonical `AA:BB:CC:DD:EE:FF` (see [StalkerClient.canonicalizeMac]). */
    val mac: String,
    val userAgent: String? = null,
    val deviceIdentity: StalkerDeviceIdentity = StalkerDeviceIdentity(),
)

/** One live portal session. Tokens live in memory only — never persisted (plan key decision #3). */
data class StalkerSession(
    val apiBase: String,
    val token: String,
    val expiresAtMs: Long,
    /** Scalar fields of `get_profile` (subscription/watchdog details land here). */
    val profile: Map<String, String>,
) {
    val isExpired: Boolean get() = SystemClock.elapsedRealtime() >= expiresAtMs
}

/**
 * Owns the in-memory Stalker sessions, one per source (plan §5.2): [sessionFor] handshakes (probing
 * the API-endpoint candidates on first contact) and caches the token with a conservative TTL;
 * [withAuthRetry] re-handshakes ONCE when a call reports the token died mid-session
 * ([StalkerClient.StalkerAuthException] — portals also signal this as an empty `{"js":false}` payload).
 */
open class StalkerAuthManager(private val client: StalkerClient) {

    private val sessions = ConcurrentHashMap<Long, StalkerSession>()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** Cached session if still fresh, else a full handshake → get_profile round trip. */
    suspend fun sessionFor(creds: StalkerCredentials): StalkerSession {
        sessions[creds.sourceId]?.takeIf { !it.isExpired }?.let { return it }
        return locks.getOrPut(creds.sourceId) { Mutex() }.withLock {
            // Re-check under the lock — a concurrent caller may have just handshaken.
            sessions[creds.sourceId]?.takeIf { !it.isExpired }?.let { return it }
            openSession(creds).also { sessions[creds.sourceId] = it }
        }
    }

    /** Drop the cached session so the next call re-handshakes (used on auth failure & source edit/delete). */
    fun invalidate(sourceId: Long) {
        sessions.remove(sourceId)
    }

    /**
     * Drop [dead] only if it is still the session everyone else is using. A sync has several requests
     * in flight at once, so one expired token produces a *burst* of auth failures that all arrive
     * after the first of them has already handshaken a replacement. An unconditional [invalidate]
     * then throws that fresh session away once per straggler, and each straggler handshakes another
     * one — a handshake storm against a portal that is usually already unhappy.
     */
    private fun invalidateIfCurrent(sourceId: Long, dead: StalkerSession) {
        sessions.remove(sourceId, dead)
    }

    /**
     * Run [block] with a valid session; on an auth failure invalidate and retry ONCE with a fresh
     * handshake (§5.2 `withAuthRetry`). Anything failing twice is a real error for the caller.
     */
    open suspend fun <T> withAuthRetry(creds: StalkerCredentials, block: suspend (StalkerSession) -> T): T {
        val session = sessionFor(creds)
        return try {
            block(session)
        } catch (e: StalkerClient.StalkerAuthException) {
            Log.i(TAG, "auth expired sourceId=${creds.sourceId} (${e.message}) — re-handshaking once")
            invalidateIfCurrent(creds.sourceId, session)
            block(sessionFor(creds))
        }
    }

    /**
     * "Test connection" for the add-source form (and the Phase A spike): always performs a FRESH
     * handshake + get_profile (ignoring any cache) and caches the result on success.
     */
    suspend fun testConnection(creds: StalkerCredentials): StalkerSession {
        invalidate(creds.sourceId)
        return sessionFor(creds)
    }

    private suspend fun openSession(creds: StalkerCredentials): StalkerSession {
        val startedAt = SystemClock.elapsedRealtime()
        val handshake = client.resolveHandshake(creds.portalUrl, creds.mac, creds.userAgent)
        val profile = client.getProfile(
            handshake.apiBase, creds.mac, handshake.token, creds.userAgent, creds.deviceIdentity,
        )
        Log.i(
            TAG,
            "session open sourceId=${creds.sourceId} apiBase=${handshake.apiBase} " +
                "profileFields=${profile.size} status=${profile["status"] ?: "?"} " +
                "watchdog=${profile["watchdog_timeout"] ?: "?"} ms=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return StalkerSession(
            apiBase = handshake.apiBase,
            token = handshake.token,
            expiresAtMs = SystemClock.elapsedRealtime() + sessionTtlMs(profile),
            profile = profile,
        )
    }

    companion object {
        private const val TAG = "StalkerAuth"

        /** Used when the portal states no `watchdog_timeout`, or states one we cannot believe. */
        internal const val DEFAULT_SESSION_TTL_MS = 5 * 60_000L

        /** Never re-handshake more than once a minute, nor trust a session for longer than a quarter hour. */
        internal const val MIN_SESSION_TTL_MS = 60_000L
        internal const val MAX_SESSION_TTL_MS = 15 * 60_000L

        /**
         * How long to trust a token, taken from the portal's own `watchdog_timeout` (seconds) where it
         * offers one. A real set-top box keeps its session alive by pinging the watchdog inside that
         * window; we do not ping, so we instead treat the portal's own number as the point by which
         * the token must be considered gone and re-handshake *before* it is refused. The flat five
         * minutes this replaces was longer than some panels allow, which turned every routine expiry
         * into a failed request first and a re-handshake second.
         */
        internal fun sessionTtlMs(profile: Map<String, String>): Long {
            val seconds = profile["watchdog_timeout"]?.trim()?.toLongOrNull() ?: return DEFAULT_SESSION_TTL_MS
            if (seconds <= 0) return DEFAULT_SESSION_TTL_MS
            return (seconds * 1_000L).coerceIn(MIN_SESSION_TTL_MS, MAX_SESSION_TTL_MS)
        }
    }
}
