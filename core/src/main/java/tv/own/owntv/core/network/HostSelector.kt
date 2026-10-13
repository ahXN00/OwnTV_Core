package tv.own.owntv.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.entity.SourceEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The one owner of each source's in-use server (design §4, R5). Process-wide (a Koin single).
 *
 * - The main server is always tried first on app start, and a failover lasts for the session only.
 *   Live, VOD and catch-up ask [current]/[forRequest] at request time; nothing reads a stored `activeUrl`
 *   for playback, so only an in-memory switch from this process moves off the primary. A ViewModel or a
 *   running sync that holds an old row still lands on the server that works now.
 * - Only this class writes `sources.activeUrl`, and only once per successful switch ([confirm]) or when
 *   the user picks a server ([use]). A failed attempt never writes it.
 * - Switches go through [attempt], [confirm] and [abandon], each under the per-source lock.
 * - Sync and EPG may *try* other servers for API calls ([apiHosts]) but never move the playback server.
 */
class HostSelector(private val writeActive: suspend (sourceId: Long, activeUrl: String?) -> Unit) {

    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** In-use server per source for this process, once anything moved it. */
    private val inMemory = ConcurrentHashMap<Long, String>()

    /** What `sources.activeUrl` holds as far as this process wrote it ("" = null), to write once only. */
    private val persisted = ConcurrentHashMap<Long, String>()

    /**
     * Held by Test all servers and by the connection-limit measurement (§9): both open requests against
     * the account one at a time, so neither may run beside the other or beside a switch.
     */
    val probeLock = Mutex()

    private fun lock(id: Long) = locks.getOrPut(id) { Mutex() }

    /**
     * The server [s] should use now (an origin), or null for a source without a pool (M3U, Stalker).
     * The main server is always tried first on app start; a failover lasts for the session only.
     */
    fun current(s: SourceEntity): String? {
        val pool = HostPool.pool(s)
        if (pool.isEmpty()) return null
        if (HostPool.hasPathPrefix(s.url)) return pool.first()
        return inMemory[s.id]?.takeIf { it in pool } ?: pool.first()
    }

    /** [url] rewritten onto [s]'s in-use server; unchanged for a source without backups. */
    fun forRequest(url: String, s: SourceEntity): String = HostPool.forRequest(url, s, current(s))

    /** `scheme://host[:port]` to put in front of a request path for [s] (the primary's own text when on it). */
    fun activeBase(s: SourceEntity): String {
        val host = current(s)
        val pool = HostPool.pool(s)
        return if (host == null || pool.size < 2 || host == pool.first()) s.url.trimEnd('/') else host
    }

    /** Put [host] in use for this process without persisting (a round's attempt); see [confirm]. */
    suspend fun attempt(s: SourceEntity, host: String) = lock(s.id).withLock {
        if (host in HostPool.pool(s)) inMemory[s.id] = host
    }

    /**
     * [host] played (first frame / READY): persist it once. The primary is stored as null. Further calls
     * for the same server do nothing, so READY on every zap doesn't invalidate the sources flows.
     */
    suspend fun confirm(s: SourceEntity, host: String) = lock(s.id).withLock {
        val pool = HostPool.pool(s)
        if (host !in pool) return@withLock
        inMemory[s.id] = host
        val value = host.takeIf { it != pool.first() }
        val known = persisted[s.id] ?: (HostPool.origin(s.activeUrl)?.takeIf { it in pool } ?: "")
        if (known == (value ?: "")) {
            persisted[s.id] = known
            return@withLock
        }
        writeActive(s.id, value)
        persisted[s.id] = value ?: ""
    }

    /** A round failed everywhere: forget the in-memory attempt, back to what was last confirmed. */
    suspend fun abandon(s: SourceEntity) = lock(s.id).withLock {
        val known = persisted[s.id]
        if (known == null) inMemory.remove(s.id) else if (known.isEmpty()) inMemory.remove(s.id) else inMemory[s.id] = known
    }

    /** The user chose [host] (Use first, Use fastest, OK on a result row): in use and stored now. */
    suspend fun use(s: SourceEntity, host: String) {
        val o = HostPool.origin(host) ?: return
        confirm(s, o)
    }

    /** The source's row changed under us (edited, restored, deleted): drop what this process remembered. */
    fun forget(sourceId: Long) {
        inMemory.remove(sourceId)
        persisted.remove(sourceId)
    }

    /**
     * Servers an API call (sync, EPG, account status) may try, in order: the in-use one, then the next
     * [MAX_API_HOSTS] − 1 after it (R6). One attempt each; the caller bounds the total time.
     */
    fun apiHosts(s: SourceEntity): List<String> {
        val pool = HostPool.pool(s)
        val now = current(s) ?: return emptyList()
        if (!HostPool.failoverActive(s)) return listOf(now)
        val start = pool.indexOf(now).coerceAtLeast(0)
        return (0 until minOf(MAX_API_HOSTS, pool.size)).map { pool[(start + it) % pool.size] }
    }

    companion object {
        /** Sync and EPG try at most this many servers per call (R6). */
        const val MAX_API_HOSTS = 3

        /** …within this much time in total. */
        const val API_BUDGET_MS = 60_000L

        /**
         * Runs [call] on each of [hosts] in turn — strictly one request in flight — until one succeeds.
         * Only a [HostFailureClass.HOST] failure moves on; anything else (an auth error, a body that broke
         * after it started) is the answer and is rethrown at once, as is the last host's failure. No new
         * host starts once [budgetMs] has gone by since the first one did.
         */
        suspend fun <T> tryHosts(
            hosts: List<String>,
            budgetMs: Long = API_BUDGET_MS,
            now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
            call: suspend (host: String) -> T,
        ): T {
            require(hosts.isNotEmpty())
            val startedAt = now()
            var last: Throwable? = null
            for ((i, host) in hosts.withIndex()) {
                if (i > 0 && now() - startedAt >= budgetMs) break
                try {
                    return call(host)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    if (HostFailure.classify(e) != HostFailureClass.HOST) throw e
                }
            }
            throw last ?: IllegalStateException("no host tried")
        }
    }
}
