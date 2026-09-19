package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.SeriesSortOrderEntity

/**
 * Per-profile, per-series season/episode presentation order. See [SeriesSortOrderEntity] — a series
 * with no row simply uses the defaults, so this table stays tiny.
 */
@Dao
interface SeriesSortOrderDao {
    /** The one row for a series, or null when the user has never changed it. */
    @Query("SELECT * FROM series_sort_order WHERE profileId = :profileId AND seriesId = :seriesId")
    fun observe(profileId: Long, seriesId: Long): Flow<SeriesSortOrderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SeriesSortOrderEntity>)

    /** The row id for a series, or null when the user has never changed it. */
    @Query("SELECT id FROM series_sort_order WHERE profileId = :profileId AND seriesId = :seriesId LIMIT 1")
    suspend fun findRowId(profileId: Long, seriesId: Long): Long?

    /**
     * Sets both orders in one write, keeping the existing row's id.
     *
     * This used to be a lookup followed by a REPLACE, because REPLACE on the unique
     * (profileId, seriesId) index mints a fresh autoGenerate id every time — and a real
     * `ON CONFLICT … DO UPDATE` was not available: SQLite learned that syntax in 3.24 (API 30) and
     * minSdk here is 26, so on an older device the statement failed to compile with
     * `near "ON": syntax error`.
     *
     * The engine now ships inside the APK (plan Phase C), so every device has UPSERT and the two
     * statements collapse into one. Plan D4: this is a workaround that had become pure cost.
     */
    @Query(
        "INSERT INTO series_sort_order (profileId, seriesId, seasonsDescending, episodesDescending) " +
            "VALUES (:profileId, :seriesId, :seasonsDescending, :episodesDescending) " +
            "ON CONFLICT(profileId, seriesId) DO UPDATE SET " +
            "seasonsDescending = :seasonsDescending, episodesDescending = :episodesDescending",
    )
    suspend fun setOrder(profileId: Long, seriesId: Long, seasonsDescending: Boolean, episodesDescending: Boolean)

    /** Everything, for Backup & Restore / re-sync snapshotting. */
    @Query("SELECT * FROM series_sort_order")
    suspend fun getAllOnce(): List<SeriesSortOrderEntity>

    /**
     * Sort rows for one source joined to their series' stable identity — the per-source re-sync
     * snapshot, mirroring [ContentOrderDao.exportRowsForSource].
     */
    @Query(
        "SELECT o.profileId AS profileId, o.seriesId AS seriesId, " +
            "o.seasonsDescending AS seasonsDescending, o.episodesDescending AS episodesDescending, " +
            "s.sourceId AS sourceId, s.remoteId AS remoteId, s.name AS name " +
            "FROM series_sort_order o JOIN series s ON o.seriesId = s.id WHERE s.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<SeriesSortOrderExportRow>

    /** Snapshot-scoped orphan drop, mirroring FavoriteDao.purgeSnapshotOrphan. */
    @Query(
        "DELETE FROM series_sort_order WHERE profileId = :profileId AND seriesId = :seriesId " +
            "AND seriesId NOT IN (SELECT id FROM series)",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, seriesId: Long)

    /** Drops rows whose series no longer exists, after a re-sync relink. Mirrors FavoriteDao.purgeOrphans. */
    @Query("DELETE FROM series_sort_order WHERE seriesId NOT IN (SELECT id FROM series)")
    suspend fun purgeOrphans()
}

/**
 * One sort row joined to its series' stable identity, for the per-source re-sync snapshot
 * ([SeriesSortOrderDao.exportRowsForSource]). Mirrors [ContentOrderExportRow]; there is no
 * mediaType because these rows only ever cover series.
 */
data class SeriesSortOrderExportRow(
    val profileId: Long,
    val seriesId: Long,
    val seasonsDescending: Boolean,
    val episodesDescending: Boolean,
    val sourceId: Long,
    val remoteId: String?,
    val name: String?,
)
