package tv.own.owntv.core.database.entity

import androidx.compose.runtime.Immutable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Objects
import tv.own.owntv.core.model.MediaType

/**
 * A category/folder within a source for a given media type (LIVE / MOVIE / SERIES). The Layer-2 rail
 * is built from these. `remoteId` is the provider's category id (used to dedupe on re-sync).
 */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "mediaType"]),
        Index(value = ["sourceId", "mediaType", "remoteId"], unique = true),
    ],
)
@Immutable
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val mediaType: MediaType,
    val name: String,
    val remoteId: String? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("sourceId"),
        Index("categoryId"),
        Index("name"),
        Index("epgChannelId"),
        Index(value = ["sourceId", "remoteId"], unique = true),
        // Composite grid read-indices (v6): A–Z order (ORDER BY name) and playlist/provider order
        // (ORDER BY sortOrder, name), for both the whole-source list and per-category. Mirrors movies/series
        // so all three browse grids (Live/Movies/Series) are index-served in either sort.
        Index(value = ["sourceId", "name"]),
        Index(value = ["categoryId", "name"]),
        Index(value = ["sourceId", "sortOrder", "name"]),
        Index(value = ["categoryId", "sortOrder", "name"]),
        Index(value = ["sourceId", "number"]),
    ],
)
@Immutable
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    val name: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    /** tvg-id (M3U) / epg_channel_id (Xtream) — links to EPG. */
    val epgChannelId: String? = null,
    val number: Int? = null,
    val remoteId: String? = null,
    val sortOrder: Int = 0,
    /** Catch-up/archive available for this channel (Xtream `tv_archive` / M3U `catchup`). */
    val catchup: Boolean = false,
    /** How many days back the archive goes (Xtream `tv_archive_duration` / M3U `catchup-days`). */
    val catchupDays: Int = 0,
    /** M3U `catchup-source` URL template (with `${start}`/`${timestamp}`/… placeholders). Null for
     *  Xtream, whose timeshift URL is built from the source credentials instead. */
    val catchupSource: String? = null,
    /** M3U `catchup` type — "default" / "append" / "shift" / "flussonic" / "xc". Decides HOW
     *  [catchupSource] is used; without it the `append` convention can't be honoured (F17). */
    val catchupType: String? = null,
    /** Per-channel HTTP request headers as `Key: Value` per line — see
     *  [tv.own.owntv.core.network.StreamHeaders]. Set from the M3U's `#EXTVLCOPT` / `#EXTHTTP` /
     *  `#KODIPROP` directives or a `url|Key=Value` suffix; null for providers that carry none (F16). */
    val httpHeaders: String? = null,
    /** Widevine/ClearKey licence details — see [tv.own.owntv.core.drm.DrmConfig] (v33, #115). Non-null
     *  makes this channel ExoPlayer-only: mpv/FFmpeg can decrypt CENC given a raw key
     *  (`--demuxer-lavf-o=decryption_key=…`) but has no CDM, so it cannot FETCH one from a licence
     *  server — which is the only thing a `license_key` URL offers. The ladder must not offer mpv. */
    val drmConfig: String? = null,
    @ColumnInfo(defaultValue = "0") val contentHash: Int = 0,
)

/**
 * The stream URL to tune: the channel's own `.ts`, swapped to `.m3u8` when the playlist's "Prefer HLS"
 * is on for an Xtream source.
 *
 * **Deliberately NOT gated on `hlsSupported`** — this KDoc used to claim it was, and two earlier audits
 * recommended adding that gate on the strength of the comment. It would be wrong. Panels lie about
 * `allowed_output_formats`: gating here breaks HLS for every user on an under-reporting panel, and for
 * every source that has not finished syncing yet. The authoritative note is on the field itself in
 * `ProfileEntities.kt`. A channel whose `.m3u8` genuinely doesn't work is handled after the fact, per
 * channel and per engine, by `LiveStreamQuirks.rememberNoHlsVariant`.
 */
fun ChannelEntity.playStreamUrl(source: SourceEntity?): String =
    resolveStreamUrl(streamUrl, source)

/** Swaps .ts to .m3u8 if preferHls is active for an Xtream source. See [playStreamUrl] for why this is
 *  not gated on `hlsSupported`. */
