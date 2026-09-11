package tv.own.owntv.core.repository

import tv.own.owntv.core.database.entity.EpisodeEntity

/**
 * Pure decision logic for the lazy episode cache (S8). Kept free of Room so both halves — when to
 * re-fetch, and how to apply a fetched list without churning row ids — are unit-testable.
 */

/** How long a fetched episode list is trusted before the provider is asked again. */
const val EPISODE_CACHE_TTL_MS: Long = 6 * 60 * 60 * 1000 // 6 hours

/**
 * Whether a show's episodes should be fetched from the provider.
 *
 * Before S8 this was simply `episodeCount == 0`, so a populated show was never refreshed and the
 * episodes a provider added later could only be seen by deleting and re-adding the source. Now an
 * empty cache always fetches, and a populated one fetches once its stamp goes stale — including
 * immediately after a sync, which zeroes the stamp.
 */
fun shouldRefreshEpisodes(
    cachedCount: Int,
    episodesSyncedAt: Long,
    now: Long,
    ttlMs: Long = EPISODE_CACHE_TTL_MS,
): Boolean {
    if (cachedCount <= 0) return true
    if (episodesSyncedAt <= 0L) return true // never stamped, or invalidated by a sync
    val age = now - episodesSyncedAt
    return age !in 0 until ttlMs // a clock moved backwards is treated as stale, not as fresh forever
}

/** What applying a freshly fetched episode list means for the rows already stored. */
data class EpisodeMergePlan(
    val inserts: List<EpisodeEntity>,
    val updates: List<EpisodeEntity>,
    val deleteIds: List<Long>,
)

/**
 * Matches [incoming] against [existing] and preserves the row id of every episode that survives.
 *
 * The id matters: watch history, resume positions and next-episode autoplay all key on it, so the
 * old delete-everything-then-reinsert would have detached the user's progress on every refresh.
 * Episodes are matched on provider `remoteId` where there is one and on season/episode number
 * otherwise (M3U-style rows carry no remote id).
 */
fun planEpisodeMerge(existing: List<EpisodeEntity>, incoming: List<EpisodeEntity>): EpisodeMergePlan {
    val byKey = LinkedHashMap<String, EpisodeEntity>()
    val duplicateIds = ArrayList<Long>()
    for (row in existing) {
        // A duplicate key can only come from an older build's bookkeeping; drop the extra copies.
        if (byKey.putIfAbsent(row.mergeKey(), row) != null) duplicateIds.add(row.id)
    }

    val inserts = ArrayList<EpisodeEntity>()
    val updates = ArrayList<EpisodeEntity>()
    val kept = HashSet<Long>()
    for (row in incoming) {
        val current = byKey[row.mergeKey()]
        if (current == null) {
            inserts.add(row.copy(id = 0))
        } else {
            kept.add(current.id)
            // The air date is the one field the stored row may know better than the provider: it can
            // have been filled in from TMDB for a panel that dates nothing. A refresh must not blank
            // it back out, so a missing incoming date keeps whatever is already there.
            val merged = row.copy(id = current.id, airDateMs = row.airDateMs ?: current.airDateMs)
            // Skip no-op writes: on a typical refresh nothing but the new episodes has changed, and
            // every write here also fires the episodes_fts triggers.
            if (merged != current) updates.add(merged)
        }
    }

    val deleteIds = duplicateIds + existing.mapNotNull { it.id.takeIf { id -> id !in kept && id !in duplicateIds } }
    return EpisodeMergePlan(inserts = inserts, updates = updates, deleteIds = deleteIds.distinct())
}

private fun EpisodeEntity.mergeKey(): String =
    remoteId?.takeIf { it.isNotBlank() } ?: "s${seasonNumber}e$episodeNumber"
