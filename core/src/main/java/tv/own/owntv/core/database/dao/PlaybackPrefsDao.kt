package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.PlaybackPrefsEntity

/**
 * Per-item zoom / volume (and, from v44, track languages) the player remembers for one profile. The
 * audio delay moved to `playback_quirks` in v44 — lip-sync belongs to the stream, not the person. See [PlaybackPrefsEntity] for why the
 * key is the stable content key rather than a Room id (no re-sync relink needed).
 *
 * The three values are written independently — changing the zoom must not wipe a remembered volume —
 * so each has its own upsert that preserves the other columns.
 */
@Dao
interface PlaybackPrefsDao {
    @Query("SELECT * FROM playback_prefs WHERE profileId = :profileId AND contentKey = :contentKey LIMIT 1")
    suspend fun get(profileId: Long, contentKey: String): PlaybackPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PlaybackPrefsEntity)

    /** Remember [zoomMode] (null = "follow the global default") without touching the volume. */
    @Transaction
    suspend fun setZoom(profileId: Long, contentKey: String, zoomMode: String?) {
        val existing = get(profileId, contentKey)
        if (existing == null && zoomMode == null) return
        upsert(existing?.copy(zoomMode = zoomMode, updatedAt = System.currentTimeMillis()) ?: fresh(profileId, contentKey).copy(zoomMode = zoomMode))
    }

    /** Remember [volumeBoost] (null = "follow the global default") without touching the zoom. */
    @Transaction
    suspend fun setVolume(profileId: Long, contentKey: String, volumeBoost: Int?) {
        val existing = get(profileId, contentKey)
        if (existing == null && volumeBoost == null) return
        upsert(existing?.copy(volumeBoost = volumeBoost, updatedAt = System.currentTimeMillis()) ?: fresh(profileId, contentKey).copy(volumeBoost = volumeBoost))
    }

    /** Remember the audio track's language (v44) without touching anything else. */
    @Transaction
    suspend fun setAudioLang(profileId: Long, contentKey: String, audioLang: String?) {
        val existing = get(profileId, contentKey)
        if (existing == null && audioLang == null) return
        upsert(existing?.copy(audioLang = audioLang, updatedAt = System.currentTimeMillis()) ?: fresh(profileId, contentKey).copy(audioLang = audioLang))
    }

    /** Remember the subtitle choice (v44) — a language, or `PlaybackPrefsStore.SUBTITLES_OFF`. */
    @Transaction
    suspend fun setSubtitleLang(profileId: Long, contentKey: String, subtitleLang: String?) {
        val existing = get(profileId, contentKey)
        if (existing == null && subtitleLang == null) return
        upsert(existing?.copy(subtitleLang = subtitleLang, updatedAt = System.currentTimeMillis()) ?: fresh(profileId, contentKey).copy(subtitleLang = subtitleLang))
    }

    /**
     * A new row for [contentKey], knowing its playlist. Every setter copies an existing row rather than
     * rebuilding it, so a zoom change can never clear the volume, the playlist or the track choices.
     */
    private fun fresh(profileId: Long, contentKey: String) = PlaybackPrefsEntity(
        profileId = profileId,
        contentKey = contentKey,
        sourceId = tv.own.owntv.core.player.sourceIdOfPinKey(contentKey),
    )

    /** v44: every row written before its `sourceId` column existed learns it from its key. */
    @Query(
        "UPDATE playback_prefs SET sourceId = CAST(substr(contentKey, 1, instr(contentKey, ':') - 1) AS INTEGER) " +
            "WHERE sourceId = -1 AND contentKey GLOB '[0-9]*:*'",
    )
    suspend fun fillSourceIds()

    /** The per-item audio delays written before v44, oldest first, for the move to `playback_quirks`. */
    @Query("SELECT * FROM playback_prefs WHERE audioDelayMs IS NOT NULL ORDER BY updatedAt ASC")
    suspend fun legacyAudioDelays(): List<PlaybackPrefsEntity>

    /** A deleted playlist takes every profile's zoom, volume and track memory for its items with it. */
    @Query("DELETE FROM playback_prefs WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM playback_prefs")
    suspend fun getAllOnce(): List<PlaybackPrefsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PlaybackPrefsEntity>)

    // --- The two Settings escape hatches. Zoom and volume are reset independently: a user who wants
    // every film back at the default aspect has not asked to lose the levels they set on quiet ones.
    // Each clear nulls only its own column, then drops rows that no longer remember anything.

    @Query("UPDATE playback_prefs SET zoomMode = NULL")
    suspend fun clearZoomColumn()

    @Query("UPDATE playback_prefs SET volumeBoost = NULL")
    suspend fun clearVolumeColumn()

    /** v44: the delays now live in `playback_quirks`; this column is emptied as they move. */
    @Query("UPDATE playback_prefs SET audioDelayMs = NULL")
    suspend fun clearAudioDelayColumn()

    @Query(
        "DELETE FROM playback_prefs WHERE zoomMode IS NULL AND volumeBoost IS NULL AND audioDelayMs IS NULL " +
            "AND audioLang IS NULL AND subtitleLang IS NULL",
    )
    suspend fun dropEmptyRows()

    /** Forget every per-item zoom, keeping the per-item volumes. */
    @Transaction
    suspend fun clearZoom() {
        clearZoomColumn()
        dropEmptyRows()
    }

    /** Forget every per-item volume, keeping the per-item zoom modes. */
    @Transaction
    suspend fun clearVolume() {
        clearVolumeColumn()
        dropEmptyRows()
    }

    /** Live counts for the Settings rows' chips ("3 saved" / "None saved"). */
    @Query("SELECT COUNT(*) FROM playback_prefs WHERE zoomMode IS NOT NULL")
    fun observeZoomCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_prefs WHERE volumeBoost IS NOT NULL")
    fun observeVolumeCount(): Flow<Int>
}
