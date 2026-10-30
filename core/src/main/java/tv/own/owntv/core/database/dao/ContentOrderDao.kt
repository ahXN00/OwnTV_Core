package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.customize.pinnedAfterMove
import tv.own.owntv.core.model.MediaType

/**
 * Write side of the per-profile manual item order ("Move up/down"). The ordered content lists are
 * produced by the content DAOs (LEFT JOIN content_order). See [ContentOrderEntity].
 */
@Dao
interface ContentOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ContentOrderEntity>)

    @Query("DELETE FROM content_order WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun clearContext(profileId: Long, type: MediaType, contextKey: String)

    /** Context keys that actually have manual-order rows for this profile/section — the fast
     *  path: folders with no overrides use the plain indexed paging query instead of the
     *  unindexable content_order join-sort (which materializes the whole folder per page). */
    @Query("SELECT DISTINCT contextKey FROM content_order WHERE profileId = :profileId AND mediaType = :type")
    fun observeContextKeys(profileId: Long, type: MediaType): Flow<List<String>>

    /** Replaces a context's entire order in one transaction (commit point of a Move). */
    @Transaction
    suspend fun replaceContext(profileId: Long, type: MediaType, contextKey: String, rows: List<ContentOrderEntity>) {
        clearContext(profileId, type, contextKey)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    /**
     * Saves a Move of one list: [order] is the list as the move left it, [carried] the items the user
     * moved. Only those and the items moved before keep a position — the rest go on following the sort
     * chip around them (see `placePinned`). Favorites has no sort chip, so its whole order is kept.
     */
    @Transaction
    suspend fun saveMove(profileId: Long, type: MediaType, contextKey: String, order: List<Long>, carried: Set<Long>) {
        val positions =
            if (contextKey == ContentOrderEntity.FAV_CONTEXT) order.withIndex().associate { (i, id) -> id to i }
            else pinnedAfterMove(order, movedItemIds(profileId, type, contextKey).toSet(), carried)
        replaceContext(
            profileId, type, contextKey,
            positions.map { (id, pos) -> ContentOrderEntity(profileId = profileId, mediaType = type, contextKey = contextKey, itemId = id, position = pos) },
        )
    }

    /** Drops every list order except [keep] (Favorites). Run once: orders saved by older versions held every
     *  item of the list, so nothing in it could follow the sort chip. */
    @Query("DELETE FROM content_order WHERE contextKey != :keep")
    suspend fun dropListOrdersExcept(keep: String)

    /** Everything, for Backup & Restore / re-sync snapshotting. */
    /** Does this row already exist? The dry run before a sync counts what is genuinely new. */
    @Query("SELECT EXISTS(SELECT 1 FROM content_order WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId)")
    suspend fun exists(profileId: Long, type: MediaType, contextKey: String, itemId: Long): Boolean

    /** The items of one list that the user moved by hand (they keep a saved position). */
    @Query("SELECT itemId FROM content_order WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun movedItemIds(profileId: Long, type: MediaType, contextKey: String): List<Long>

    @Query("SELECT * FROM content_order")
    suspend fun getAllOnce(): List<ContentOrderEntity>

    /**
     * Manual-order rows tied to one source, joined to stable content keys — the per-source re-sync
     * snapshot. Without this, a resync renumbered the content ids and every Move the user had
     * made in that source's folders was silently lost. Episodes never appear in content_order, so
     * the join only covers LIVE/MOVIE/SERIES.
     */
    @Query(
        "SELECT o.profileId AS profileId, o.mediaType AS mediaType, o.itemId AS itemId, " +
            "o.contextKey AS contextKey, o.position AS position, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name " +
            "FROM content_order o " +
            "LEFT JOIN channels c ON o.mediaType = 'LIVE' AND o.itemId = c.id " +
            "LEFT JOIN movies m ON o.mediaType = 'MOVIE' AND o.itemId = m.id " +
            "LEFT JOIN series s ON o.mediaType = 'SERIES' AND o.itemId = s.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<ContentOrderExportRow>

    /**
     * Snapshot-scoped orphan drop (as FavoriteDao.purgeSnapshotOrphan) for many ids at once. This
     * table has no index that starts with (profileId, mediaType, itemId), so a one-row form scans all of
     * a profile's rows for every snapshot row — quadratic, and hours with tens of thousands of rows while
     * it holds the writer.
     */
    @Query(
        "DELETE FROM content_order WHERE profileId = :profileId AND mediaType = :type AND itemId IN (:itemIds) AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphans(profileId: Long, type: MediaType, itemIds: List<Long>)

    /**
     * Drops order rows whose content row no longer exists — content is clear-then-insert on every sync,
     * so an item's itemId goes stale. Called after a re-sync's relink (UserDataResolver), mirroring
     * FavoriteDao.purgeOrphans. Episodes never appear here (items only — LIVE/MOVIE/SERIES).
     */
    @Query(
        "DELETE FROM content_order WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
