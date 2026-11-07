package tv.own.owntv.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.portalCeilingStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_portal_ceiling")

/**
 * The parallel-request limit a Stalker portal has refused, per playlist, so the next sync and the
 * background backfill start below it instead of climbing into the same wall every time. A portal
 * that has never refused us has no entry and keeps the full range.
 */
internal class PortalCeilingStore(private val context: Context) {

    suspend fun get(sourceId: Long): LearnedCeiling? {
        val prefs = context.portalCeilingStore.data.first()
        val ceiling = prefs[ceilingKey(sourceId)] ?: return null
        return LearnedCeiling(ceiling, prefs[cleanRunsKey(sourceId)] ?: 0)
    }

    /** Record what one crawl learned with [limiter], which was built from [before]. */
    suspend fun record(sourceId: Long, before: LearnedCeiling?, limiter: AdaptivePortalLimiter) {
        val after = nextCeiling(before, limiter.throttledAt, limiter.reachedMax)
        if (after == before) return
        context.portalCeilingStore.edit {
            if (after == null) {
                it.remove(ceilingKey(sourceId))
                it.remove(cleanRunsKey(sourceId))
            } else {
                it[ceilingKey(sourceId)] = after.ceiling
                it[cleanRunsKey(sourceId)] = after.cleanRuns
            }
        }
    }

    private fun ceilingKey(sourceId: Long) = intPreferencesKey("ceiling_$sourceId")
    private fun cleanRunsKey(sourceId: Long) = intPreferencesKey("clean_runs_$sourceId")
}

/** [ceiling] is the lowest limit the portal refused; [cleanRuns] counts crawls since then that ran at the cap unrefused. */
internal data class LearnedCeiling(val ceiling: Int, val cleanRuns: Int)

/** Clean crawls at the cap before the cap is raised by one again. */
internal const val CLEAN_RUNS_BEFORE_RAISE = 2

/**
 * What a crawl leaves behind. A refusal stores the limit it happened at. A crawl that ran at the
 * cap without one counts towards raising it, one step at a time, until the portal is back to the
 * full range — so a single overloaded moment does not slow a tolerant portal down for good, and a
 * strict one is only asked for one more request at a time.
 */
internal fun nextCeiling(stored: LearnedCeiling?, throttledAt: Int?, reachedMax: Boolean): LearnedCeiling? = when {
    throttledAt != null -> LearnedCeiling(throttledAt, 0)
    stored == null -> null
    !reachedMax -> stored
    stored.cleanRuns + 1 < CLEAN_RUNS_BEFORE_RAISE -> stored.copy(cleanRuns = stored.cleanRuns + 1)
    stored.ceiling + 1 > AdaptivePortalLimiter.DEFAULT_MAX -> null
    else -> LearnedCeiling(stored.ceiling + 1, 0)
}
