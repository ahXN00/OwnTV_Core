package tv.own.owntv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One remembered "this channel is also carried by that provider" relationship (v48, Smart Provider
 * phase 2).
 *
 * When a tune fails on the provider that owns a channel, the useful question is not "what went
 * wrong" but **"who else carries this exact channel, and has it worked lately"**. Phase 1 already
 * answers the first half on demand (`SmartProviderAnalyzer`, which matches a failed channel against
 * the other playlists' channels in memory and touches no database). This table is where that answer
 * is *kept*, so a later attempt can prefer a provider that is already known to work instead of
 * re-deriving the match every time.
 *
 * One row per (channel, provider) rather than per attempt: a provider that has failed this channel
 * four times is one fact, not four, and the two stamps below say which way it went most recently.
 * See the primary key and the timestamp notes.
 *
 * **Deliberately not part of the bulk-sync machinery.** It is absent from
 * [tv.own.owntv.core.database.OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES], so `BulkInsertHelper`
 * never drops its index during a fresh import and never has to restore it; and it is absent from
 * [tv.own.owntv.core.database.OwnTVDatabase.EXPECTED_FTS_TABLES]. Nothing here is synced, exported
 * or backed up — it is local failover memory that can be rebuilt by playing a channel again, and a
 * device with no rows simply falls back to phase 1's on-demand matching.
 *
 * @property anchorKey The failed channel, in the same stable shape the player already pins engines
 *   with: `enginePinKey`'s `"$sourceId:$mediaType:$remoteId"`
 *   ([tv.own.owntv.core.player.enginePinKey]). That shape was chosen deliberately over a local Room
 *   id because it survives a re-sync — ids do not. It is also why this table has no foreign key to
 *   `channels`: an anchor is *identified* by playlist + media type + the provider's own id, not by a
 *   row that a re-sync is free to delete and recreate.
 * @property anchorSourceId The playlist that owns the anchor channel. Not part of the key and
 *   deliberately left without a foreign key: it is provenance for the relationship (which provider
 *   the user was actually watching), useful when reconciling after a re-sync, and a channel whose
 *   anchor playlist was deleted is still a channel another playlist may serve.
 * @property sourceId The playlist that *also* carries the anchor channel. Foreign key to `sources`,
 *   CASCADE, so forgetting a playlist forgets the relationships that named it — the one case where
 *   this table must actively lose rows.
 * @property candidateRemoteId The candidate provider's own id for this channel, so a future phase
 *   can tune it directly rather than re-matching by name. Nullable on purpose: hand-made M3U rows
 *   routinely have no `remoteId` at all, and such a candidate is still a real candidate as long as
 *   it matched. `null` therefore means "matched, but only ever identifiable by name".
 * @property candidateName The matched candidate's display name, denormalised so a diagnostic, a log
 *   line or a chooser can show what was matched without joining `channels` — and so the memory stays
 *   legible after the anchor's own provider has been removed.
 * @property lastSuccessAt Epoch milliseconds when a tune on this provider last worked for this
 *   channel; **`0` means "never"**, which is why the column carries a SQL default and the constructor
 *   a Kotlin one. A row whose both stamps are `0` is a candidate that was discovered but not yet
 *   tried, and that must not be confused with one that failed.
 * @property lastFailureAt Epoch milliseconds of the most recent provider-relevant failure. Set and
 *   read independently of [lastSuccessAt]: "worked in March, failed this morning" is exactly the
 *   state a failover decision needs, so a success deliberately does not clear it.
 * @property lastFailureReason Why the last failure happened, as the player's own
 *   `PlayerFailureReason.name` (`tv.own.owntv.player`, stored as text so `core` needs no dependency
 *   on the player module). **Only provider-relevant categories belong here** — stream/session/HTTP
 *   reasons (`HTTP_509`, `ONE_SESSION_PROVIDER`, `HTTP_403`, `SSL`, …) that say something about the
 *   provider. Device-local decoder and audio categories (`DECODER_*`, `UNSUPPORTED_VIDEO`, `AUDIO`,
 *   `STEREO_FALLBACK`) say something about *this phone* and must never be written here, or one poor
 *   device would condemn a perfectly good provider for every profile on it.
 */
@Entity(
    tableName = "channel_provider_candidates",
    primaryKeys = ["anchorKey", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Required by Room for the foreign key above, and the lookup the failover path performs:
        // "what does this playlist carry". The primary key's own index cannot serve it, because
        // `sourceId` is the key's *second* column, not its prefix.
        Index("sourceId"),
    ],
)
data class ChannelProviderCandidateEntity(
    val anchorKey: String,
    val anchorSourceId: Long,
    val sourceId: Long,
    val candidateRemoteId: String? = null,
    val candidateName: String,
    @ColumnInfo(defaultValue = "0") val lastSuccessAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastFailureAt: Long = 0,
    val lastFailureReason: String? = null,
)
