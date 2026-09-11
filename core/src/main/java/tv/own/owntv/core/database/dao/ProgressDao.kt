package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.model.MediaType

/** Resume positions for VOD and episodes (per profile). */
@Dao
interface ProgressDao {
    /** One row per (profile, type, item); REPLACE updates position via the unique index. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: PlaybackProgressEntity)

    /**
     * The merge write, and deliberately not [save]: a resume position from another device must never
     * overwrite a newer one here. REPLACE did, and it is the one case in local sync that loses real
     * watching — finish episode 7 on the television, sync, and the phone's stale "episode 5, 12
     * minutes in" wrote itself straight over it. Paired with [updateIfNewer]: insert when absent,
     * then overwrite only when the incoming position is genuinely later.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(progress: PlaybackProgressEntity)

    /** Overwrites the position only when [at] is later than the one already stored. */
    @Query(
        "UPDATE playback_progress SET positionMs = :positionMs, durationMs = :durationMs, updatedAt = :at " +
            "WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND updatedAt < :at",
    )
    suspend fun updateIfNewer(
        profileId: Long,
        type: MediaType,
        itemId: Long,
        positionMs: Long,
        durationMs: Long,
        at: Long,
    )

    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    fun observe(profileId: Long, type: MediaType, itemId: Long): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun get(profileId: Long, type: MediaType, itemId: Long): PlaybackProgressEntity?

    @Query("DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun clear(profileId: Long, type: MediaType, itemId: Long)

    /**
     * Deletes the row only when it is older than [at] — the local-sync merge rule for an
     * incoming deletion. A row the user re-created after the other device deleted it is newer,
     * and survives. Returns the number of rows removed, so the sync summary can count it.
     */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND updatedAt <= :at")
    suspend fun removeIfOlderThan(profileId: Long, type: MediaType, itemId: Long, at: Long): Int

    /** Every episode resume position for one series. "Remove from history" on a show has to run this
     *  too: Home's Continue watching row is built from EPISODE progress rows, not from history, so
     *  clearing the show's history row alone left the episode sitting on the home screen. */
    @Query(
        "DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)",
    )
    suspend fun clearSeriesEpisodes(profileId: Long, seriesId: Long)

    /** Wipe all resume positions for a profile — drives "Clear watch history" (so Home's continue-watching empties). */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    /** Wipe resume positions for one media type (MOVIE / EPISODE) for a profile. */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type")
    suspend fun clearProfileType(profileId: Long, type: MediaType)

    /** One profile's resume positions, so a "clear" can record each deletion before it happens. */
    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId")
    suspend fun getForProfile(profileId: Long): List<PlaybackProgressEntity>

    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = :type")
    suspend fun getForProfileType(profileId: Long, type: MediaType): List<PlaybackProgressEntity>

    /** The episodes of one series that have a resume position — the ids whose removal is recorded. */
    @Query(
        "SELECT itemId FROM playback_progress WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)",
    )
    suspend fun episodeIdsWithProgress(profileId: Long, seriesId: Long): List<Long>

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM playback_progress")
    suspend fun getAllOnce(): List<PlaybackProgressEntity>

    /** User-data rows tied to one source, already joined to stable content keys for fast re-sync snapshots. */
    @Query(
        "SELECT p.profileId AS profileId, p.mediaType AS mediaType, p.itemId AS itemId, " +
            "COALESCE(c.sourceId, m.sourceId, s.sourceId, episodeSeries.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, m.remoteId, s.remoteId, e.remoteId) AS remoteId, " +
            "COALESCE(c.name, m.name, s.name) AS name, " +
            "episodeSeries.remoteId AS seriesRemoteId, episodeSeries.name AS seriesName, " +
            "e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, " +
            "p.updatedAt AS at, p.positionMs AS positionMs, p.durationMs AS durationMs " +
            "FROM playback_progress p " +
            "LEFT JOIN channels c ON p.mediaType = 'LIVE' AND p.itemId = c.id " +
            "LEFT JOIN movies m ON p.mediaType = 'MOVIE' AND p.itemId = m.id " +
            "LEFT JOIN series s ON p.mediaType = 'SERIES' AND p.itemId = s.id " +
            "LEFT JOIN episodes e ON p.mediaType = 'EPISODE' AND p.itemId = e.id " +
            "LEFT JOIN series episodeSeries ON e.seriesId = episodeSeries.id " +
            "WHERE c.sourceId = :sourceId OR m.sourceId = :sourceId OR s.sourceId = :sourceId OR episodeSeries.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<UserDataExportRow>

    @Query(
        "DELETE FROM playback_progress WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
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

    /** The episode most recently watched in [seriesId] (by this profile), or null — so opening a show can
     *  jump straight to where you left off instead of episode 1. */
    @Query(
        "SELECT itemId FROM playback_progress " +
            "WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId) " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun lastWatchedEpisodeId(profileId: Long, seriesId: Long): Long?

    /** All episode resume positions for one series (this profile), reactively — drives the Series screen's
     *  per-episode watched/progress indicators, season "watched/total" counts, the "Hide watched" filter,
     *  and the "Next up" card. */
    @Query(
        "SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = 'EPISODE' " +
            "AND itemId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)",
    )
    fun observeSeriesEpisodeProgress(profileId: Long, seriesId: Long): Flow<List<PlaybackProgressEntity>>

    /** All movie resume positions for this profile, reactively — drives the Movies grid/list
     *  per-item watched (✓) and in-progress bar indicators. Progress rows exist only for movies the
     *  user has actually started/finished, so this set is small (not the full catalogue). */
    @Query("SELECT * FROM playback_progress WHERE profileId = :profileId AND mediaType = 'MOVIE'")
    fun observeMovieProgress(profileId: Long): Flow<List<PlaybackProgressEntity>>

    /**
     * Drops resume positions orphaned by a re-sync (see FavoriteDao.purgeOrphans). Episodes are
     * excluded — they load lazily, so episode progress is kept and re-attached when the show opens.
     */
    @Query(
        "DELETE FROM playback_progress WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()
}
