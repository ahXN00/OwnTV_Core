package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tv.own.owntv.core.database.entity.ChannelProviderCandidateEntity

/**
 * Reads and writes of the cross-provider failover memory (v47, Smart Provider phase 2).
 *
 * Deliberately small. This table is a cache of a *relationship*, and the entity plus its migration
 * are the substance of this phase — what a provider last did for a channel. Deciding which
 * candidate to actually try, and in what order, is phase 3's job and lives above this interface, so
 * nothing here ranks, scores, probes or schedules anything.
 */
@Dao
interface SmartProviderDao {

    /**
     * Remember a relationship, or overwrite what was known about it.
     *
     * REPLACE rather than ABORT because re-discovering a candidate must not fail the caller, and the
     * new row is the truthful one: the fresh match carries the current name and remote id. The
     * primary key is (anchorKey, sourceId), so re-discovering the same pairing updates one row
     * instead of accumulating duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candidate: ChannelProviderCandidateEntity)

    /** The batch form, for a discovery pass that just matched many candidates at once. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(candidates: List<ChannelProviderCandidateEntity>)

    /**
     * Every provider remembered for one anchor channel, most recently proven first.
     *
     * Ordered so the caller's default is already the sensible one — a provider that worked beats one
     * that never has (both stamps `0`) beats one that has only failed — and the trailing `sourceId`
     * keeps the order deterministic for candidates that tie. Never-yet-tried candidates sort above
     * failed ones because their `lastSuccessAt` is `0` while a failed-but-once-working one is not;
     * what must *not* be read into this order is that a never-tried provider is preferable, which is
     * a judgement for the caller, not a query.
     */
    @Query(
        "SELECT * FROM `channel_provider_candidates` WHERE `anchorKey` = :anchorKey " +
            "ORDER BY `lastSuccessAt` DESC, `sourceId` ASC",
    )
    suspend fun forAnchor(anchorKey: String): List<ChannelProviderCandidateEntity>

    /** One remembered pairing, or null when this provider was never a known candidate for it. */
    @Query(
        "SELECT * FROM `channel_provider_candidates` WHERE `anchorKey` = :anchorKey " +
            "AND `sourceId` = :sourceId",
    )
    suspend fun find(anchorKey: String, sourceId: Long): ChannelProviderCandidateEntity?

    /**
     * Record that a tune on [sourceId] worked for [anchorKey] at [at] (epoch millis).
     *
     * Leaves [ChannelProviderCandidateEntity.lastFailureAt] alone on purpose: the two stamps answer
     * different questions ("has this provider ever worked for me" vs "when did it last let me down"),
     * and a success that erased the failure would hide a provider that is intermittently bad.
     */
    @Query(
        "UPDATE `channel_provider_candidates` SET `lastSuccessAt` = :at " +
            "WHERE `anchorKey` = :anchorKey AND `sourceId` = :sourceId",
    )
    suspend fun markSuccess(anchorKey: String, sourceId: Long, at: Long)

    /**
     * Record a provider-relevant failure at [at], with [reason] being the player's
     * `PlayerFailureReason.name`.
     *
     * The caller owns the provider-relevance filter — see the entity's note on
     * [ChannelProviderCandidateEntity.lastFailureReason]. Nothing is inserted here: a failure for a
     * pairing this table has never heard of is not evidence of a relationship, so it updates nothing
     * rather than inventing a candidate row.
     */
    @Query(
        "UPDATE `channel_provider_candidates` SET `lastFailureAt` = :at, `lastFailureReason` = :reason " +
            "WHERE `anchorKey` = :anchorKey AND `sourceId` = :sourceId",
    )
    suspend fun markFailure(anchorKey: String, sourceId: Long, at: Long, reason: String?)

    /**
     * Forget one pairing without touching the others, for a match that turned out to be wrong (a
     * candidate tuned to the wrong content, say) — the anchor's other candidates stay.
     */
    @Query(
        "DELETE FROM `channel_provider_candidates` WHERE `anchorKey` = :anchorKey " +
            "AND `sourceId` = :sourceId",
    )
    suspend fun forget(anchorKey: String, sourceId: Long)
}
