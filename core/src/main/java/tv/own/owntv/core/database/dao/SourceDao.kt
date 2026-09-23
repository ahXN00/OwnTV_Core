package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.HlsSupport

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("UPDATE sources SET lastSyncAt = :timestamp WHERE id = :id")
    suspend fun markSynced(id: Long, timestamp: Long)

    /** The verdict from "Test HLS support", which actually requested an `.m3u8` stream. Overwrites
     *  whatever the panel merely *claimed* at the last sync. */
    @Query("UPDATE sources SET hlsSupported = :hlsSupported WHERE id = :id")
    suspend fun updateHlsSupport(id: Long, hlsSupported: HlsSupport)

    /**
     * What the panel reported about HLS at the last sync; [HlsSupport.UNKNOWN] until it has run once.
     *
     * Deliberately does NOT overwrite a stored [HlsSupport.SUPPORTED] with [HlsSupport.UNSUPPORTED]:
     * plenty of panels serve HLS without listing it in `allowed_output_formats`, so a proven "yes" from
     * the test button must survive the next sync's weaker claim.
     */
    @Query(
        "UPDATE sources SET hlsSupported = :hlsSupported WHERE id = :id " +
            "AND (:hlsSupported = 1 OR hlsSupported != 1)",
    )
    suspend fun updateHlsSupportFromSync(id: Long, hlsSupported: HlsSupport)

    /** Simultaneous streams the provider allows (Xtream `user_info.max_connections`); 0 = unknown. */
    @Query("UPDATE sources SET maxConnections = :maxConnections WHERE id = :id")
    suspend fun updateMaxConnections(id: Long, maxConnections: Int)

    /**
     * Record a *measured* limit and when it was measured.
     *
     * Separate from [updateMaxConnections], which is the provider's own published number, because the
     * timestamp is what stops the measurement running again on every sync — and because a measurement
     * that concluded nothing still has to be remembered as "we looked", or it would be repeated
     * forever on exactly the playlists it cannot answer for.
     */
    @Query("UPDATE sources SET maxConnections = :maxConnections, maxConnectionsProbedAt = :probedAt WHERE id = :id")
    suspend fun updateProbedConnections(id: Long, maxConnections: Int, probedAt: Long)

    @Query("UPDATE sources SET preferHls = :preferHls WHERE id = :id")
    suspend fun updatePreferHls(id: Long, preferHls: Boolean)

    /** Per-playlist "Pre-buffer" override in seconds; `-1` follows the global setting. */
    @Query("UPDATE sources SET livePrerollSecs = :secs WHERE id = :id")
    suspend fun updateLivePreroll(id: Long, secs: Int)

    /** Per-playlist Live TV engine override (an `EnginePreference` name); `null` follows the global setting. */
    @Query("UPDATE sources SET liveEnginePreference = :preference WHERE id = :id")
    suspend fun updateLiveEnginePreference(id: Long, preference: String?)

    /** Per-playlist Live latency override (a `LiveLatency` name); `null` follows the global setting. The
     *  seconds are written alongside so a CUSTOM choice can never land without its value. */
    @Query("UPDATE sources SET liveLatencyMode = :mode, liveLatencyCustomSecs = :customSecs WHERE id = :id")
    suspend fun updateLiveLatency(id: Long, mode: String?, customSecs: Int)

    /** Per-playlist catch-up time zone (a `CatchupTimezone` name); `null` follows the global setting. The
     *  offset is written alongside so a MANUAL choice can never land without its value. */
    @Query("UPDATE sources SET catchupTimezone = :mode, catchupOffsetMin = :offsetMin WHERE id = :id")
    suspend fun updateCatchupTimezone(id: Long, mode: String?, offsetMin: Int?)

    /** Per-playlist Movies & Series engine (an `EnginePreference` name); `null` follows the global setting. */
    @Query("UPDATE sources SET vodEnginePreference = :preference WHERE id = :id")
    suspend fun updateVodEnginePreference(id: Long, preference: String?)

    /** Per-playlist "Give up after" in seconds (0 = never); `null` follows the global setting. */
    @Query("UPDATE sources SET liveTuneTimeoutSecs = :secs WHERE id = :id")
    suspend fun updateLiveTuneTimeout(id: Long, secs: Int?)

    /** Per-playlist HTTP Referer; `null` sends none beyond what each stream declares. */
    @Query("UPDATE sources SET httpReferer = :referer WHERE id = :id")
    suspend fun updateHttpReferer(id: Long, referer: String?)

    // --- profile <-> source links (hybrid model) ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: ProfileSourceCrossRef)

    @Query("DELETE FROM profile_source WHERE profileId = :profileId AND sourceId = :sourceId")
    suspend fun unlink(profileId: Long, sourceId: Long)

    @Query(
        "SELECT s.* FROM sources s " +
            "INNER JOIN profile_source ps ON ps.sourceId = s.id " +
            "WHERE ps.profileId = :profileId ORDER BY s.createdAt ASC",
    )
    fun observeForProfile(profileId: Long): Flow<List<SourceEntity>>

    @Query("SELECT sourceId FROM profile_source WHERE profileId = :profileId")
    suspend fun sourceIdsForProfile(profileId: Long): List<Long>

    @Query("SELECT profileId FROM profile_source WHERE sourceId = :sourceId")
    suspend fun profileIdsForSource(sourceId: Long): List<Long>

    @Query("SELECT id FROM sources")
    suspend fun allSourceIds(): List<Long>

    @Query("SELECT * FROM sources ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<SourceEntity>

    @Query("SELECT * FROM profile_source")
    suspend fun allLinks(): List<ProfileSourceCrossRef>

    @Query("DELETE FROM sources")
    suspend fun deleteAllSources()
}
