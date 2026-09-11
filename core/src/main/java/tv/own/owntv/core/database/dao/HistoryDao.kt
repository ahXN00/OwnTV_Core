package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.model.MediaType

/** Write side of watch history; the recently-watched content rows live in the content DAOs (joins). */
@Dao
interface HistoryDao {
    /** One row per (profile, type, item); REPLACE bumps `watchedAt` via the unique index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: WatchHistoryEntity)

    /**
     * The merge write, and deliberately not [record]: a row that arrives from another device must
     * never move `watchedAt` **backwards**. REPLACE did exactly that — the other device's older copy
     * of "you watched this" overwrote the newer local one, so a sync could make a show look less
     * watched than it was. Paired with [bumpIfNewer]: insert when absent, then move the time forward
     * only when the incoming one is actually later. Both run inside the resolver's own transaction.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: WatchHistoryEntity)

    /** Moves `watchedAt` forward, never back. The `<` is the whole rule. */
    @Query(
        "UPDATE watch_history SET watchedAt = :at " +
            "WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND watchedAt < :at",
    )
    suspend fun bumpIfNewer(profileId: Long, type: MediaType, itemId: Long, at: Long)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun remove(profileId: Long, type: MediaType, itemId: Long)

    /**
     * Deletes the row only when it is older than [at] — the local-sync merge rule for an
     * incoming deletion. A row the user re-created after the other device deleted it is newer,
     * and survives. Returns the number of rows removed, so the sync summary can count it.
     */
    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND watchedAt <= :at")
    suspend fun removeIfOlderThan(profileId: Long, type: MediaType, itemId: Long, at: Long): Int

    /** The episode rows belonging to one series — removed alongside the show's own row, so the
     *  top-bar Continue chip stops offering a show the user just removed from history. */
    @Query(
        "DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)",
    )
    suspend fun removeSeriesEpisodes(profileId: Long, seriesId: Long)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)

    /** Clear just one media type (Live / Movie / Series) for a profile. */
    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type")
    suspend fun clearType(profileId: Long, type: MediaType)

    /** When it was last watched — the dry run compares it against an incoming deletion. */
    @Query("SELECT watchedAt FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun watchedAt(profileId: Long, type: MediaType, itemId: Long): Long?

    /** Does this row already exist? The dry run before a sync counts what is genuinely new. */
    @Query("SELECT EXISTS(SELECT 1 FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId)")
    suspend fun exists(profileId: Long, type: MediaType, itemId: Long): Boolean

    @Query("SELECT COUNT(*) FROM watch_history WHERE profileId = :profileId AND mediaType = :type")
    fun count(profileId: Long, type: MediaType): Flow<Int>

    /** The single most-recently-watched item (any type) — drives the top-bar Continue chip. */
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY watchedAt DESC LIMIT 1")
    fun observeMostRecent(profileId: Long): Flow<WatchHistoryEntity?>

    /** One profile's history rows, so a "clear" can record each deletion before it happens. */
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId")
    suspend fun getForProfile(profileId: Long): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND mediaType = :type")
    suspend fun getForProfileType(profileId: Long, type: MediaType): List<WatchHistoryEntity>

    /** The episodes of one series that are in history — the ids whose removal has to be recorded. */
    @Query(
        "SELECT itemId FROM watch_history WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)",
    )
    suspend fun episodeIdsInHistory(profileId: Long, seriesId: Long): List<Long>

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM watch_history")
    suspend fun getAllOnce(): List<WatchHistoryEntity>

    /** User-data rows tied to one source, already joined to stable content keys for fast re-sync snapshots. */
    @Query(
        "SELECT h.profileId AS profileId, h.mediaType AS mediaType, h.itemId AS itemId, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId, episodeSeries.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId, e.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name, " +
            "episodeSeries.remoteId AS seriesRemoteId, episodeSeries.name AS seriesName, " +
            "e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, " +
            "h.watchedAt AS at, 0 AS positionMs, 0 AS durationMs " +
            "FROM watch_history h " +
            "LEFT JOIN channels c ON h.mediaType = 'LIVE' AND h.itemId = c.id " +
            "LEFT JOIN movies m ON h.mediaType = 'MOVIE' AND h.itemId = m.id " +
            "LEFT JOIN series s ON h.mediaType = 'SERIES' AND h.itemId = s.id " +
            "LEFT JOIN episodes e ON h.mediaType = 'EPISODE' AND h.itemId = e.id " +
            "LEFT JOIN series episodeSeries ON e.seriesId = episodeSeries.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId OR episodeSeries.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<UserDataExportRow>

    @Query(
        "DELETE FROM watch_history WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))  OR " +
            // Resume positions and history are mostly EPISODE rows, and without this branch the
            // OR-chain was false for every one of them: the orphan was never dropped, while the
            // relink inserted a fresh row for the new id. Every series re-sync therefore left one
            // more dead row behind, for ever. Safe to purge: an episode that has not loaded yet is
            // held in the pending set by the same call and re-inserts when it arrives.
            "(:type = 'EPISODE' AND itemId NOT IN (SELECT id FROM episodes))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /** Drops history rows orphaned by a re-sync (see FavoriteDao.purgeOrphans); episodes excluded. */
    @Query(
        "DELETE FROM watch_history WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
