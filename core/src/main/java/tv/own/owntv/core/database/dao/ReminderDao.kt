package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ReminderEntity

/** Programme reminders, per profile. Not synced, not backed up — see [ReminderEntity]. */
@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Query("DELETE FROM programme_reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM programme_reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    /** Soonest first — the guide marks these with a bell. */
    @Query("SELECT * FROM programme_reminders WHERE profileId = :profileId ORDER BY startMs ASC")
    fun observeForProfile(profileId: Long): Flow<List<ReminderEntity>>

    /** Every profile's: alarms are armed for all of them, whichever profile is active. */
    @Query("SELECT * FROM programme_reminders ORDER BY startMs ASC")
    suspend fun all(): List<ReminderEntity>

    /** All of them as they change; which are due is decided against the clock (ReminderSchedule.due). */
    @Query("SELECT * FROM programme_reminders ORDER BY startMs ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("DELETE FROM programme_reminders WHERE stopMs <= :now")
    suspend fun deleteEnded(now: Long): Int
}
