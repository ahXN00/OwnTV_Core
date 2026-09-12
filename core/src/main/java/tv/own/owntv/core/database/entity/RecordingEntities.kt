package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus

/**
 * One live recording: scheduled, running, finished, or the reason it never happened.
 *
 * **None of this syncs and none of it is backed up (D6).** A recording is a file on one device's
 * disk; a row that arrived on another device would name a file that is not there, which is worse
 * than saying nothing. So there are no tombstones here, `UserDataResolver` does not know this table,
 * and neither does the backup writer. That is deliberate, not an omission — if a later change adds
 * this table to either, it is changing an owner decision, not filling a gap.
 *
 * **Deleting the playlist does not delete the recording.** `sourceId` is an ordinary column and not
 * a foreign key, precisely so removing a playlist cannot cascade away a row whose file is still on
 * disk — D2's "never destroy what was captured" applies to the record of it too. `profileId` *is* a
 * cascade, matching every other user-data table: a deleted profile takes its own rows with it.
 *
 * **Two windows, not one.** `programmeStartMs`/`programmeStopMs` are the EPG's — what the user
 * pointed at, and what the row should always be described by. `startMs`/`stopMs` are what is
 * actually recorded, pre-roll and post-roll included (A5). Keeping both is what lets the screen say
 * "the 9 o'clock news" while the recorder runs from 20:58 to 21:35.
 *
 * `channelId` is a local id and goes stale on a re-sync, exactly as `DownloadEntity.itemId` does.
 * `channelName` and `epgChannelId` are stored beside it so a row stays readable, and re-matchable,
 * after the catalogue underneath it has been replaced.
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index("status"),
        // The scheduler asks "what is due?" and the clash check asks "what overlaps?"; both walk the
        // recorded window, not the programme's.
        Index(value = ["startMs", "stopMs"]),
        Index("sourceId"),
        Index("ruleId"),
        // One row per programme per channel per profile: pressing Record twice updates rather than
        // schedules the same thing again.
        Index(value = ["profileId", "channelId", "programmeStartMs"], unique = true),
    ],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Which playlist's connection this spends (D10/D11). A plain column — see the class KDoc. */
    val sourceId: Long,
    val channelId: Long,
    val channelName: String,
    val channelIconUrl: String? = null,
    /** The stable guide key, for re-matching after a re-sync and for series rules (A11). */
    val epgChannelId: String? = null,
    /**
     * The stream as it was known at scheduling time. The recorder resolves a fresh URL per attempt
     * anyway — a Stalker portal's links expire — so this is the starting point, never the authority.
     */
    val streamUrl: String,
    /** The channel's own headers, carried so a recording sends what playback would. */
    val httpHeaders: String? = null,
    val title: String,
    val description: String? = null,
    /** The programme's own guide window: what the user asked for. */
    val programmeStartMs: Long,
    val programmeStopMs: Long,
    /** The window actually recorded, pre-roll and post-roll included. */
    val startMs: Long,
    val stopMs: Long,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    /** Why it failed or was missed. [RecordingFailure.NONE] whenever nothing went wrong. */
    val failure: RecordingFailure = RecordingFailure.NONE,
    val filePath: String? = null,
    val bytes: Long = 0,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** The series rule that created this row, null when the user scheduled it by hand (A11). */
    val ruleId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * "Record every showing of this title on this channel" (D7) — the standing instruction, not the
 * recordings it produces. Each showing the guide reveals becomes a [RecordingEntity] carrying this
 * rule's id.
 *
 * Scoped to one channel on purpose: "every showing anywhere" across a 20 000-channel playlist is a
 * different and much worse feature, and nobody asked for it.
 *
 * [titleKey] is the folded form the guide is matched against — the raw [title] is what the user
 * reads. Folding lives with the matcher rather than here, so the rule for what counts as the same
 * programme is written once.
 *
 * Like [RecordingEntity], this never syncs and is never backed up (D6).
 */
@Entity(
    tableName = "recording_rules",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "channelId", "titleKey"], unique = true),
    ],
)
data class RecordingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val sourceId: Long,
    val channelId: Long,
    val channelName: String,
    val epgChannelId: String? = null,
    /** The programme title as the user saw it in the guide. */
    val title: String,
    /** The folded title the guide is matched against. */
    val titleKey: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
