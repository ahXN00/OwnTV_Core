package tv.own.owntv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MetadataDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.PlaybackPrefsDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SeriesSortOrderDao
import tv.own.owntv.core.database.dao.TvProviderProgramDao
import tv.own.owntv.core.database.dao.TombstoneDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ChannelFtsEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.EpisodeFtsEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MetadataMatchEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.MovieFtsEntity
import tv.own.owntv.core.database.entity.PlaybackPrefsEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SeasonEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SeriesFtsEntity
import tv.own.owntv.core.database.entity.SeriesSortOrderEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.SubtitleCacheEntity
import tv.own.owntv.core.database.entity.SubtitleLinkEntity
import tv.own.owntv.core.database.entity.SubtitleSelectionEntity
import tv.own.owntv.core.database.entity.SubtitleTimingEntity
import tv.own.owntv.core.database.entity.UserDataTombstoneEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.database.entity.TvProviderProgramEntity
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.SubtitleDao

@Database(
    entities = [
        // Profiles & sources
        ProfileEntity::class,
        SourceEntity::class,
        ProfileSourceCrossRef::class,
        // Content
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        // User data (profile-scoped)
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        PlaybackProgressEntity::class,
        ContentOrderEntity::class,
        CustomCategoryMemberEntity::class,
        PlaybackPrefsEntity::class,
        SeriesSortOrderEntity::class,
        UserDataTombstoneEntity::class,
        DownloadEntity::class,
        // Android TV home-screen bookkeeping
        TvProviderProgramEntity::class,
        // EPG
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        // TMDB metadata enrichment cache (plan §7)
        MetadataCacheEntity::class,
        MetadataMatchEntity::class,
        // Source-scoped, locally matched TMDB Trending showcase cache (v30).
        TrendingSnapshotEntity::class,
        TrendingItemEntity::class,
        // External subtitles (OpenSubtitles / local files) — subtitle plan Phase 2
        SubtitleCacheEntity::class,
        SubtitleSelectionEntity::class,
        SubtitleTimingEntity::class,
        SubtitleLinkEntity::class,
        // FTS (search)
        ChannelFtsEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        EpisodeFtsEntity::class,
    ],
    version = 38, // v7: content_order (Move). v8: contentHash + browse/unique indexes. v9: EPG contentHash + natural key. v10: TMDB metadata cache. v11: movies/series rating-sort indexes. v12: metadata_cache trailerKey. v13: metadata_cache logoPath. v14: sources.mac (Stalker portal). v15: external-subtitle cache/selection/timing tables. v16: subtitle_link (downloaded-sub ↔ content). v17: sources.syncLive/Movies/Series (skip-sync enabledScope). v18: series.episodesSyncedAt (episode-cache freshness, S8). v19: epg_channels.iconUrl (XMLTV channel logos). v20: channels (sourceId, number) index for direct tune. v21: series.addedAt + date-added sort indexes. v22: series_sort_order (per-series season/episode order). v23: sources.hlsSupported and sources.preferHls. v24: custom_category_members (user custom categories, #87). v25: sources.livePrerollSecs (per-playlist "Pre-buffer"). v26: channels.catchupType + channels.httpHeaders (M3U catch-up styles + per-channel HTTP headers). v27: sources.maxConnections (Xtream session limit read at sync). v28: movies.httpHeaders + episodes.httpHeaders (per-item M3U HTTP headers). v29: optional Stalker serial/device IDs/signature. v30: source-scoped Now Trending snapshots. v31: indexed provider-title metadata and persistent Trending attempt state. v32: playback_prefs (per-item zoom + volume, keyed by the P6 stable content key). v33: channels/movies/episodes drmConfig (M3U Widevine/ClearKey licence details, #115). v34: sources.liveEnginePreference + sources.liveLatencyMode/liveLatencyCustomSecs (per-playlist Live TV engine and Live latency). v35: playback_prefs.audioDelayMs (per-item A/V-sync memory). v36: user_data_tombstones (deleted favorites/history/resume/memberships, so local sync propagates a deletion instead of undoing it). v37: episodes.airDateMs + metadata_cache.airDate (when an episode first aired — the provider's date, with TMDB's as the fallback) profiles.avatarPath (a picture of the user's own instead of a drawn tile). v38: sources.importPortalEpg (whether a Stalker portal's own guide may be imported).

    exportSchema = true,
)
@TypeConverters(Converters::class)
/** Room entry point; its generated `_Impl` is loaded by name and retained by release keep rules. */
abstract class OwnTVDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun sourceDao(): SourceDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun progressDao(): ProgressDao
    abstract fun contentOrderDao(): ContentOrderDao
    abstract fun playbackPrefsDao(): PlaybackPrefsDao
    abstract fun customCategoryDao(): CustomCategoryDao
    abstract fun seriesSortOrderDao(): SeriesSortOrderDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun tvProviderProgramDao(): TvProviderProgramDao
    abstract fun downloadDao(): DownloadDao
    abstract fun epgDao(): EpgDao
    abstract fun metadataDao(): tv.own.owntv.core.database.dao.MetadataDao
    abstract fun trendingDao(): TrendingDao
    abstract fun subtitleDao(): SubtitleDao

    companion object {
        const val NAME = "owntv.db"

        /**
         * v1 → v2: drop the foreign key on the EPG tables (standalone EPG sources use ids that
         * aren't in `sources`). EPG data is transient and re-synced, so the tables are recreated
         * empty — everything else (profiles, sources, content, favorites, history) is preserved.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `epg_programmes`")
                db.execSQL("DROP TABLE IF EXISTS `epg_channels`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `epg_channels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `epgChannelId` TEXT NOT NULL, `displayName` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_channels_sourceId` ON `epg_channels` (`sourceId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_channels_sourceId_epgChannelId` ON `epg_channels` (`sourceId`, `epgChannelId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `epg_programmes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `epgChannelId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `stopMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_epgChannelId_startMs` ON `epg_programmes` (`epgChannelId`, `startMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId` ON `epg_programmes` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_stopMs` ON `epg_programmes` (`stopMs`)")
            }
        }

        /**
         * v2 → v3:
         * - add catch-up/archive columns to `channels` (pure additive ALTERs with defaults)
         * - add Android TV provider bookkeeping for Watch Next / Continue Watching rows
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchup` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tv_provider_programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`surface` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`targetItemId` INTEGER NOT NULL, " +
                        "`providerProgramId` INTEGER, " +
                        "`lastPositionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`lastEngagementAt` INTEGER NOT NULL, " +
                        "`lastPublishedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId` ON `tv_provider_programs` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId_surface_mediaType_groupId` ON `tv_provider_programs` (`profileId`, `surface`, `mediaType`, `groupId`)")
            }
        }

        /**
         * v3 → v4: v3 existed in the wild in two incompatible variants (catch-up vs Android TV home
         * bookkeeping). v4 unifies them by ensuring BOTH the catch-up columns and the provider table
         * exist, regardless of which v3 a user has.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Channels catch-up columns (skip if already present).
                if (!hasColumn(db, "channels", "catchup")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchup` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "channels", "catchupDays")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "channels", "catchupSource")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT")
                }

                // Android TV provider bookkeeping table (safe to run repeatedly).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tv_provider_programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`surface` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`targetItemId` INTEGER NOT NULL, " +
                        "`providerProgramId` INTEGER, " +
                        "`lastPositionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`lastEngagementAt` INTEGER NOT NULL, " +
                        "`lastPublishedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId` ON `tv_provider_programs` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tv_provider_programs_profileId_surface_mediaType_groupId` ON `tv_provider_programs` (`profileId`, `surface`, `mediaType`, `groupId`)")

                // EPG-perf Guide read-index (v4.0.0). Declared on EpgProgrammeEntity, so v4 expects it; older
                // DBs (and the runtime ensureEpgIndexes) create it too — make sure the migrated DB has it.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_epgChannelId` ON `epg_programmes` (`sourceId`, `epgChannelId`)")
            }
        }

        /**
         * v4 → v6: main's v5 briefly added `favorites.sortOrder` and v6 removed it again, so the v4
         * and v6 schemas are identical — a no-op hop keeps the public 3 → latest chain unbroken.
         * (Dev builds that sat exactly on the transient v5 fall back to the destructive safety net,
         * same as on main; v5 never shipped publicly.)
         */
        val MIGRATION_4_6 = object : androidx.room.migration.Migration(4, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // v4 and v6 schemas are identical.
            }
        }

        /**
         * v6 → v7: manual reorder (Move) — per-profile `content_order` table. This is main's v7 and
         * must keep that meaning: dev devices on unreleased main builds already sit on it.
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                createContentOrderTable(db)
            }
        }

        /**
         * v7 → v8: incremental sync (PR #40) — `contentHash` on channels/movies/series plus the
         * browse composite indexes and the unique `(sourceId, remoteId)` movie/series indexes.
         * Everything is guarded so both v3.2.0-lineage and main-dev DBs (which already have the
         * indexes) migrate cleanly.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "channels", "contentHash")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "movies", "contentHash")) {
                    db.execSQL("ALTER TABLE `movies` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "series", "contentHash")) {
                    db.execSQL("ALTER TABLE `series` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_name` ON `channels` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_name` ON `channels` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_sortOrder_name` ON `channels` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_categoryId_sortOrder_name` ON `channels` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_name` ON `movies` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_name` ON `movies` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_sortOrder_name` ON `movies` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sortOrder_name` ON `movies` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_movies_sourceId_remoteId` ON `movies` (`sourceId`, `remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_name` ON `series` (`sourceId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_name` ON `series` (`categoryId`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_sortOrder_name` ON `series` (`sourceId`, `sortOrder`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_sortOrder_name` ON `series` (`categoryId`, `sortOrder`, `name`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_series_sourceId_remoteId` ON `series` (`sourceId`, `remoteId`)")
                // Early v4 dev builds shipped without the EPG guide read-index (it was added while the
                // version stayed 4) — heal them here since the 4→6 hop is a no-op.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_epgChannelId` ON `epg_programmes` (`sourceId`, `epgChannelId`)")
            }
        }

        /**
         * v8 → v9: incremental EPG sync (PR #40) — `contentHash` on programmes plus the natural-key
         * unique index.
         *
         * D1 rewrite (body only; the resulting schema is unchanged and still matches the committed
         * 9.json): the original ran a row-wise de-dup
         * (`DELETE ... WHERE id NOT IN (SELECT MIN(id) ... GROUP BY ...)`) — a full unindexed
         * self-scan of the largest table, synchronously on first open after upgrade (multi-second
         * hang on big guides). `epg_programmes` is a rebuildable cache with no user data attached,
         * so simply truncate it: the unique index is then free to create on an empty table, and the
         * guide re-downloads on the next EPG sync. Only affects upgrades from v3.2.0 or older
         * (DB ≤ 8); everyone on 4.x already ran the old body (Room never re-runs a completed
         * migration). Lesson for future heavy migrations: probe first or truncate rebuildable
         * caches — never row-wise de-dup a cache table.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "epg_programmes", "contentHash")) {
                    db.execSQL("ALTER TABLE `epg_programmes` ADD COLUMN `contentHash` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("DELETE FROM `epg_programmes`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_programmes_natural_key` " +
                        "ON `epg_programmes` (`sourceId`, `epgChannelId`, `startMs`)",
                )
            }
        }

        /**
         * v9 → v10: TMDB metadata enrichment cache (plan §7). Two additive, purely-cache tables; no
         * existing table is touched, so this is a safe additive migration.
         */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metadata_cache` (" +
                        "`key` TEXT NOT NULL, " +
                        "`tmdbId` INTEGER NOT NULL, " +
                        "`imdbId` TEXT, " +
                        "`type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`year` INTEGER, " +
                        "`overview` TEXT, " +
                        "`posterPath` TEXT, " +
                        "`backdropPath` TEXT, " +
                        "`rating` REAL, " +
                        "`genresJson` TEXT, " +
                        "`castJson` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`)" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_cache_tmdbId` ON `metadata_cache` (`tmdbId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_cache_updatedAt` ON `metadata_cache` (`updatedAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metadata_match` (" +
                        "`localKey` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`tmdbId` INTEGER, " +
                        "`confidence` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`localKey`)" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_match_updatedAt` ON `metadata_match` (`updatedAt`)")
            }
        }

        /**
         * v10 → v11: composite indexes for the new "Rating" sort on Movies & Series
         * ("ORDER BY rating DESC, name"). Additive index-only migration; no data or column changes.
         */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_rating_name` ON `movies` (`sourceId`, `rating`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_rating_name` ON `movies` (`categoryId`, `rating`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_rating_name` ON `series` (`sourceId`, `rating`, `name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_rating_name` ON `series` (`categoryId`, `rating`, `name`)")
            }
        }

        /**
         * v11 → v12: nullable `trailerKey` on the metadata_cache table (in-app YouTube trailers, plan §7.3).
         * Additive column on a pure cache table; existing rows get NULL and simply re-fetch on next refresh.
         */
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `metadata_cache` ADD COLUMN `trailerKey` TEXT")
            }
        }

        /**
         * v12 → v13: nullable `logoPath` on the metadata_cache table (Home hero title-logo treatment).
         * Additive column on a pure cache table; existing rows simply use text-title fallback until refreshed.
         */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `metadata_cache` ADD COLUMN `logoPath` TEXT")
            }
        }

        /**
         * v13 → v14: nullable `mac` on sources for the Stalker portal source type (null for
         * M3U/Xtream). Additive; preserves all user data.
         *
         * Also runs [healSchema]: Room re-validates the ENTIRE schema after any migration, and DBs
         * with runtime index drift (a bulk import interrupted mid-sync leaves BulkInsertHelper's
         * dropped indexes missing) would otherwise fail validation and crash-loop at launch — this
         * bricked 4.0.x → 4.1.0 upgrades for affected users. See [healSchema] for the standing rule.
         */
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "mac")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `mac` TEXT")
                }
                healSchema(db)
            }
        }

        /**
         * v14 → v15: external subtitles (subtitle plan Phase 2). Three additive tables — a device-wide
         * `subtitle_cache` of downloaded/imported files, per-profile `subtitle_selection`, and
         * per-subtitle `subtitle_timing`. No existing table is touched, so all user data is preserved.
         *
         * Runs [healSchema] per the standing rule (every final migration must): this is now the last
         * hop in the chain, so it carries the schema-drift heal that a public-release upgrade relies on.
         */
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subtitle_cache` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`openSubFileId` INTEGER, " +
                        "`language` TEXT, " +
                        "`languageName` TEXT, " +
                        "`releaseName` TEXT, " +
                        "`format` TEXT, " +
                        "`hearingImpaired` INTEGER NOT NULL DEFAULT 0, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`cachedPath` TEXT NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subtitle_cache_openSubFileId` ON `subtitle_cache` (`openSubFileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_cache_lastUsedAt` ON `subtitle_cache` (`lastUsedAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subtitle_selection` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, " +
                        "`cacheId` INTEGER, " +
                        "`off` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentKey`), " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE, " +
                        "FOREIGN KEY(`cacheId`) REFERENCES `subtitle_cache`(`id`) ON DELETE SET NULL" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_selection_profileId` ON `subtitle_selection` (`profileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_selection_cacheId` ON `subtitle_selection` (`cacheId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subtitle_timing` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, " +
                        "`subtitleKey` TEXT NOT NULL, " +
                        "`offsetMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentKey`, `subtitleKey`), " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_timing_profileId` ON `subtitle_timing` (`profileId`)")

                healSchema(db)
            }
        }

        /**
         * v15 → v16: `subtitle_link` — ties each downloaded subtitle to the movie/episode it was
         * fetched for (subtitle plan §11), so a title's subtitles re-list on replay and the
         * "Delete subtitles" surfaces can browse by Movies/Series. Additive.
         */
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subtitle_link` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, " +
                        "`cacheId` INTEGER NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`contentTitle` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentKey`, `cacheId`), " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE, " +
                        "FOREIGN KEY(`cacheId`) REFERENCES `subtitle_cache`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_link_profileId` ON `subtitle_link` (`profileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_link_cacheId` ON `subtitle_link` (`cacheId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_link_profileId_mediaType` ON `subtitle_link` (`profileId`, `mediaType`)")
                healSchema(db)
            }
        }

        /**
         * v16 → v17: per-section enabledScope on sources (`syncLive` / `syncMovies` / `syncSeries`).
         * Default On (1) preserves today's "always sync everything" behaviour for existing sources.
         * Off means never fetch AND never show that section — cache is retained. Additive; runs
         * [healSchema] as the new last hop (standing rule).
         */
        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "syncLive")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `syncLive` INTEGER NOT NULL DEFAULT 1")
                }
                if (!hasColumn(db, "sources", "syncMovies")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `syncMovies` INTEGER NOT NULL DEFAULT 1")
                }
                if (!hasColumn(db, "sources", "syncSeries")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `syncSeries` INTEGER NOT NULL DEFAULT 1")
                }
                healSchema(db)
            }
        }

        /**
         * v18: `series.episodesSyncedAt` — when this show's episode list was last fetched.
         * Existing rows default to 0 ("never"), so every already-cached show refreshes its episodes
         * once on next open, which is exactly what an upgrading user needs: the shows frozen by S8
         * pick up their missing episodes without deleting and re-adding the playlist.
         */
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "series", "episodesSyncedAt")) {
                    db.execSQL("ALTER TABLE `series` ADD COLUMN `episodesSyncedAt` INTEGER NOT NULL DEFAULT 0")
                }
                healSchema(db)
            }
        }

        /** v18 → v19: nullable `iconUrl` on epg_channels (XMLTV `<icon src>`, "Prefer EPG logos"). */
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "epg_channels", "iconUrl")) {
                    db.execSQL("ALTER TABLE `epg_channels` ADD COLUMN `iconUrl` TEXT")
                }
                healSchema(db)
            }
        }

        /**
         * v19 → v20: non-unique `(sourceId, number)` index on `channels` for direct-tune channel-number
         * lookup. Additive index-only migration; no data or column changes.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_number` ON `channels` (`sourceId`, `number`)")
                healSchema(db)
            }
        }

        /**
         * v20 → v21: `series.addedAt` column + the indexes behind the "Date added" sort mode.
         * Movies already have `addedAt` since the original schema; series did not.
         *
         * Deliberately NO triggers and NO backfill:
         * - Room builds a fresh install from the exported schema JSON, and triggers are not part of
         *   a Room schema, so a trigger would exist only on upgraded databases — a permanent
         *   behaviour fork between two users on the same app version.
         * - A NULL addedAt means "unknown". NULLs sort lowest, so `addedAt DESC` already puts them
         *   last, where they fall through to the `sortOrder DESC` tiebreaker (reverse playlist
         *   order). Stamping "now" on an entire catalog would claim everything was added today.
         *
         * Legacy `movies.addedAt` was stored raw from the Xtream `added` field, which is epoch
         * SECONDS; new writes normalise to milliseconds, so convert the old rows in place. The
         * `< 10000000000` guard makes it idempotent (a ms value is always above it).
         *
         * Still calls [healSchema] (harmless, idempotent) even though 21→22 is now the last hop.
         */
        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // New column: series.addedAt (movies already has it since the original schema).
                if (!hasColumn(db, "series", "addedAt")) {
                    db.execSQL("ALTER TABLE `series` ADD COLUMN `addedAt` INTEGER")
                }

                // Seconds → milliseconds for pre-v21 Xtream movie rows.
                db.execSQL(
                    "UPDATE `movies` SET `addedAt` = `addedAt` * 1000 " +
                        "WHERE `addedAt` IS NOT NULL AND `addedAt` > 0 AND `addedAt` < 10000000000"
                )

                // Indexes for the date-added sort (same shape as the v11 rating indexes).
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_addedAt_sortOrder` ON `movies` (`sourceId`, `addedAt`, `sortOrder`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_addedAt_sortOrder` ON `movies` (`categoryId`, `addedAt`, `sortOrder`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_addedAt_sortOrder` ON `series` (`sourceId`, `addedAt`, `sortOrder`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_addedAt_sortOrder` ON `series` (`categoryId`, `addedAt`, `sortOrder`)")

                healSchema(db)
            }
        }

        /**
         * v21 → v22: `series_sort_order` — the per-profile, per-series season/episode presentation
         * order behind the "Sorting" popup.
         *
         * Its own hop rather than part of [MIGRATION_20_21] because v21 already exists in the wild
         * on dev builds (the date-added half shipped first). Folding the table into 20→21 would have
         * left those databases stamped 21 WITHOUT the table and with a stale identity hash, so Room
         * would refuse to open them — no migration runs when the version already matches.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_sort_order` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`seriesId` INTEGER NOT NULL, " +
                        "`seasonsDescending` INTEGER NOT NULL, " +
                        "`episodesDescending` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sort_order_profileId` ON `series_sort_order` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_series_sort_order_profileId_seriesId` ON `series_sort_order` (`profileId`, `seriesId`)")

                healSchema(db)
            }
        }

        /**
         * v22 → v23: `sources.hlsSupported` & `sources.preferHls` — per-source HLS support flag
         * (detected from user_info.allowed_output_formats) and user preference for prioritizing
         * .m3u8 live streams over .ts. Additive.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "hlsSupported")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `hlsSupported` INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "sources", "preferHls")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `preferHls` INTEGER NOT NULL DEFAULT 0")
                }
                healSchema(db)
            }
        }

        /**
         * v23 → v24: `custom_category_members` — the user-created custom combined categories (issue
         * #87). Membership rows per (profileId, mediaType, contextKey="custom:<uuid>", itemId),
         * modeled exactly on `content_order`: the same position semantics, the same unique index,
         * the same volatile itemId (content is clear-then-insert every sync, so rows are snapshotted
         * with stable keys and re-attached by UserDataResolver). The category definitions themselves
         * live in DataStore, so only this one table appears in the schema.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                createCustomCategoryMembersTable(db)
                healSchema(db)
            }
        }

        /**
         * v24 → v25: `sources.livePrerollSecs` — the per-playlist "Pre-buffer" override
         * (F07). `-1` (the default) means "follow the global setting", so every existing row keeps
         * exactly today's behaviour. Additive, one column, no data rewrite.
         */
        val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "livePrerollSecs")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `livePrerollSecs` INTEGER NOT NULL DEFAULT -1")
                }
                healSchema(db)
            }
        }

        /**
         * v25 → v26: two nullable `channels` columns for the M3U playback gaps —
         * `catchupType` (the `catchup="append"`/`shift`/… style, previously parsed and thrown away,
         * F17) and `httpHeaders` (per-channel `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP` request headers,
         * F16). Both null on every existing row, so behaviour is unchanged until the playlist is
         * re-synced and the values are actually populated.
         *
         * Additive only — no rewrite of the (potentially 100k-row) channels table.
         */
        val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "channels", "catchupType")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupType` TEXT")
                }
                if (!hasColumn(db, "channels", "httpHeaders")) {
                    db.execSQL("ALTER TABLE `channels` ADD COLUMN `httpHeaders` TEXT")
                }
                healSchema(db)
            }
        }

        /**
         * v26 → v27: `sources.maxConnections` — how many simultaneous streams the provider allows,
         * read from Xtream's `user_info.max_connections` at sync (F30). `0` on every existing row,
         * which means "unknown" and behaves exactly as before until the playlist is re-synced.
         *
         * Additive, and the `sources` table has a handful of rows.
         */
        val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "maxConnections")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `maxConnections` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v27 → v28: `movies.httpHeaders` and `episodes.httpHeaders` — the per-item HTTP headers
         * an M3U entry can carry (`#EXTVLCOPT:http-user-agent`, `http-referrer`, …), stored in the
         * same `Key: Value`-per-line form as `channels.httpHeaders` (v26). NULL on every existing
         * row, which behaves exactly as before until the playlist is re-synced.
         *
         * Additive only, so no table rewrite even on a 170k-movie catalog.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "movies", "httpHeaders")) {
                    db.execSQL("ALTER TABLE `movies` ADD COLUMN `httpHeaders` TEXT")
                }
                if (!hasColumn(db, "episodes", "httpHeaders")) {
                    db.execSQL("ALTER TABLE `episodes` ADD COLUMN `httpHeaders` TEXT")
                }
                healSchema(db)
            }
        }

        /**
         * v28 → v29: optional Stalker/Ministra second-step device identity. Existing MAC-only
         * sources remain null in every new column and therefore keep the exact old auth request.
         */
        val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "stalkerSerialNumber")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `stalkerSerialNumber` TEXT")
                }
                if (!hasColumn(db, "sources", "stalkerDeviceId")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `stalkerDeviceId` TEXT")
                }
                if (!hasColumn(db, "sources", "stalkerDeviceId2")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `stalkerDeviceId2` TEXT")
                }
                if (!hasColumn(db, "sources", "stalkerSignature")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `stalkerSignature` TEXT")
                }
                healSchema(db)
            }
        }

        /**
         * v29 → v30: source-scoped Now Trending snapshot cache. The item table intentionally has
         * no movie/series foreign key because provider rows may be replaced during synchronization.
         * Deleting a source cascades through its snapshot state and items.
         */
        val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trending_snapshots` (" +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`metadataLanguage` TEXT NOT NULL, " +
                        "`refreshedAt` INTEGER NOT NULL, " +
                        "`candidateFetchedAt` INTEGER NOT NULL, " +
                        "`generationId` TEXT NOT NULL, " +
                        "`itemCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trending_items` (" +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`tmdbId` INTEGER NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`trendingRank` INTEGER NOT NULL, " +
                        "`providerItemId` INTEGER NOT NULL, " +
                        "`providerRemoteId` TEXT, " +
                        "`providerStableKey` TEXT NOT NULL, " +
                        "`providerRawName` TEXT NOT NULL, " +
                        "`canonicalTitle` TEXT NOT NULL, " +
                        "`providerLanguage` TEXT, " +
                        "`advertisedQuality` TEXT, " +
                        "`advertisedCapabilities` TEXT, " +
                        "`localizedTitle` TEXT NOT NULL, " +
                        "`originalTitle` TEXT, " +
                        "`year` INTEGER, " +
                        "`overview` TEXT, " +
                        "`posterPath` TEXT, " +
                        "`backdropPath` TEXT, " +
                        "`rating` REAL, " +
                        "`trailerKey` TEXT, " +
                        "`generationId` TEXT NOT NULL, " +
                        "`refreshedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `position`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `trending_snapshots`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trending_items_sourceId_mediaType_providerItemId` " +
                        "ON `trending_items` (`sourceId`, `mediaType`, `providerItemId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trending_items_mediaType_tmdbId` " +
                        "ON `trending_items` (`mediaType`, `tmdbId`)",
                )
                healSchema(db)
            }
        }

        /** v30 → v31: additive provider-title search metadata and refresh-result diagnostics. */
        val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for (table in listOf("movies", "series")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `canonicalTitle` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `titleSignature` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `parsedYear` INTEGER")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `providerLanguage` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `qualityRank` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `advertisedCapabilities` TEXT")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_${table}_sourceId_titleSignature_parsedYear` " +
                            "ON `$table` (`sourceId`, `titleSignature`, `parsedYear`)",
                    )
                }
                db.execSQL("ALTER TABLE `trending_snapshots` ADD COLUMN `matchedItemCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trending_snapshots` ADD COLUMN `lastAttemptAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trending_snapshots` ADD COLUMN `lastAttemptStatus` TEXT NOT NULL DEFAULT 'NEVER'")
                db.execSQL("ALTER TABLE `trending_snapshots` ADD COLUMN `failureStage` TEXT")
                healSchema(db)
            }
        }

        /**
         * v31 → v32: `playback_prefs` — the per-item zoom and volume the player now remembers.
         * A new empty table only; nothing existing is touched, and an absent row means "follow the
         * global default", so an upgraded install behaves exactly as before until the user changes
         * something in the player.
         */
        val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_prefs` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, " +
                        "`zoomMode` TEXT, " +
                        "`volumeBoost` INTEGER, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `contentKey`), " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_prefs_profileId` ON `playback_prefs` (`profileId`)")
                healSchema(db)
            }
        }

        /**
         * v32 → v33: `drmConfig` on `channels`, `movies` and `episodes` — the Widevine/ClearKey licence
         * details an M3U entry declares through `#KODIPROP:inputstream.adaptive.license_*` (#115),
         * stored as the small JSON blob [tv.own.owntv.core.drm.DrmConfig] writes.
         *
         * NULL on every existing row, and a NULL row plays exactly as it did before, so an upgraded
         * install is unchanged until the playlist is re-synced. Additive only — no table rewrite even
         * on a 170k-movie catalog, which is why all three columns ride in one version rather than
         * three.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("channels", "movies", "episodes").forEach { table ->
                    if (!hasColumn(db, table, "drmConfig")) {
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `drmConfig` TEXT")
                    }
                }
                healSchema(db)
            }
        }

        /**
         * v34 → v35: `playback_prefs.audioDelayMs` — the per-item A/V-sync offset the player
         * remembers, alongside the per-item zoom and volume already in this table (v32).
         *
         * Nullable with no default, which is what "no per-item choice, follow the global setting"
         * means, so every existing row keeps behaving exactly as it does today.
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        /**
         * v35 → v36: `user_data_tombstones` — the deleted favorites / history entries / resume
         * positions / custom-category memberships local sync needs so a deletion travels between two
         * devices instead of being undone by the next merge.
         *
         * A new empty table: nothing existing changes, and an app that never syncs simply never
         * writes a row. Rows are keyed by the same stable content identity a backup exports, not by
         * the volatile `itemId`, because the point of the row is to be recognisable on the other
         * device.
         */
        val MIGRATION_35_36 = object : androidx.room.migration.Migration(35, 36) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_data_tombstones` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`identity` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_data_tombstones_profileId` ON `user_data_tombstones` (`profileId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_data_tombstones_profileId_kind_identity` " +
                        "ON `user_data_tombstones` (`profileId`, `kind`, `identity`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_data_tombstones_deletedAt` ON `user_data_tombstones` (`deletedAt`)")
                healSchema(db)
            }
        }

        /**
         * v36 → v37: when an episode first aired, in the two places the two answers belong.
         * `episodes.airDateMs` holds the provider's own date; `metadata_cache.airDate` holds TMDB's,
         * as the fallback for the many panels that date nothing. Stored episodes fill in on their
         * next refresh; the cache fills in as seasons are opened.
         *
         * `profiles.avatarPath` rides along: a profile picture the user chose, null meaning the drawn
         * tile everyone has had until now. All three are nullable columns added to existing tables —
         * nothing is rewritten and nothing existing changes meaning.
         */
        /**
         * v37 → v38: `sources.importPortalEpg` — whether a Stalker portal's own guide may be imported,
         * on for every existing playlist.
         *
         * A version of its own rather than another line in [MIGRATION_36_37], which by then had already
         * run on real devices. Room fingerprints the schema, so widening a migration after the fact
         * leaves those databases claiming a version whose shape no longer matches — the app then
         * refuses to open at all. A migration that has executed anywhere is finished; the next change
         * gets the next number.
         */
        val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `importPortalEpg` INTEGER NOT NULL DEFAULT 1")
                healSchema(db)
            }
        }

        val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `episodes` ADD COLUMN `airDateMs` INTEGER")
                db.execSQL("ALTER TABLE `metadata_cache` ADD COLUMN `airDate` TEXT")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `avatarPath` TEXT")
                healSchema(db)
            }
        }

        val MIGRATION_34_35 = object : androidx.room.migration.Migration(34, 35) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "playback_prefs", "audioDelayMs")) {
                    db.execSQL("ALTER TABLE `playback_prefs` ADD COLUMN `audioDelayMs` INTEGER")
                }
                healSchema(db)
            }
        }

        /**
         * v33 → v34: `sources.liveEnginePreference`, `sources.liveLatencyMode` and
         * `sources.liveLatencyCustomSecs` — the per-playlist Live TV engine and Live latency overrides.
         *
         * All three ride in one version because the latency pair is meaningless split up, and because
         * the engine choice and the buffer depth are the two halves of "how this provider's live
         * streams want to be played" — a user setting one almost always looks at the other.
         *
         * The two TEXT columns are nullable with no default, which is exactly what "follow the global
         * setting" means, so every existing row keeps today's behaviour without a data pass. The INTEGER
         * column takes the same `-1` sentinel `livePrerollSecs` already uses (v25).
         *
         * Last hop, so it carries [healSchema] (standing rule).
         */
        val MIGRATION_33_34 = object : androidx.room.migration.Migration(33, 34) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!hasColumn(db, "sources", "liveEnginePreference")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `liveEnginePreference` TEXT")
                }
                if (!hasColumn(db, "sources", "liveLatencyMode")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `liveLatencyMode` TEXT")
                }
                if (!hasColumn(db, "sources", "liveLatencyCustomSecs")) {
                    db.execSQL("ALTER TABLE `sources` ADD COLUMN `liveLatencyCustomSecs` INTEGER NOT NULL DEFAULT -1")
                }
                healSchema(db)
            }
        }

        /**
         * Every migration, in one place, because there are two callers and they must never disagree:
         * `databaseModule` (the real database) and `OwnTVDatabaseMigrationTest` (the upgrade-path
         * proof). The test kept its own copy of this list and it silently fell three versions behind,
         * which left every migration test unable to even open the database — the one check that
         * guards against wiping a user's profiles and history was red and nobody could see it.
         *
         * Declared below the migrations themselves: a companion `val` initialises in source order, so
         * referencing them from higher up would capture nulls.
         */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
        )

        /**
         * Canonical CREATE statements for every NON-unique index Room expects on the four
         * bulk-synced tables, keyed by table (must stay in sync with the current schema JSON).
         * BulkInsertHelper drops exactly these during eligible fresh imports; restore, the
         * post-import ensure* passes, and [healSchema] all recreate from this one list so a gap
         * can't survive anywhere. Unique indexes are deliberately absent: no code path ever drops
         * them, and re-creating a unique index on unexpected data could itself fail.
         */
        val EXPECTED_NON_UNIQUE_INDEXES: Map<String, List<String>> = mapOf(
            "channels" to listOf(
                "CREATE INDEX IF NOT EXISTS `index_channels_sourceId` ON `channels` (`sourceId`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_categoryId` ON `channels` (`categoryId`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_name` ON `channels` (`name`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_epgChannelId` ON `channels` (`epgChannelId`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_sourceId_name` ON `channels` (`sourceId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_categoryId_name` ON `channels` (`categoryId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_sourceId_sortOrder_name` ON `channels` (`sourceId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_categoryId_sortOrder_name` ON `channels` (`categoryId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_channels_sourceId_number` ON `channels` (`sourceId`, `number`)",
            ),
            "movies" to listOf(
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId` ON `movies` (`sourceId`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_categoryId` ON `movies` (`categoryId`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_name` ON `movies` (`name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_name` ON `movies` (`sourceId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_categoryId_name` ON `movies` (`categoryId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_sortOrder_name` ON `movies` (`sourceId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sortOrder_name` ON `movies` (`categoryId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_rating_name` ON `movies` (`sourceId`, `rating`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_categoryId_rating_name` ON `movies` (`categoryId`, `rating`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_addedAt_sortOrder` ON `movies` (`sourceId`, `addedAt`, `sortOrder`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_categoryId_addedAt_sortOrder` ON `movies` (`categoryId`, `addedAt`, `sortOrder`)",
                "CREATE INDEX IF NOT EXISTS `index_movies_sourceId_titleSignature_parsedYear` ON `movies` (`sourceId`, `titleSignature`, `parsedYear`)",
            ),
            "series" to listOf(
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId` ON `series` (`sourceId`)",
                "CREATE INDEX IF NOT EXISTS `index_series_categoryId` ON `series` (`categoryId`)",
                "CREATE INDEX IF NOT EXISTS `index_series_name` ON `series` (`name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId_name` ON `series` (`sourceId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_categoryId_name` ON `series` (`categoryId`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId_sortOrder_name` ON `series` (`sourceId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_categoryId_sortOrder_name` ON `series` (`categoryId`, `sortOrder`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId_rating_name` ON `series` (`sourceId`, `rating`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_categoryId_rating_name` ON `series` (`categoryId`, `rating`, `name`)",
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId_addedAt_sortOrder` ON `series` (`sourceId`, `addedAt`, `sortOrder`)",
                "CREATE INDEX IF NOT EXISTS `index_series_categoryId_addedAt_sortOrder` ON `series` (`categoryId`, `addedAt`, `sortOrder`)",
                "CREATE INDEX IF NOT EXISTS `index_series_sourceId_titleSignature_parsedYear` ON `series` (`sourceId`, `titleSignature`, `parsedYear`)",
            ),
            "epg_programmes" to listOf(
                "CREATE INDEX IF NOT EXISTS `index_epg_programmes_epgChannelId_startMs` ON `epg_programmes` (`epgChannelId`, `startMs`)",
                "CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId` ON `epg_programmes` (`sourceId`)",
                "CREATE INDEX IF NOT EXISTS `index_epg_programmes_stopMs` ON `epg_programmes` (`stopMs`)",
                "CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_epgChannelId` ON `epg_programmes` (`sourceId`, `epgChannelId`)",
            ),
        )

        /**
         * Room's exact generated DDL for the external-content FTS tables. Only `createAllTables`
         * (fresh install) ever creates them — no migration does — so keep the strings verbatim from
         * the generated OwnTVDatabase_Impl or validation will reject the healed table.
         */
        // internal, not private: the migration test asserts healSchema restores every entry.
        internal val EXPECTED_FTS_TABLES: Map<String, String> = mapOf(
            "channels_fts" to "CREATE VIRTUAL TABLE IF NOT EXISTS `channels_fts` USING FTS4(`name` TEXT NOT NULL, content=`channels`)",
            "movies_fts" to "CREATE VIRTUAL TABLE IF NOT EXISTS `movies_fts` USING FTS4(`name` TEXT NOT NULL, content=`movies`)",
            "series_fts" to "CREATE VIRTUAL TABLE IF NOT EXISTS `series_fts` USING FTS4(`name` TEXT NOT NULL, content=`series`)",
            "episodes_fts" to "CREATE VIRTUAL TABLE IF NOT EXISTS `episodes_fts` USING FTS4(`name` TEXT NOT NULL, content=`episodes`)",
        )

        /**
         * Bring a live database back to the schema Room expects, idempotently (pure no-op on a
         * healthy DB). Recreates the non-unique indexes on the bulk-synced tables and any missing
         * FTS table (rebuilt from its content table when it had to be created).
         *
         * STANDING RULE: every future *final* migration (14 → 15, …) must call this. Room validates
         * the whole schema once, after the last migration in the chain — so the heal only protects
         * an upgrade if it runs in the step users actually pass through.
         */
        fun healSchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            EXPECTED_FTS_TABLES.forEach { (name, createSql) ->
                val exists = db.query(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$name'",
                ).use { it.moveToFirst() }
                if (!exists) {
                    db.execSQL(createSql)
                    db.execSQL("INSERT INTO `$name`(`$name`) VALUES('rebuild')")
                }
            }
            EXPECTED_NON_UNIQUE_INDEXES.values.forEach { statements ->
                statements.forEach { if (indexColumnsExist(db, it)) db.execSQL(it) }
            }
        }

        /**
         * True when every column an index statement references already exists.
         *
         * Needed because healSchema runs from EVERY migration, not just the last one, while this
         * list always describes the CURRENT schema. Upgrading from an old version therefore heals
         * against a table that has not gained its newer columns yet (series.addedAt arrives in
         * v21), which would abort the whole chain with "no such column". Skipping such an index is
         * safe: the migration that adds the column creates it, and the final hop's heal — by which
         * point the column exists — restores it if it is ever missing.
         */
        private fun indexColumnsExist(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            createIndexSql: String,
        ): Boolean {
            val match = INDEX_TARGET.find(createIndexSql) ?: return true
            val table = match.groupValues[1]
            return match.groupValues[2].split(',')
                .map { it.trim().trim('`') }
                .all { it.isEmpty() || hasColumn(db, table, it) }
        }

        /** Pulls the table name and column list out of a canonical CREATE INDEX statement. */
        private val INDEX_TARGET = Regex("ON\\s+`([^`]+)`\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

        /**
         * Every schema object [healSchema] guarantees: the FTS tables plus the name of each
         * non-unique index, parsed once from the canonical CREATE statements above so the two
         * lists can never drift apart.
         */
        private val EXPECTED_SCHEMA_OBJECTS: List<String> by lazy {
            EXPECTED_FTS_TABLES.keys.toList() +
                EXPECTED_NON_UNIQUE_INDEXES.values.flatten()
                    .map { it.substringAfter("IF NOT EXISTS `").substringBefore('`') }
        }

        /**
         * ST4: the cheap drift probe that gates the heal on the app-open path. One `sqlite_master`
         * count answers "is anything missing?"; on a healthy database — the normal case — that is a
         * single index lookup instead of ~30 `CREATE INDEX IF NOT EXISTS` statements landing on
         * whichever thread issues the first query, at exactly the moment the first grid wants data.
         *
         * Deliberately still inside `onOpen`: a query that ran before the heal finished would hit a
         * missing index, which is the slow path the heal exists to prevent. Returns true if it healed.
         */
        fun healSchemaIfDrifted(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean {
            val expected = EXPECTED_SCHEMA_OBJECTS
            val placeholders = expected.joinToString(",") { "?" }
            val present = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN ($placeholders)",
                expected.toTypedArray(),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (present >= expected.size) return false
            healSchema(db)
            return true
        }

        private fun createCustomCategoryMembersTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_category_members` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, " +
                    "`contextKey` TEXT NOT NULL, " +
                    "`itemId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_category_members_profileId` ON `custom_category_members` (`profileId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_category_members_profileId_mediaType_contextKey` ON `custom_category_members` (`profileId`, `mediaType`, `contextKey`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_category_members_profileId_mediaType_contextKey_itemId` ON `custom_category_members` (`profileId`, `mediaType`, `contextKey`, `itemId`)")
        }

        private fun createContentOrderTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `content_order` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, " +
                    "`contextKey` TEXT NOT NULL, " +
                    "`itemId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_order_profileId` ON `content_order` (`profileId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_order_profileId_mediaType_contextKey` ON `content_order` (`profileId`, `mediaType`, `contextKey`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_content_order_profileId_mediaType_contextKey_itemId` ON `content_order` (`profileId`, `mediaType`, `contextKey`, `itemId`)")
        }

        private fun hasColumn(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                return false
            }
        }
    }
}

