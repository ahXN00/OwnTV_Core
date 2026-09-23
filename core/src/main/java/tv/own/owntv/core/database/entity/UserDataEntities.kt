package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType

/*
 * User data is scoped per profile. `itemId` is the local id of a channel/movie/series/episode and is
 * disambiguated by `mediaType` (it can't be a single foreign key since it points at several tables),
 * so referential cleanup of these rows is handled in the repository layer.
 */

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "watch_history",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "watchedAt"]),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val watchedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class PlaybackProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Per-profile manual ordering for individual items ("Move up/down"). Each row pins one content item
 * (channel/movie/series) to a [position] within a [contextKey] — a category's stable key for a folder,
 * or [FAV_CONTEXT] for the Favorites list. The browsing queries LEFT JOIN this table and order by
 * [position] first, falling back to the natural order for items without a row. `itemId` is volatile
 * (content is clear-then-insert on every sync), so these rows are snapshotted with stable keys and
 * re-attached after a sync by UserDataResolver, just like favorites/history.
 */
@Entity(
    tableName = "content_order",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "contextKey"]),
        Index(value = ["profileId", "mediaType", "contextKey", "itemId"], unique = true),
    ],
)
data class ContentOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** LIVE / MOVIE / SERIES — never EPISODE (episodes aren't reorderable). */
    val mediaType: MediaType,
    /** A category stable key (CustomizeKeys.category) for a folder, or [FAV_CONTEXT] for Favorites. */
    val contextKey: String,
    val itemId: Long,
    val position: Int,
) {
    companion object {
        /** Sentinel [contextKey] for the per-section Favorites list. */
        const val FAV_CONTEXT = "__fav__"
    }
}

/**
 * Per-profile, per-item playback preferences: the zoom/aspect mode and the volume level the user
 * last chose while watching one specific channel, film or episode. Both are nullable and a null
 * means "no per-item choice — follow the global default in Settings", which is also why there is no
 * row at all until the user changes something in the player.
 *
 * [contentKey] is deliberately NOT the volatile Room `itemId` that favorites/history use. It is the
 * P6 stable identity — `sourceId:mediaType:remoteId` from
 * [tv.own.owntv.core.player.enginePinKey], falling back to the stream URL for rows with no provider
 * id — exactly like the engine pins ([tv.own.owntv.core.player.VodEngineStore],
 * `ForceMpvStore`) and the subtitle tables. That key survives the clear-then-insert of a re-sync on
 * its own, so unlike `content_order` these rows need no snapshot/relink pass: after a re-sync the
 * same film computes the same key and finds its own row again.
 */
@Entity(
    tableName = "playback_prefs",
    primaryKeys = ["profileId", "contentKey"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("profileId"), Index("sourceId")],
)
data class PlaybackPrefsEntity(
    val profileId: Long,
    /** [tv.own.owntv.core.player.enginePinKey] result, or the stream URL when the row has none. */
    val contentKey: String,
    /** A [tv.own.owntv.player.ZoomMode] name; null = follow the global default zoom. */
    val zoomMode: String? = null,
    /** Volume percent (0–150, the shared boost ceiling); null = follow the global default volume. */
    val volumeBoost: Int? = null,
    /**
     * A/V-sync offset in ms (-5000..5000, positive = audio delayed); null = follow the global default.
     *
     * Per item because lip-sync error belongs to the **stream**, not to the user: one badly-muxed film
     * or one channel whose provider mis-times its audio needs the correction, and carrying that same
     * correction onto the next, correctly-muxed item would break it.
     */
    val audioDelayMs: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v44: the playlist [contentKey] belongs to, so deleting a playlist deletes its rows (owner
     * decision 13). `-1` = unknown — a legacy stream-URL key, or a row written before v44.
     */
    @androidx.room.ColumnInfo(defaultValue = "-1") val sourceId: Long = -1,
    /** v44: the audio track language this profile last chose here (N12); null = follow the preference. */
    val audioLang: String? = null,
    /** v44: the subtitle track language this profile last chose here; `""` = subtitles off. */
    val subtitleLang: String? = null,
)

/**
 * What a **stream** needs, whoever is watching (v44, owner decision 10). [playback_prefs][PlaybackPrefsEntity]
 * is what a *person* prefers — zoom and volume — and stays per profile; the facts here belong to the
 * stream and the device's output, so they are shared: the engine a channel only plays on, whether it is
 * sound only, its lip-sync correction, whether it needs software decoding.
 *
 * Keyed by [tv.own.owntv.core.player.enginePinKey] like every per-item store. Replaces the DataStore
 * sets `ForceMpvStore` / `VodEngineStore` / `AudioOnlyStore`, which are copied in once at startup and
 * kept readable for one release (owner decision 14). [sourceId] lets a playlist's rows go with it.
 */
