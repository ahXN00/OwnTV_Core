package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A programme reminder (v47, Stage G2): "tell me when this starts". The alarm fires [leadMinutes]
 * before [startMs]; the apps show the prompt, core only keeps time.
 *
 * Shaped after [RecordingEntity]: `profileId` cascades with its profile; `channelId` is a local id that
 * goes stale on a re-sync, so `channelName` and `epgChannelId` travel with it to keep the row readable
 * and re-matchable. A row lives for hours or days and is deleted once its programme has ended or the
 * user dismisses the prompt, so it takes no part in local sync or backup.
 */
@Entity(
    tableName = "programme_reminders",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index("startMs"),
        // One reminder per programme per channel per profile: pressing Remind me twice updates it.
        Index(value = ["profileId", "channelId", "startMs"], unique = true),
    ],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val channelId: Long,
    val channelName: String,
    val epgChannelId: String? = null,
    val title: String,
    /** The programme's own guide window. */
    val startMs: Long,
    val stopMs: Long,
    /** How long before [startMs] the prompt comes up; 0 = at the start. */
    val leadMinutes: Int,
    val createdAt: Long,
) {
    val remindAtMs: Long get() = startMs - leadMinutes * 60_000L
}
