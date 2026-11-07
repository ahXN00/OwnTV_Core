package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.model.MediaType

/** Write side of favorites; per-type favorite content lists live in the content DAOs (joins). */
@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun remove(profileId: Long, type: MediaType, itemId: Long)

    /** Every favorite of one profile: a restore's Replace, before the backup's are put back. */
    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    /**
     * Deletes the row only when it is older than [at] — the local-sync merge rule for an
     * incoming deletion. A row the user re-created after the other device deleted it is newer,
     * and survives. Returns the number of rows removed, so the sync summary can count it.
     */
    @Query("DELETE FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND addedAt <= :at")
    suspend fun removeIfOlderThan(profileId: Long, type: MediaType, itemId: Long, at: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId)")
    fun isFavorite(profileId: Long, type: MediaType, itemId: Long): Flow<Boolean>

    /** When it was favorited — the dry run compares it against an incoming deletion. */
    @Query("SELECT addedAt FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun addedAt(profileId: Long, type: MediaType, itemId: Long): Long?

    /** Does this row already exist? The dry run before a sync counts what is genuinely new. */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId)")
    suspend fun exists(profileId: Long, type: MediaType, itemId: Long): Boolean

    @Query("SELECT COUNT(*) FROM favorites WHERE profileId = :profileId AND mediaType = :type")
    fun count(profileId: Long, type: MediaType): Flow<Int>

    /** Favorite item ids for a type, so lists can mark rows without a per-row query. */
    @Query("SELECT itemId FROM favorites WHERE profileId = :profileId AND mediaType = :type")
    fun observeFavoriteIds(profileId: Long, type: MediaType): Flow<List<Long>>

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM favorites")
    suspend fun getAllOnce(): List<FavoriteEntity>

    /** User-data rows tied to one source, already joined to stable content keys for fast re-sync snapshots. */
    @Query(
        "SELECT f.profileId AS profileId, f.mediaType AS mediaType, f.itemId AS itemId, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId, episodeSeries.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId, e.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name, " +
            "episodeSeries.remoteId AS seriesRemoteId, episodeSeries.name AS seriesName, " +
            "e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, " +
            "f.addedAt AS at, 0 AS positionMs, 0 AS durationMs " +
            "FROM favorites f " +
            "LEFT JOIN channels c ON f.mediaType = 'LIVE' AND f.itemId = c.id " +
            "LEFT JOIN movies m ON f.mediaType = 'MOVIE' AND f.itemId = m.id " +
            "LEFT JOIN series s ON f.mediaType = 'SERIES' AND f.itemId = s.id " +
            "LEFT JOIN episodes e ON f.mediaType = 'EPISODE' AND f.itemId = e.id " +
            "LEFT JOIN series episodeSeries ON e.seriesId = episodeSeries.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId OR episodeSeries.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<UserDataExportRow>

    @Query(
        "DELETE FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /**
     * Drops favorites whose content row no longer exists — content is clear-then-insert on every sync,
     * so a favorite's itemId goes stale and the join (pagingFavorites) returns nothing while the raw
     * count still showed it. Called after a re-sync's relink (UserDataResolver). Episodes are excluded
     * (they load lazily, so their rows are legitimately absent until a show is opened).
     */
    @Query(
        "DELETE FROM favorites WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