@Entity(
    tableName = "playback_quirks",
    indices = [Index("sourceId")],
)
data class PlaybackQuirkEntity(
    @PrimaryKey val contentKey: String,
    /** `-1` = unknown (a legacy stream-URL key). */
    val sourceId: Long,
    /**
     * `LIVE`, `MOVIE` or `EPISODE` — the kind of item, recorded by whoever wrote the row. The key
     * alone cannot say: an item with no provider id is keyed by its stream URL. Lets the channel
     * pins and the film pins be counted and reset separately, as their two Settings rows do.
     */
    val mediaType: String,
    /** "MPV" or "EXO"; null = no pin, follow the settings. */
    val enginePin: String? = null,
    val audioOnly: Boolean? = null,
    /** A/V-sync offset in ms (-5000..5000, positive = audio delayed); null = follow the global default. */
    val audioDelayMs: Int? = null,
    val softwareDecode: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Membership rows of the user's custom combined categories (issue #87). One row pins one content
 * item (channel/movie/series) to a [position] inside a custom category, identified by its stable
 * DataStore key — `CustomizeKeys` with the `"custom:"` prefix, e.g. `"custom:1b2f…"` — in
 * [contextKey]. Modeled EXACTLY on [ContentOrderEntity]: the same (profileId, mediaType, contextKey,
 * itemId) uniqueness, the same position semantics (a custom category's rails JOIN this table and
 * order by [position] first), so Move works inside custom categories through the identical
 * content_order machinery (the custom category's own order rows live HERE, not in content_order).
 *
 * The membership itself is Room (not DataStore) on purpose: the browse rails need to JOIN
 * `custom_category_members` against channels/movies/series, and DataStore string keys can't be
 * JOINed. The catalog tables are clear-then-insert on every sync, so [itemId] is volatile — these
 * rows are snapshotted with stable keys and re-attached after a sync by UserDataResolver, exactly
 * like [ContentOrderEntity] and favorites/history.
 */
@Entity(
    tableName = "custom_category_members",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "mediaType", "contextKey"]),
        Index(value = ["profileId", "mediaType", "contextKey", "itemId"], unique = true),
    ],
)
data class CustomCategoryMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** LIVE / MOVIE / SERIES — never EPISODE (episodes aren't added to custom categories). */
    val mediaType: MediaType,
    /** The custom category's stable key (`"custom:<uuid>"`, see CustomizeKeys.CUSTOM_PREFIX). */
    val contextKey: String,
    val itemId: Long,
    val position: Int,
)

/**
 * A user data row the user deleted, remembered so the deletion survives a sync (v36).
 *
 * Local sync merges — it never clobbers — so without this a favorite removed on the phone simply
 * looks *absent* there and *present* on the TV, and the next merge helpfully puts it back. A
 * tombstone makes the absence deliberate: "this was deleted at this moment", which the merge can
 * compare against the other device's timestamp the same way it compares two live rows.
 *
 * [identity] is NOT the volatile `itemId`. It is the same stable content key
 * [tv.own.owntv.core.backup.UserDataResolver] exports into a backup — source id, provider remote id
 * and name (or show + season/episode) as a canonically-ordered JSON object — because the row being
 * deleted has to be recognisable on a device whose ids are entirely different, and across the
 * clear-then-insert of a re-sync on this one.
 *
 * Only genuine user deletions are recorded. Housekeeping deletes — the orphan purges after a
 * re-sync, a profile cascade — must never write one: that would turn "your playlist was refreshed"
 * into "delete this on every other device too".
 */
@Entity(
    tableName = "user_data_tombstones",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "kind", "identity"], unique = true),
        Index("deletedAt"),
    ],
)
data class UserDataTombstoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Which list the row was deleted from: `fav`, `his`, `prog` or `member` — UserDataResolver's kinds. */
    val kind: String,
    /** Canonical stable content key JSON; see [tv.own.owntv.core.backup.UserDataResolver.canonicalIdentity]. */
    val identity: String,
    val deletedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index("status"),
        Index(value = ["profileId", "mediaType", "itemId"], unique = true),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Movies & series episodes only — never LIVE. */
    val mediaType: MediaType,
    val itemId: Long,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String,
    val filePath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Per-profile, per-series presentation order for the series episode view ("Sorting" popup): the
 * season rail and the episode list are ordered independently, each Oldest-first (ascending) or
 * Newest-first (descending). A series with no row uses the defaults (both ascending), so the table
 * only ever holds the shows the user actually changed.
 *
 * PRESENTATION ONLY — playback order (autoplay next episode) is never affected by these values.
 *
 * Deliberately a separate user-data table rather than a column on `series`: the catalog tables are
 * bulk-synced and would wipe it. Like favorites/history/content_order it foreign-keys the profile
 * only — `seriesId` is a volatile local id (content is re-inserted on sync), so stale rows are
 * dropped by [purgeOrphans] and re-attached from stable keys by UserDataResolver.
 */
@Entity(
    tableName = "series_sort_order",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "seriesId"], unique = true),
    ],
)
data class SeriesSortOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Local `series.id`. Volatile across a re-sync/restore — see the class KDoc. */
    val seriesId: Long,
    /** Season rail order. false = Oldest first (default), true = Newest first. */
    val seasonsDescending: Boolean = false,
    /** Episode list order. false = Oldest first (default), true = Newest first. */
    val episodesDescending: Boolean = false,
)