fun resolveStreamUrl(url: String, source: SourceEntity?): String {
    if (source != null && source.type == tv.own.owntv.core.model.SourceType.XTREAM && source.preferHls) {
        if (url.endsWith(".ts", ignoreCase = true)) {
            return url.dropLast(3) + ".m3u8"
        }
    }
    return url
}

@Entity(
    tableName = "movies",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("sourceId"),
        Index("categoryId"),
        Index("name"),
        // Composite (filter + order) indices so the grid's "WHERE sourceId/categoryId ORDER BY name" never
        // falls back to a full temp-B-tree sort of the whole table (100k+ rows → 2–3s) — it seeks + scans.
        // A–Z order (ORDER BY name) uses the first pair; playlist/provider order (ORDER BY sortOrder, name)
        // — the v6 default for Movies/Series/Live — uses the second pair. Both sort paths are index-served.
        Index(value = ["sourceId", "name"]),
        Index(value = ["categoryId", "name"]),
        Index(value = ["sourceId", "sortOrder", "name"]),
        Index(value = ["categoryId", "sortOrder", "name"]),
        Index(value = ["sourceId", "remoteId"], unique = true),
        // Rating sort (v11): "ORDER BY rating DESC, name" is index-served, not a full temp-B-tree sort.
        Index(value = ["sourceId", "rating", "name"]),
        Index(value = ["categoryId", "rating", "name"]),
        // Date added sort (v21): same shape as the rating pair, for "ORDER BY addedAt DESC, sortOrder DESC".
        Index(value = ["sourceId", "addedAt", "sortOrder"]),
        Index(value = ["categoryId", "addedAt", "sortOrder"]),
        Index(value = ["sourceId", "titleSignature", "parsedYear"]),
    ],
)
@Immutable
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val durationSecs: Int? = null,
    val plot: String? = null,
    val streamUrl: String,
    val containerExt: String? = null,
    val remoteId: String? = null,
    val addedAt: Long? = null,
    val sortOrder: Int = 0,
    /** Per-item HTTP request headers as `Key: Value` per line — see
     *  [tv.own.owntv.core.network.StreamHeaders]. The VOD twin of [ChannelEntity.httpHeaders]: an M3U
     *  movie entry carries the same `#EXTVLCOPT` / `#EXTHTTP` / `#KODIPROP` directives a channel does,
     *  and a restream that answers 403 without them does so for files exactly as for live (v28). */
    val httpHeaders: String? = null,
    /** Widevine/ClearKey licence details — see [ChannelEntity.drmConfig] (v33). */
    val drmConfig: String? = null,
    @ColumnInfo(defaultValue = "0") val contentHash: Int = 0,
    @ColumnInfo(defaultValue = "''") val canonicalTitle: String = "",
    @ColumnInfo(defaultValue = "''") val titleSignature: String = "",
    val parsedYear: Int? = null,
    val providerLanguage: String? = null,
    @ColumnInfo(defaultValue = "0") val qualityRank: Int = 0,
    val advertisedCapabilities: String? = null,
)

@Entity(
    tableName = "series",
    foreignKeys = [
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("sourceId"),
        Index("categoryId"),
        Index("name"),
        // Composite (filter + order) indices — see MovieEntity: avoids a full table sort on the Series grid.
        // A–Z (ORDER BY name) → first pair; playlist/provider (ORDER BY sortOrder, name) → second pair.
        Index(value = ["sourceId", "name"]),
        Index(value = ["categoryId", "name"]),
        Index(value = ["sourceId", "sortOrder", "name"]),
        Index(value = ["categoryId", "sortOrder", "name"]),
        Index(value = ["sourceId", "remoteId"], unique = true),
        // Rating sort (v11): "ORDER BY rating DESC, name" is index-served, not a full temp-B-tree sort.
        Index(value = ["sourceId", "rating", "name"]),
        Index(value = ["categoryId", "rating", "name"]),
        // Date added sort (v21): same shape as the rating pair, for "ORDER BY addedAt DESC, sortOrder DESC".
        Index(value = ["sourceId", "addedAt", "sortOrder"]),
        Index(value = ["categoryId", "addedAt", "sortOrder"]),
        Index(value = ["sourceId", "titleSignature", "parsedYear"]),
    ],
)
@Immutable
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val plot: String? = null,
    val remoteId: String? = null,
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val contentHash: Int = 0,
    val addedAt: Long? = null,
    /**
     * When this show's episode list was last fetched from the provider (epoch ms; 0 = never).
     * Episodes are loaded lazily on open and used to be cached forever, so a show never gained the
     * episodes the provider added after the first open (S8). This drives the freshness check in
     * `SeriesRepository.loadEpisodes`. Deliberately *not* in [computeContentHash] — it is local
     * bookkeeping, not provider content.
     */
    @ColumnInfo(defaultValue = "0") val episodesSyncedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val canonicalTitle: String = "",
    @ColumnInfo(defaultValue = "''") val titleSignature: String = "",
    val parsedYear: Int? = null,
    val providerLanguage: String? = null,
    @ColumnInfo(defaultValue = "0") val qualityRank: Int = 0,
    val advertisedCapabilities: String? = null,
)

