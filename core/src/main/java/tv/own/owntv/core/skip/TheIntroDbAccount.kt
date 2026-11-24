package tv.own.owntv.core.skip

import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The TheIntroDB row in Settings: whether the user's key works, and how many lookups are left today.
 *
 * Validity comes from `/v4/user/stats` alone — `/v4/media` silently treats a wrong key as no key. The
 * allowance comes from the headers of one `/v4/media` call for a title every catalogue knows, sent
 * with the same key a real lookup would use, so the number shown is the one lookups will spend.
 */
class TheIntroDbAccount(
    private val client: TheIntroDbClient,
    private val repository: SkipSegmentRepository,
    private val settings: SettingsRepository,
) {
    /** [key] is null when no key is entered. */
    data class Status(val key: TheIntroDbClient.KeyCheck?, val quota: TheIntroDbClient.Quota?)

    /** The allowance the last lookup or check reported; null until one ran this session. */
    val quota: StateFlow<TheIntroDbClient.Quota?> = repository.quota

    suspend fun check(): Status {
        val key = settings.currentTheIntroDbApiKey().trim()
        val keyCheck = if (key.isEmpty()) null else client.checkKey(key)
        val (_, quota) = client.media(PROBE_TMDB_ID, PROBE_SEASON, PROBE_EPISODE, null, key.ifEmpty { null })
        quota?.let(repository::noteQuota)
        return Status(keyCheck, quota ?: repository.quota.value)
    }

    private companion object {
        // Game of Thrones S1 E1 on TMDB: present in every catalogue the service knows.
        const val PROBE_TMDB_ID = 1399
        const val PROBE_SEASON = 1
        const val PROBE_EPISODE = 1
    }
}
