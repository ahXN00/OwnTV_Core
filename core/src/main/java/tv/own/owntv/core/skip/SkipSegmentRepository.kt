package tv.own.owntv.core.skip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Skip times for the playing film or episode, asked of TheIntroDB at most once per item.
 *
 * **Memory only, by design.** TheIntroDB's terms allow temporary client-side caching for
 * performance and forbid storing answers in a way that rebuilds its database — so nothing here
 * reaches Room, a file or a backup, and a restart asks again.
 *
 * An empty answer is remembered for [MISS_TTL_MS] only: the service grows every day, and an episode
 * nobody had timed this morning may be timed tonight. A 429 stops every lookup until the time the
 * service named, so a household over its daily allowance is not sending thousands of refused calls.
 */
class SkipSegmentRepository(
    private val lookup: suspend (key: Key, durationMs: Long?) -> Pair<TheIntroDbClient.Lookup, TheIntroDbClient.Quota?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A film is (tmdbId, null, null); an episode is the show's tmdbId with its season and episode. */
    data class Key(val tmdbId: Int, val season: Int?, val episode: Int?)

    private class Entry(val segments: List<RawSkipSegment>, val atMs: Long)

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > MAX_ENTRIES
    }
    private var blockedUntilMs = 0L

    private val _quota = MutableStateFlow<TheIntroDbClient.Quota?>(null)
    /** The allowance the last answer reported; null until the first lookup of this session. */
    val quota: StateFlow<TheIntroDbClient.Quota?> = _quota.asStateFlow()

    /** The raw parts for [key]; empty when none are known or the service cannot be asked right now. */
    suspend fun segmentsFor(key: Key, durationMs: Long?): List<RawSkipSegment> = mutex.withLock {
        val now = clock()
        cache[key]?.let { hit ->
            if (hit.segments.isNotEmpty() || now - hit.atMs < MISS_TTL_MS) return hit.segments
        }
        if (now < blockedUntilMs) return emptyList()
        val (result, quota) = lookup(key, durationMs)
        quota?.let { _quota.value = it }
        when (result) {
            is TheIntroDbClient.Lookup.Found -> result.segments.also { cache[key] = Entry(it, now) }
            TheIntroDbClient.Lookup.NotFound, TheIntroDbClient.Lookup.Unlisted ->
                emptyList<RawSkipSegment>().also { cache[key] = Entry(it, now) }
            is TheIntroDbClient.Lookup.Limited -> emptyList<RawSkipSegment>().also { blockedUntilMs = result.untilMs }
            // A network failure is not remembered: the next play of this item simply asks again.
            TheIntroDbClient.Lookup.Failed -> emptyList()
        }
    }

    /** Records an allowance read outside a lookup (the key check in Settings). */
    fun noteQuota(quota: TheIntroDbClient.Quota) {
        _quota.value = quota
    }

    companion object {
        const val MAX_ENTRIES = 300
        const val MISS_TTL_MS = 30 * 60_000L
    }
}
