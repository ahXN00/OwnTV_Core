package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.PlaybackQuirkEntity

/**
 * What a stream needs, shared by every profile — see [PlaybackQuirkEntity].
 *
 * Each value is written on its own and never clears the others: pinning a channel to mpv must not
 * forget that it is sound only. A row whose values have all gone back to null is dropped.
 */
@Dao
interface PlaybackQuirkDao {
    @Query("SELECT * FROM playback_quirks WHERE contentKey = :contentKey LIMIT 1")
    suspend fun get(contentKey: String): PlaybackQuirkEntity?

    /** Creates the row if it is missing, leaving an existing one exactly as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(row: PlaybackQuirkEntity)

    @Query("UPDATE playback_quirks SET enginePin = :pin, updatedAt = :now WHERE contentKey = :contentKey")
    suspend fun updateEnginePin(contentKey: String, pin: String?, now: Long)

    @Query("UPDATE playback_quirks SET audioOnly = :audioOnly, updatedAt = :now WHERE contentKey = :contentKey")
    suspend fun updateAudioOnly(contentKey: String, audioOnly: Boolean?, now: Long)

    @Query("UPDATE playback_quirks SET audioDelayMs = :audioDelayMs, updatedAt = :now WHERE contentKey = :contentKey")
    suspend fun updateAudioDelay(contentKey: String, audioDelayMs: Int?, now: Long)

    @Query(
        "DELETE FROM playback_quirks WHERE enginePin IS NULL AND audioOnly IS NULL " +
            "AND audioDelayMs IS NULL AND softwareDecode IS NULL",
    )
    suspend fun dropEmptyRows()

    /** [pin] "MPV" / "EXO", or null to forget it. */
    @Transaction
    suspend fun setEnginePin(contentKey: String, sourceId: Long, mediaType: String, pin: String?) {
        if (pin != null) insertIfMissing(PlaybackQuirkEntity(contentKey, sourceId, mediaType))
        updateEnginePin(contentKey, pin, System.currentTimeMillis())
        if (pin == null) dropEmptyRows()
    }

    /** Only a sound-only item is recorded; false forgets it. */
    @Transaction
    suspend fun setAudioOnly(contentKey: String, sourceId: Long, mediaType: String, audioOnly: Boolean) {
        if (audioOnly) insertIfMissing(PlaybackQuirkEntity(contentKey, sourceId, mediaType))
        updateAudioOnly(contentKey, audioOnly.takeIf { it }, System.currentTimeMillis())
        if (!audioOnly) dropEmptyRows()
    }

    /** [audioDelayMs] null forgets it and returns the item to the global setting. */
    @Transaction
    suspend fun setAudioDelay(contentKey: String, sourceId: Long, mediaType: String, audioDelayMs: Int?) {
        if (audioDelayMs != null) insertIfMissing(PlaybackQuirkEntity(contentKey, sourceId, mediaType))
        updateAudioDelay(contentKey, audioDelayMs, System.currentTimeMillis())
        if (audioDelayMs == null) dropEmptyRows()
    }

    /** Keys pinned to [pin], of the live kind or of every other kind. */
    @Query("SELECT contentKey FROM playback_quirks WHERE enginePin = :pin AND (mediaType = 'LIVE') = :live")
    fun observePinned(pin: String, live: Boolean): Flow<List<String>>

    @Query("SELECT contentKey FROM playback_quirks WHERE audioOnly = 1")
    fun observeAudioOnly(): Flow<List<String>>

    /** "Reset saved player choices" for films and episodes; the channels' pins are kept. */
    @Transaction
    suspend fun clearVodPins() {
        clearVodPinColumn()
        dropEmptyRows()
    }

    @Query("UPDATE playback_quirks SET enginePin = NULL WHERE mediaType != 'LIVE'")
    suspend fun clearVodPinColumn()

    @Transaction
    suspend fun clearAudioDelays() {
        clearAudioDelayColumn()
        dropEmptyRows()
    }

    @Query("UPDATE playback_quirks SET audioDelayMs = NULL")
    suspend fun clearAudioDelayColumn()

    @Query("SELECT COUNT(*) FROM playback_quirks WHERE audioDelayMs IS NOT NULL")
    fun observeAudioDelayCount(): Flow<Int>

    /** A legacy stream-URL row moves to the stable key, keeping its values (see ForceMpvStore.migrateKey). */
    @Query("UPDATE OR IGNORE playback_quirks SET contentKey = :stableKey, sourceId = :sourceId WHERE contentKey = :legacyKey")
    suspend fun migrateKey(legacyKey: String, stableKey: String, sourceId: Long)

    /** A deleted playlist takes what was remembered for its items with it (owner decision 13). */
    @Query("DELETE FROM playback_quirks WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM playback_quirks")
    suspend fun getAllOnce(): List<PlaybackQuirkEntity>

    @Query("SELECT COUNT(*) FROM playback_quirks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PlaybackQuirkEntity>)
}
