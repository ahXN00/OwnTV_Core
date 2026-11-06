package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.CatalogBackfillEntity
import tv.own.owntv.core.model.MediaType

/** The outstanding-page plan written by setup and drained in the background or on demand. */
@Dao
interface CatalogBackfillDao {

    /**
     * REPLACE, so re-planning a phase overwrites the previous plan for the same
     * (source, type, category) instead of colliding on the primary key. A later sweep's totals are
     * the truthful ones — the portal may have gained or lost items since.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CatalogBackfillEntity>)

    /** Every category of this phase still owing pages, in provider order so the backfill fills top-down. */
    @Query(
        "SELECT * FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType " +
            "AND done = 0 ORDER BY sortBase ASC",
    )
    suspend fun pending(sourceId: Long, mediaType: MediaType): List<CatalogBackfillEntity>

    /** One category's plan — the read-through path, when the user opens a category directly. */
    @Query(
        "SELECT * FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType " +
            "AND categoryRemoteId = :categoryRemoteId",
    )
    suspend fun forCategory(sourceId: Long, mediaType: MediaType, categoryRemoteId: String): CatalogBackfillEntity?

    /**
     * One category's plan, found by the *local* row id.
     *
     * [forCategory] keys on the portal's own id, which is what the drain works in. A browse screen
     * only ever has the local `categories.id`, and making every caller translate first would mean a
     * second query for the common case where there is no plan at all.
     */
    @Query("SELECT * FROM catalog_backfill WHERE categoryDbId = :categoryDbId LIMIT 1")
    suspend fun forCategoryDbId(categoryDbId: Long): CatalogBackfillEntity?

    /**
     * Advance the cursor after a page lands. Separate from [markDone] so an interrupted drain
     * resumes at the next unfetched page rather than restarting the category.
     */
    @Query(
        "UPDATE catalog_backfill SET nextPage = :nextPage WHERE sourceId = :sourceId " +
            "AND mediaType = :mediaType AND categoryRemoteId = :categoryRemoteId",
    )
    suspend fun advance(sourceId: Long, mediaType: MediaType, categoryRemoteId: String, nextPage: Int)

    @Query(
        "UPDATE catalog_backfill SET done = 1, nextPage = lastPage + 1 WHERE sourceId = :sourceId " +
            "AND mediaType = :mediaType AND categoryRemoteId = :categoryRemoteId",
    )
    suspend fun markDone(sourceId: Long, mediaType: MediaType, categoryRemoteId: String)

    /** Every category of this phase, finished or not — the reconciliation pass reads the done ones too. */
    @Query(
        "SELECT * FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType " +
            "ORDER BY sortBase ASC",
    )
    suspend fun all(sourceId: Long, mediaType: MediaType): List<CatalogBackfillEntity>

    /**
     * Put a finished category back in the queue from page 2 (the drain's reconciliation).
     *
     * A drain can believe it walked every page and still be short: a measured *eager* walk
     * lost 4,987 of 65,523 movies to pages the portal answered but did not fill. Re-walking the
     * short categories is what turns "I fetched every page" into "the rows are actually here", and
     * re-inserting the ones that already landed costs nothing — `sourceId + remoteId` is unique.
     */
    @Query(
        "UPDATE catalog_backfill SET done = 0, nextPage = 2 WHERE sourceId = :sourceId " +
            "AND mediaType = :mediaType AND categoryRemoteId = :categoryRemoteId",
    )
    suspend fun reopen(sourceId: Long, mediaType: MediaType, categoryRemoteId: String)

    /** How many categories of this phase still owe pages — 0 means the catalogue is whole. */
    @Query(
        "SELECT COUNT(*) FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType AND done = 0",
    )
    suspend fun pendingCount(sourceId: Long, mediaType: MediaType): Int

    /**
     * Which of [sourceIds] still owe pages. Observes only this small table, so it re-runs on each
     * cursor advance for the price of an indexed lookup — never a scan of the content tables.
     */
    @Query("SELECT DISTINCT sourceId FROM catalog_backfill WHERE done = 0 AND sourceId IN (:sourceIds) ORDER BY sourceId")
    fun observeFilling(sourceIds: List<Long>): Flow<List<Long>>

    /** Films and series stored so far for [sourceIds] — two counts on the `sourceId` index. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM movies WHERE sourceId IN (:sourceIds)) + " +
            "(SELECT COUNT(*) FROM series WHERE sourceId IN (:sourceIds))",
    )
    suspend fun titlesStored(sourceIds: List<Long>): Int

    /** The phase's reconciliation target: the sum of the portal's own per-category totals. */
    @Query("SELECT COALESCE(SUM(totalItems), 0) FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType")
    suspend fun advertisedTotal(sourceId: Long, mediaType: MediaType): Int

    /** Dropped when a phase is re-planned from scratch, and by the CASCADE when a source is deleted. */
    @Query("DELETE FROM catalog_backfill WHERE sourceId = :sourceId AND mediaType = :mediaType")
    suspend fun clear(sourceId: Long, mediaType: MediaType)
}
