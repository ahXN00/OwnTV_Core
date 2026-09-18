package tv.own.owntv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Objects

/**
 * EPG channel descriptor (XMLTV `<channel>` or an Xtream epg id). Channels link to it via their
 * `epgChannelId`.
 */
// NOTE: no foreign key to sources. EPG also comes from standalone EPG sources (stored in DataStore
// with negative ids that don't exist in the sources table), so a sourceId FK would reject them.
// EPG rows are cleared explicitly when a source/EPG-source is removed.
@Entity(
    tableName = "epg_channels",
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "epgChannelId"], unique = true),
        Index("normName"),
    ],
)
data class EpgChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val epgChannelId: String,
    val displayName: String? = null,
    /** The feed's own `<icon src>` logo, used instead of the provider logo when the user turns
     *  Settings → EPG → "Prefer EPG logos" on. Null when the feed carries no usable icon. */
    val iconUrl: String? = null,
    /**
     * [displayName] and [epgChannelId] through `EpgMatcher.normalizeForEpg`, stored at sync time.
     *
     * The matcher normalizes both of these for every candidate, on every keystroke in the "Match EPG"
     * search and again for every run of auto-match — an NFKC pass plus four regex passes each, across
     * thousands of rows, recomputed to the same answer every time. The feed only changes when it is
     * synced, so this is computed there instead.
     *
     * **Both are nullable on purpose.** A row written before this existed, or by any path that has
     * not been taught to fill them, must degrade to normalizing on the fly rather than dropping the
     * channel out of the picker. Every reader treats null as "not known yet", never as "empty".
     */
    val normName: String? = null,
    val normId: String? = null,
)

/**
 * A single programme (now/next & guide). Bounded to a rolling window (≈ now → +48h) by the EPG
 * engine, which prunes old rows. Indexed on `(epgChannelId, startMs)` for fast now/next lookups.
 */
@Entity(
    tableName = "epg_programmes",
    indices = [
        Index(value = ["epgChannelId", "startMs"]),
        Index("sourceId"),
        Index("stopMs"),
        // Time-bounded reads (a rail, an "on now" list) seek a range of start times rather than
        // walking the table. Without it a bounded window still examined every row in the database.
        Index(value = ["startMs", "stopMs"]),
        // Guide read-index (v4.0.0 EPG-perf): also created at runtime by EpgRepository.ensureEpgIndexes()
        // and in MIGRATION_3_4 — declared here so Room's schema validation expects it.
        Index(value = ["sourceId", "epgChannelId"]),
        Index(value = ["sourceId", "epgChannelId", "startMs"], unique = true, name = "index_epg_programmes_natural_key"),
    ],
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val epgChannelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    @ColumnInfo(defaultValue = "0") val contentHash: Int = 0,
)

/** Projection for the "Prefer EPG logos" override: one EPG channel id → its feed icon. */
data class EpgChannelIcon(
    val epgChannelId: String,
    val iconUrl: String,
)

/**
 * Projection for the "Match EPG" candidate list: one feed's name for one guide channel, with the
 * normalized forms the matcher works in. Both may be null on a row written before v41 or by a path
 * that does not fill them — the reader normalizes on the fly for those.
 */
data class EpgChannelName(
    val epgChannelId: String,
    val displayName: String?,
    val normName: String?,
    val normId: String?,
)

data class EpgHashProjection(
    val id: Long,
    val epgChannelId: String,
    val startMs: Long,
    val contentHash: Int,
)

data class EpgProgrammeKey(
    val epgChannelId: String,
    val startMs: Long,
)

fun EpgProgrammeEntity.computeContentHash(): Int = Objects.hash(title, description, stopMs)