@Entity(
    tableName = "seasons",
    foreignKeys = [
        ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("seriesId")],
)
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val seasonNumber: Int,
    val name: String? = null,
    val remoteId: String? = null,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SeasonEntity::class, parentColumns = ["id"], childColumns = ["seasonId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("seriesId"),
        Index("seasonId"),
        Index("name"),
        Index(value = ["seriesId", "remoteId"], unique = true),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val plot: String? = null,
    val streamUrl: String,
    val durationSecs: Int? = null,
    val containerExt: String? = null,
    val remoteId: String? = null,
    /** Per-item HTTP request headers — see [MovieEntity.httpHeaders] (v28). */
    val httpHeaders: String? = null,
    /** Widevine/ClearKey licence details — see [ChannelEntity.drmConfig] (v33). */
    val drmConfig: String? = null,
    /**
     * When the episode first aired, epoch ms, or null when nothing said (v37).
     *
     * From the provider's own `releasedate` / `air_date` where it offers one, else the date TMDB has
     * for that season and episode. Requested by a user with series running to thousands of episodes,
     * where a list of titles gives no way to tell which one is which — the number alone stops helping
     * long before episode nine hundred.
     */
    val airDateMs: Long? = null,
)

/**
 * What a resync needs to know about a row it already holds. [sortOrder] is deliberately *outside*
 * `computeContentHash()`: folding it in would change every stored hash at once and turn the next
 * resync of a large catalog into a full rewrite, so the syncer compares it separately (S1).
 */
data class ContentHashProjection(
    val remoteId: String,
    val id: Long,
    val contentHash: Int,
    val sortOrder: Int,
)

/**
 * The v26 fields ([ChannelEntity.catchupType] / [ChannelEntity.httpHeaders]) and the v33
 * [ChannelEntity.drmConfig] are folded in ONLY when the channel actually carries one. Adding them
 * unconditionally would change every stored hash at once and turn the next resync of a 100k-channel
 * playlist into a full rewrite for everybody; this way only the (rare) channels that use them pay a
 * single re-upsert, while a later change to a header, a catch-up type or a licence URL still propagates.
 */
fun ChannelEntity.computeContentHash(): Int {
    val base = Objects.hash(
        sourceId, categoryId, name, logoUrl, streamUrl,
        epgChannelId, number, remoteId, catchup, catchupDays, catchupSource,
    )
    if (catchupType == null && httpHeaders == null && drmConfig == null) return base
    val withV26 = Objects.hash(base, catchupType, httpHeaders)
    return if (drmConfig == null) withV26 else Objects.hash(withV26, drmConfig)
}

/** [httpHeaders] and [MovieEntity.drmConfig] are folded in only when the movie actually carries them —
 *  same reasoning as [ChannelEntity.computeContentHash]: no full-catalog rewrite for the 99% that don't. */
fun MovieEntity.computeContentHash(): Int {
    val base = Objects.hash(
        sourceId, categoryId, name, posterUrl, backdropUrl,
        year, rating, durationSecs, plot, streamUrl, containerExt, remoteId, addedAt,
    )
    if (httpHeaders == null && drmConfig == null) return base
    val withHeaders = Objects.hash(base, httpHeaders)
    return if (drmConfig == null) withHeaders else Objects.hash(withHeaders, drmConfig)
}

fun SeriesEntity.computeContentHash(): Int = Objects.hash(
    sourceId, categoryId, name, posterUrl, backdropUrl,
    year, rating, plot, remoteId, addedAt,
)
