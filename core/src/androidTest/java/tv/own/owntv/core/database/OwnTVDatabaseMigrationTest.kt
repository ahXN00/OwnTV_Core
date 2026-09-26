package tv.own.owntv.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus

@RunWith(AndroidJUnit4::class)
class OwnTVDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrateVersion2ToCurrent_preservesUserData_andUnifiesTvProviderAndCatchup() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion2Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = openForAssertions(db)
            assertTableExists(sqlite, "tv_provider_programs")
            assertIndexExists(sqlite, "index_tv_provider_programs_profileId_surface_mediaType_groupId")
            assertColumnExists(sqlite, "channels", "catchup")
            assertColumnExists(sqlite, "channels", "catchupDays")
            assertColumnExists(sqlite, "channels", "catchupSource")

            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "sources", 1)
            assertCount(sqlite, "profile_source", 1)
            assertCount(sqlite, "categories", 3)
            assertCount(sqlite, "channels", 1)
            assertCount(sqlite, "movies", 1)
            assertCount(sqlite, "series", 1)
            assertCount(sqlite, "seasons", 1)
            assertCount(sqlite, "episodes", 1)
            assertCount(sqlite, "watch_history", 3)
            assertCount(sqlite, "playback_progress", 2)
            assertCount(sqlite, "tv_provider_programs", 0)
        } finally {
            db.close()
        }
    }

    /** Dev devices on unreleased main builds (DB v7 with content_order, no contentHash). */
    @Test
    fun migrateVersion7ToCurrent_addsContentHashesAndEpgNaturalKey() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion7Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = openForAssertions(db)
            assertColumnExists(sqlite, "channels", "contentHash")
            assertColumnExists(sqlite, "movies", "contentHash")
            assertColumnExists(sqlite, "series", "contentHash")
            assertColumnExists(sqlite, "epg_programmes", "contentHash")
            assertIndexExists(sqlite, "index_epg_programmes_natural_key")
            assertTableExists(sqlite, "content_order")
            assertIndexExists(sqlite, "index_content_order_profileId_mediaType_contextKey_itemId")
            // D1: v8→v9 now truncates the rebuildable programme cache (instant first launch)
            // instead of the old row-wise de-dup; the guide re-downloads on the next EPG sync.
            // epg_channels is untouched.
            assertCount(sqlite, "epg_programmes", 0)
            assertCount(sqlite, "epg_channels", 2)
        } finally {
            db.close()
        }
    }

    /** The public upgrade path: a real v3.2.0 database (DB v3) all the way to the current version. */
    @Test
    fun migrateVersion3ToCurrent_publicUpgradePath_preservesUserData() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion3Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = openForAssertions(db)
            // Structure added along the way.
            assertTableExists(sqlite, "content_order")
            assertIndexExists(sqlite, "index_content_order_profileId_mediaType_contextKey_itemId")
            // v24: custom category membership (issue #87).
            assertTableExists(sqlite, "custom_category_members")
            assertIndexExists(sqlite, "index_custom_category_members_profileId_mediaType_contextKey_itemId")
            assertColumnExists(sqlite, "channels", "contentHash")
            assertColumnExists(sqlite, "movies", "contentHash")
            assertColumnExists(sqlite, "series", "contentHash")
            assertColumnExists(sqlite, "epg_programmes", "contentHash")
            assertIndexExists(sqlite, "index_channels_sourceId_sortOrder_name")
            assertIndexExists(sqlite, "index_movies_sourceId_remoteId")
            assertIndexExists(sqlite, "index_series_sourceId_remoteId")
            assertIndexExists(sqlite, "index_epg_programmes_natural_key")
            assertIndexExists(sqlite, "index_epg_programmes_sourceId_epgChannelId")
            assertTableExists(sqlite, "metadata_cache")
            assertColumnExists(sqlite, "metadata_cache", "logoPath")
            assertColumnExists(sqlite, "sources", "mac")
            // v25: per-playlist "Pre-buffer" override.
            assertColumnExists(sqlite, "sources", "livePrerollSecs")
            // v26: M3U catch-up style + per-channel HTTP headers.
            assertColumnExists(sqlite, "channels", "catchupType")
            assertColumnExists(sqlite, "channels", "httpHeaders")
            // v27: Xtream session limit read at sync.
            assertColumnExists(sqlite, "sources", "maxConnections")
            assertColumnExists(sqlite, "sources", "stalkerSerialNumber")
            assertColumnExists(sqlite, "sources", "stalkerDeviceId")
            assertColumnExists(sqlite, "sources", "stalkerDeviceId2")
            assertColumnExists(sqlite, "sources", "stalkerSignature")
            assertTableExists(sqlite, "trending_snapshots")
            assertTableExists(sqlite, "trending_items")
            // v34: the per-playlist Live TV engine / latency overrides. All three must arrive meaning
            // "follow the global setting" — nulls for the two modes, the -1 sentinel for the custom
            // buffer — or an upgrade would silently pin every existing playlist to one engine.
            assertColumnExists(sqlite, "sources", "liveEnginePreference")
            assertColumnExists(sqlite, "sources", "liveLatencyMode")
            assertColumnValue(sqlite, "sources", "liveEnginePreference", 10, null)
            assertColumnValue(sqlite, "sources", "liveLatencyMode", 10, null)
            assertColumnValue(sqlite, "sources", "liveLatencyCustomSecs", 10, -1L)
            // v35: the per-item remembered A/V-sync offset, nullable = "no per-item choice".
            assertColumnExists(sqlite, "playback_prefs", "audioDelayMs")
            // v36: the deleted-user-data markers local sync merges on. New and empty — an upgrade
            // must not invent deletions.
            assertTableExists(sqlite, "user_data_tombstones")
            assertIndexExists(sqlite, "index_user_data_tombstones_profileId_kind_identity")
            assertCount(sqlite, "user_data_tombstones", 0)
            // v37: when an episode first aired — the provider's date on the episode, TMDB's in the
            // cache. Both must arrive NULL on an upgrade: an invented date would be worse than none,
            // and the real ones fill in on the next refresh.
            assertColumnExists(sqlite, "episodes", "airDateMs")
            assertColumnExists(sqlite, "metadata_cache", "airDate")
            assertColumnValue(sqlite, "episodes", "airDateMs", 70, null)
            // …and the profile picture column, which must arrive empty: an existing profile keeps the
            // drawn avatar it already had until somebody chooses a picture.
            assertColumnExists(sqlite, "profiles", "avatarPath")
            assertColumnValue(sqlite, "profiles", "avatarPath", 1, null)
            // v38: whether a Stalker portal's own guide may be imported. Every existing playlist
            // arrives with it ON — an upgrade must not silently switch a guide off.
            assertColumnExists(sqlite, "sources", "importPortalEpg")
            assertColumnValue(sqlite, "sources", "importPortalEpg", 10, 1L)
            // v39: live recording. Two new tables, both empty — an upgrade must not invent a
            // recording, and it must not have touched anything that was already there. The unique
            // index is the one with teeth: it is what stops pressing Record twice on the same
            // programme from scheduling it twice.
            assertTableExists(sqlite, "recordings")
            assertIndexExists(sqlite, "index_recordings_profileId_channelId_programmeStartMs")
            assertIndexExists(sqlite, "index_recordings_startMs_stopMs")
            assertCount(sqlite, "recordings", 0)
            assertTableExists(sqlite, "recording_rules")
            assertIndexExists(sqlite, "index_recording_rules_profileId_channelId_titleKey")
            assertCount(sqlite, "recording_rules", 0)
            // v40: when the app measured how many streams the provider allows. Every upgraded
            // playlist must arrive at 0 — "never measured" — so the measurement runs once for them
            // rather than being assumed to have already happened. Crucially it must NOT disturb
            // maxConnections: a playlist whose Xtream panel already published its limit keeps it.
            assertColumnExists(sqlite, "sources", "maxConnectionsProbedAt")
            assertColumnValue(sqlite, "sources", "maxConnectionsProbedAt", 10, 0L)
            assertIndexExists(sqlite, "index_movies_sourceId_rating_name")
            // v20: direct-tune index on (sourceId, number).
            assertIndexExists(sqlite, "index_channels_sourceId_number")
            // Channel number column preserved through the full migration chain.
            assertColumnValue(sqlite, "channels", "number", 30, 1L)
            // User data survives.
            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "sources", 1)
            assertCount(sqlite, "profile_source", 1)
            assertCount(sqlite, "categories", 3)
            assertCount(sqlite, "channels", 1)
            assertCount(sqlite, "movies", 1)
            assertCount(sqlite, "series", 1)
            assertCount(sqlite, "favorites", 3)
            assertCount(sqlite, "playback_progress", 2)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateVersion29ToCurrent_addsIndependentTrendingSnapshots_andCascadesSourceDelete() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion29Database()

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertTableExists(sqlite, "trending_snapshots")
            assertTableExists(sqlite, "trending_items")
            assertIndexExists(sqlite, "index_trending_items_sourceId_mediaType_providerItemId")
            assertIndexExists(sqlite, "index_trending_items_mediaType_tmdbId")
            assertCount(sqlite, "sources", 2)

            runBlocking {
                val dao = db.trendingDao()
                dao.replaceSnapshot(
                    trendingState(sourceId = 10, generationId = "source-a-1"),
                    trendingItems(sourceId = 10, generationId = "source-a-1", titlePrefix = "A"),
                )
                dao.replaceSnapshot(
                    trendingState(sourceId = 11, generationId = "source-b-1"),
                    trendingItems(sourceId = 11, generationId = "source-b-1", titlePrefix = "B"),
                )

                val sourceB = dao.getSnapshot(11) ?: error("Source B snapshot missing")
                assertEquals(5, sourceB.items.size)
                assertEquals("B 1", sourceB.items.first().localizedTitle)

                dao.writeBelowThreshold(
                    TrendingSnapshotEntity(
                        sourceId = 10,
                        status = TrendingSnapshotStatus.BELOW_THRESHOLD,
                        metadataLanguage = "de-DE",
                        refreshedAt = 2_000,
                        candidateFetchedAt = 1_900,
                        generationId = "source-a-2",
                        itemCount = 0,
                        matchedItemCount = 2,
                        lastAttemptAt = 2_000,
                        lastAttemptStatus = TrendingAttemptStatus.BELOW_THRESHOLD,
                    ),
                )

                assertEquals(0, dao.getSnapshot(10)?.items?.size)
                assertEquals("source-b-1", dao.getSnapshot(11)?.state?.generationId)
            }

            sqlite.execSQL("DELETE FROM sources WHERE id = 11")
            assertCount(sqlite, "trending_snapshots", 1)
            assertCount(sqlite, "trending_items", 0)
        } finally {
            db.close()
        }
    }

    /**
     * The v23 → v24 hop: custom category membership (issue #87). A user arriving from a v23 dev
     * build (or the next public release built on it) must gain the table with zero data loss —
     * content_order rows made before the upgrade survive, and the new table is empty but fully
     * indexed. `everyExportedSchemaVersionMigratesToCurrent` covers the schema-validity half of
     * this hop from every start version; this test pins the user-data half.
     */
    @Test
    fun migrateVersion23ToCurrent_addsCustomCategoryMembers() {
        context.deleteDatabase(DB_NAME)
        val db23 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db23, "tv.own.owntv.core.database.OwnTVDatabase/23.json")
            db23.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            // A manual-order row the user made before the upgrade must survive it.
            db23.execSQL("INSERT INTO content_order (profileId, mediaType, contextKey, itemId, position) VALUES (1, '${MediaType.LIVE.name}', '10:cat-live', 30, 0)")
            db23.version = 23
        } finally {
            db23.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertTableExists(sqlite, "custom_category_members")
            assertIndexExists(sqlite, "index_custom_category_members_profileId")
            assertIndexExists(sqlite, "index_custom_category_members_profileId_mediaType_contextKey")
            assertIndexExists(sqlite, "index_custom_category_members_profileId_mediaType_contextKey_itemId")
            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "content_order", 1)
            assertCount(sqlite, "custom_category_members", 0)
        } finally {
            db.close()
        }
    }

    /**
     * The v31 → v32 hop: `playback_prefs`, the per-item zoom/volume the player remembers. The table
     * is new and starts empty, so the point of this test is that an upgrade from the current public
     * schema loses nothing and that a profile delete still cascades — a row keyed on a stable
     * content key is never orphaned by a re-sync, but it must not outlive its profile.
     */
    @Test
    fun migrateVersion31ToCurrent_addsPlaybackPrefs_andCascadesProfileDelete() {
        context.deleteDatabase(DB_NAME)
        val db31 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db31, "tv.own.owntv.core.database.OwnTVDatabase/31.json")
            db31.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            db31.execSQL("INSERT INTO content_order (profileId, mediaType, contextKey, itemId, position) VALUES (1, '${MediaType.LIVE.name}', '10:cat-live', 30, 0)")
            db31.version = 31
        } finally {
            db31.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertTableExists(sqlite, "playback_prefs")
            assertIndexExists(sqlite, "index_playback_prefs_profileId")
            assertCount(sqlite, "profiles", 1)
            assertCount(sqlite, "content_order", 1)
            assertCount(sqlite, "playback_prefs", 0)

            sqlite.execSQL("PRAGMA foreign_keys = ON")
            sqlite.execSQL(
                "INSERT INTO playback_prefs (profileId, contentKey, zoomMode, volumeBoost, updatedAt) " +
                    "VALUES (1, '10:MOVIE:art-42', 'FILL', 130, 5)",
            )
            assertCount(sqlite, "playback_prefs", 1)
            sqlite.execSQL("DELETE FROM profiles WHERE id = 1")
            assertCount(sqlite, "playback_prefs", 0)
        } finally {
            db.close()
        }
    }

    /**
     * Regression for the 4.0.x → 4.1.0 upgrade crash: an interrupted bulk import leaves
     * BulkInsertHelper's dropped non-unique indexes missing. That drift is invisible while the DB
     * version doesn't change, but the next migration triggers Room's full-schema validation, which
     * used to throw "Migration didn't properly handle" and crash-loop the app at launch. The final
     * migration now runs OwnTVDatabase.healSchema, so opening a drifted v12 database must succeed
     * and end with every expected index back in place.
     */
    @Test
    fun migrateDriftedVersion12ToCurrent_healsDroppedIndexes() {
        context.deleteDatabase(DB_NAME)
        val db12 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db12, "tv.own.owntv.core.database.OwnTVDatabase/12.json")
            db12.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            // Simulate the interrupted-import drift.
            db12.execSQL("DROP INDEX IF EXISTS `index_movies_sourceId_rating_name`")
            db12.execSQL("DROP INDEX IF EXISTS `index_series_categoryId_rating_name`")
            db12.execSQL("DROP INDEX IF EXISTS `index_channels_sourceId`")
            db12.execSQL("DROP INDEX IF EXISTS `index_epg_programmes_stopMs`")
            db12.version = 12
        } finally {
            db12.close()
        }

        val db = openWithAllMigrations()
        try {
            // Would throw IllegalStateException here without the heal (validation failure).
            val sqlite = openForAssertions(db)
            assertIndexExists(sqlite, "index_movies_sourceId_rating_name")
            assertIndexExists(sqlite, "index_series_categoryId_rating_name")
            assertIndexExists(sqlite, "index_channels_sourceId")
            assertIndexExists(sqlite, "index_epg_programmes_stopMs")
            assertColumnExists(sqlite, "metadata_cache", "logoPath")
            assertColumnExists(sqlite, "sources", "mac")
            assertColumnExists(sqlite, "sources", "syncLive")
            assertColumnExists(sqlite, "sources", "syncMovies")
            assertColumnExists(sqlite, "sources", "syncSeries")
            assertColumnExists(sqlite, "series", "episodesSyncedAt")
            assertCount(sqlite, "profiles", 1)
        } finally {
            db.close()
        }
    }

    /**
     * D2 — the tests above each pick one interesting starting version, which leaves the hops in
     * between covered only by accident. A user can arrive from *any* shipped version, so every
     * exported schema must migrate all the way to the current one and pass Room's full-schema
     * validation. This is the test that fails when a new migration is added without its predecessor
     * being reachable, or when a hand-written migration drifts from the entity definitions.
     *
     * Schema-only on purpose: seeding each version would mean hand-maintaining a column list per
     * version, and data preservation is already asserted from v2/v3/v7 above.
     */
    @Test
    fun everyExportedSchemaVersionMigratesToCurrent() {
        MIGRATABLE_START_VERSIONS.forEach { version ->
            context.deleteDatabase(DB_NAME)
            val old = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
            try {
                executeSchemaQueries(old, "tv.own.owntv.core.database.OwnTVDatabase/$version.json")
                old.version = version
            } finally {
                old.close()
            }

            val db = openWithAllMigrations()
            try {
                // Room validates the whole schema while opening; a broken hop throws here.
                val sqlite = openForAssertions(db)
                assertEquals("v$version did not reach the current version", CURRENT_VERSION, userVersionOf(sqlite))
                OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.values.flatten().forEach {
                    assertIndexExists(sqlite, indexNameOf(it))
                }
            } finally {
                db.close()
            }
        }
    }

    /**
     * D2 — MIGRATION_4_6 skips version 5 rather than shipping 4→5 and 5→6. That is only safe
     * because v5 never reached a public build: it existed on dev machines for one change to
     * `favorites` that was reverted before release, so 4.json and 6.json describe the identical
     * schema (same identityHash). If a future change ever makes them differ, this no-op hop would
     * silently leave a v4 database malformed — so pin the property the shortcut depends on.
     */
    @Test
    fun migration4to6IsANoOpBecauseVersion5WasNeverPublic() {
        assertEquals(4, OwnTVDatabase.MIGRATION_4_6.startVersion)
        assertEquals(6, OwnTVDatabase.MIGRATION_4_6.endVersion)
        assertEquals(
            "4.json and 6.json describe different schemas — MIGRATION_4_6 can no longer be a no-op",
            identityHashOf(4),
            identityHashOf(6),
        )
    }

    /**
     * D2 — [OwnTVDatabase.healSchema] is the last line of defence against the interrupted-import
     * drift that crash-looped 4.0.x → 4.1.0. The test above proves it rescues one drifted upgrade;
     * this one proves it is complete: strip *every* object it claims to guarantee (all non-unique
     * indexes on the bulk-synced tables and all four external-content FTS tables) from a current
     * database, heal, and require the full set back — each FTS table actually queryable, not just
     * present in sqlite_master.
     */
    @Test
    fun healSchemaRestoresEveryGuaranteedIndexAndFtsTable() {
        context.deleteDatabase(DB_NAME)
        val expectedIndexes = OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.values.flatten().map(::indexNameOf)
        val expectedFts = OwnTVDatabase.EXPECTED_FTS_TABLES.keys

        val current = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(current, "tv.own.owntv.core.database.OwnTVDatabase/$CURRENT_VERSION.json")
            current.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            expectedIndexes.forEach { current.execSQL("DROP INDEX IF EXISTS `$it`") }
            expectedFts.forEach { current.execSQL("DROP TABLE IF EXISTS `$it`") }
            current.version = CURRENT_VERSION
        } finally {
            current.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            // Opening at the current version runs no migration, so nothing has healed yet.
            expectedIndexes.forEach { assertMissing(sqlite, "index", it) }
            expectedFts.forEach { assertMissing(sqlite, "table", it) }

            withConnection(db) { OwnTVDatabase.healSchema(it) }

            expectedIndexes.forEach { assertIndexExists(sqlite, it) }
            expectedFts.forEach { fts ->
                assertTableExists(sqlite, fts)
                // A CREATE VIRTUAL TABLE that registered but is unusable (missing shadow tables,
                // content table mismatch) only shows up when something reads from it.
                countRows(sqlite, "SELECT COUNT(*) FROM `$fts`")
            }
            // Idempotent: the app runs this on every drifted open.
            withConnection(db) { OwnTVDatabase.healSchema(it) }
            expectedIndexes.forEach { assertIndexExists(sqlite, it) }
            assertCount(sqlite, "profiles", 1)
        } finally {
            db.close()
        }
    }

    /** `CREATE INDEX IF NOT EXISTS \`name\` ON …` -> `name`. */
    private fun indexNameOf(createSql: String) =
        createSql.substringAfter("IF NOT EXISTS `").substringBefore('`')

    private fun identityHashOf(version: Int): String {
        val asset = "tv.own.owntv.core.database.OwnTVDatabase/$version.json"
        val json = JSONObject(testContext.assets.open(asset).bufferedReader().use { it.readText() })
        return json.getJSONObject("database").getString("identityHash")
    }


    /**
     * v40 → v41: the matcher's normalized names become stored columns, and a time index appears.
     *
     * The upgrade path that matters is the owner's: a database already full of guide rows, upgraded
     * in place. What is proved here is that the columns and indexes arrive, that existing rows are
     * backfilled with the same answer [EpgMatcher.normalizeForEpg] would have given, and — the part a
     * schema check cannot see — that no guide row is lost on the way.
     */
    @Test
    fun migrateVersion40To41_addsNormalizedColumnsAndBackfillsExistingChannels() {
        context.deleteDatabase(DB_NAME)
        bootstrapVersion40Database()

        val db = openWithAllMigrations()

        try {
            val sqlite = openForAssertions(db)
            assertColumnExists(sqlite, "epg_channels", "normName")
            assertColumnExists(sqlite, "epg_channels", "normId")
            assertIndexExists(sqlite, "index_epg_channels_normName")
            assertIndexExists(sqlite, "index_epg_programmes_startMs_stopMs")

            // Nothing was dropped on the way through.
            assertEquals(3L, countRows(sqlite, "SELECT COUNT(*) FROM epg_channels", emptyArray()))
            assertEquals(1L, countRows(sqlite, "SELECT COUNT(*) FROM epg_programmes", emptyArray()))

            // Backfilled to exactly what the matcher would compute.
            assertEquals(EpgMatcher.normalizeForEpg("BBC One HD"), normNameOf(sqlite, "bbc1"))
            assertEquals(EpgMatcher.normalizeForEpg("bbc1"), normIdOf(sqlite, "bbc1"))
            assertEquals(EpgMatcher.normalizeForEpg("ПЕРВЫЙ КАНАЛ"), normNameOf(sqlite, "perviy"))

            // A channel the feed never named keeps a null name but still gets its id normalized —
            // otherwise it would drop out of the picker's search.
            assertEquals(null, normNameOf(sqlite, "nameless"))
            assertEquals(EpgMatcher.normalizeForEpg("nameless"), normIdOf(sqlite, "nameless"))
        } finally {
            db.close()
        }
    }

    /**
     * v43 → v44 (plan P5), from the published baseline (core-1.0.57 ships v43). Additive only: the
     * remembered zoom / volume / delay a user already has must survive untouched and read as
     * "no playlist known" (`sourceId = -1`) until the startup step fills it in; the new table and
     * every new column must exist, and a new quirk row must be writable.
     */
    @Test
    fun migrateVersion43To44_addsPlaybackQuirksAndPerPlaylistColumns_keepingPrefs() {
        context.deleteDatabase(DB_NAME)
        val db43 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db43, "tv.own.owntv.core.database.OwnTVDatabase/43.json")
            db43.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
            db43.execSQL(
                "INSERT INTO playback_prefs (profileId, contentKey, zoomMode, volumeBoost, audioDelayMs, updatedAt) " +
                    "VALUES (1, '10:LIVE:bbc-one', 'FILL', 130, 250, 5)",
            )
            db43.version = 43
        } finally {
            db43.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertTableExists(sqlite, "playback_quirks")
            assertIndexExists(sqlite, "index_playback_quirks_sourceId")
            assertIndexExists(sqlite, "index_playback_prefs_sourceId")
            listOf("sourceId", "audioLang", "subtitleLang").forEach { assertColumnExists(sqlite, "playback_prefs", it) }
            listOf("catchupTimezone", "catchupOffsetMin", "vodEnginePreference", "liveTuneTimeoutSecs", "httpReferer")
                .forEach { assertColumnExists(sqlite, "sources", it) }

            // The user's row came through whole, with the new columns at their "unknown" values.
            assertCount(sqlite, "playback_prefs", 1)
            sqlite.prepare("SELECT zoomMode, volumeBoost, audioDelayMs, sourceId, audioLang, subtitleLang FROM playback_prefs").use {
                assertTrue(it.step())
                assertEquals("FILL", it.getText(0))
                assertEquals(130L, it.getLong(1))
                assertEquals(250L, it.getLong(2))
                assertEquals(-1L, it.getLong(3))
                assertTrue(it.isNull(4))
                assertTrue(it.isNull(5))
            }

            assertCount(sqlite, "playback_quirks", 0)
            sqlite.execSQL(
                "INSERT INTO playback_quirks (contentKey, sourceId, mediaType, enginePin, audioOnly, audioDelayMs, updatedAt) " +
                    "VALUES ('10:LIVE:bbc-one', 10, 'LIVE', 'MPV', NULL, 250, 6)",
            )
            assertCount(sqlite, "playback_quirks", 1)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateVersion44To45_addsOriginalLanguage_keepingCachedRows() {
        context.deleteDatabase(DB_NAME)
        val db44 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db44, "tv.own.owntv.core.database.OwnTVDatabase/44.json")
            db44.execSQL(
                "INSERT INTO metadata_cache (`key`, tmdbId, imdbId, type, title, year, overview, posterPath, backdropPath, " +
                    "rating, genresJson, castJson, trailerKey, logoPath, updatedAt, airDate) " +
                    "VALUES ('movie:496243', 496243, NULL, 'movie', 'Parasite', 2019, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 7, NULL)",
            )
            db44.version = 44
        } finally {
            db44.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertColumnExists(sqlite, "metadata_cache", "originalLanguage")
            // The cached row came through; its language is unknown until the title is played.
            assertCount(sqlite, "metadata_cache", 1)
            sqlite.prepare("SELECT title, originalLanguage FROM metadata_cache").use {
                assertTrue(it.step())
                assertEquals("Parasite", it.getText(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateVersion45To46_dropsSoftwareDecode_keepingQuirkRows() {
        context.deleteDatabase(DB_NAME)
        val db45 = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db45, "tv.own.owntv.core.database.OwnTVDatabase/45.json")
            db45.execSQL(
                "INSERT INTO playback_quirks (contentKey, sourceId, mediaType, enginePin, audioOnly, audioDelayMs, softwareDecode, updatedAt) " +
                    "VALUES ('10:LIVE:bbc-one', 10, 'LIVE', 'MPV', 1, 250, NULL, 6)",
            )
            db45.version = 45
        } finally {
            db45.close()
        }

        val db = openWithAllMigrations()
        try {
            val sqlite = openForAssertions(db)
            assertEquals(0L, countRows(sqlite, "SELECT COUNT(*) FROM pragma_table_info('playback_quirks') WHERE name = 'softwareDecode'"))
            assertIndexExists(sqlite, "index_playback_quirks_sourceId")
            // The pin, the sound-only mark and the delay came through untouched.
            sqlite.prepare("SELECT enginePin, audioOnly, audioDelayMs FROM playback_quirks WHERE contentKey = '10:LIVE:bbc-one'").use {
                assertTrue(it.step())
                assertEquals("MPV", it.getText(0))
                assertEquals(1L, it.getLong(1))
                assertEquals(250L, it.getLong(2))
            }
        } finally {
            db.close()
        }
    }

    private fun normNameOf(db: SQLiteConnection, epgChannelId: String): String? =
        db.prepare("SELECT normName FROM epg_channels WHERE epgChannelId = ?").use {
            it.bindText(1, epgChannelId)
            if (it.step() && !it.isNull(0)) it.getText(0) else null
        }

    private fun normIdOf(db: SQLiteConnection, epgChannelId: String): String? =
        db.prepare("SELECT normId FROM epg_channels WHERE epgChannelId = ?").use {
            it.bindText(1, epgChannelId)
            if (it.step() && !it.isNull(0)) it.getText(0) else null
        }

    private fun bootstrapVersion40Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/40.json")
            db.execSQL(
                "INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName, iconUrl) VALUES " +
                    "(1, -3, 'bbc1', 'BBC One HD', NULL), " +
                    "(2, -3, 'perviy', 'ПЕРВЫЙ КАНАЛ', NULL), " +
                    "(3, -3, 'nameless', NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description, contentHash) " +
                    "VALUES (1, -3, 'bbc1', 1000, 2000, 'Programme', NULL, 0)",
            )
            db.version = 40
        } finally {
            db.close()
        }
    }

    private fun assertMissing(db: SQLiteConnection, type: String, name: String) {
        assertEquals(
            "$type $name should not exist yet",
            0L,
            countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?", arrayOf<Any?>(type, name)),
        )
    }

    private fun openWithAllMigrations() = Room.databaseBuilder(context, OwnTVDatabase::class.java, DB_NAME)
        .addMigrations(*OwnTVDatabase.ALL_MIGRATIONS)
        // Mirrors databaseModule — and it must be the SAME engine, not merely the driver API.
        // Android's own engine intercepts a bare `BEGIN` / `COMMIT` and maps it onto its
        // transaction API; the bundled one hands it to SQLite, which refuses a transaction inside
        // a transaction. A migration issuing its own BEGIN therefore passed here and crashed every
        // launch on a real phone, which is exactly the upgrade this file exists to prove.
        .setDriver(BundledSQLiteDriver())
        .allowMainThreadQueries()
        .build()

    private fun bootstrapVersion2Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/2.json")
            seedVersion2Data(db)
            db.version = 2
        } finally {
            db.close()
        }
    }

    private fun bootstrapVersion7Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/7.json")
            seedVersion7Data(db)
            db.version = 7
        } finally {
            db.close()
        }
    }

    private fun bootstrapVersion29Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/29.json")
            db.execSQL(
                "INSERT INTO sources (id, name, type, url, username, password, mac, " +
                    "stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature, " +
                    "userAgent, epgUrl, syncLive, syncMovies, syncSeries, hlsSupported, preferHls, " +
                    "livePrerollSecs, maxConnections, createdAt, lastSyncAt) VALUES " +
                    "(10, 'Source A', '${SourceType.XTREAM.name}', 'https://a.example', NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, 1, 1, 1, 0, 0, -1, 0, 1, NULL), " +
                    "(11, 'Source B', '${SourceType.XTREAM.name}', 'https://b.example', NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, 1, 1, 1, 0, 0, -1, 0, 1, NULL)",
            )
            db.version = 29
        } finally {
            db.close()
        }
    }

    /** A completed, eligible refresh. The attempt fields are explicit because [TrendingDao] rejects a
     *  replacement that does not describe a successful attempt — they default to "never attempted". */
    private fun trendingState(sourceId: Long, generationId: String) = TrendingSnapshotEntity(
        sourceId = sourceId,
        status = TrendingSnapshotStatus.ELIGIBLE,
        metadataLanguage = "de-DE",
        refreshedAt = 1_000,
        candidateFetchedAt = 900,
        generationId = generationId,
        itemCount = 5,
        matchedItemCount = 5,
        lastAttemptAt = 1_000,
        lastAttemptStatus = TrendingAttemptStatus.SUCCESS,
    )

    private fun trendingItems(sourceId: Long, generationId: String, titlePrefix: String) =
        (0 until 5).map { position ->
            val mediaType = if (position % 2 == 0) MediaType.MOVIE else MediaType.SERIES
            TrendingItemEntity(
                sourceId = sourceId,
                position = position,
                tmdbId = 1_000 + position,
                mediaType = mediaType,
                trendingRank = position + 1,
                providerItemId = 2_000L + position,
                providerRemoteId = "remote-$position",
                providerStableKey = "remote-$position",
                providerRawName = "$titlePrefix ${position + 1}",
                canonicalTitle = "$titlePrefix ${position + 1}",
                providerLanguage = "DE",
                advertisedQuality = "FHD",
                advertisedCapabilities = null,
                localizedTitle = "$titlePrefix ${position + 1}",
                originalTitle = null,
                year = 2026,
                overview = null,
                posterPath = null,
                backdropPath = null,
                rating = 8.0,
                trailerKey = null,
                generationId = generationId,
                refreshedAt = 1_000,
            )
        }

    private fun bootstrapVersion3Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            executeSchemaQueries(db, "tv.own.owntv.core.database.OwnTVDatabase/3.json")
            seedVersion3Data(db)
            db.version = 3
        } finally {
            db.close()
        }
    }

    /** Representative v3.2.0 user data (column lists match 3.json exactly). */
    private fun seedVersion3Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
        db.execSQL("INSERT INTO sources (id, name, type, url, username, password, userAgent, epgUrl, createdAt, lastSyncAt) VALUES (10, 'Playlist', '${SourceType.XTREAM.name}', 'https://example.test', 'user', 'pass', NULL, NULL, 2, 3)")
        db.execSQL("INSERT INTO profile_source (profileId, sourceId) VALUES (1, 10)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (20, 10, '${MediaType.LIVE.name}', 'Live', 'cat-live', 0)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (21, 10, '${MediaType.MOVIE.name}', 'Movies', 'cat-movies', 1)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (22, 10, '${MediaType.SERIES.name}', 'Series', 'cat-series', 2)")
        db.execSQL(
            "INSERT INTO channels (id, sourceId, categoryId, name, logoUrl, streamUrl, epgChannelId, number, remoteId, sortOrder, catchup, catchupDays, catchupSource) " +
                "VALUES (30, 10, 20, 'News', 'https://example.test/logo.png', 'https://example.test/live.m3u8', 'news-epg', 1, 'ch-30', 0, 1, 7, 'default')",
        )
        db.execSQL(
            "INSERT INTO movies (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, durationSecs, plot, streamUrl, containerExt, remoteId, addedAt, sortOrder) " +
                "VALUES (40, 10, 21, 'Movie One', 'https://example.test/movie.jpg', NULL, 2026, 8.1, 7200, 'Plot', 'https://example.test/movie.mp4', 'mp4', 'movie-40', 4, 0)",
        )
        db.execSQL(
            "INSERT INTO series (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, plot, remoteId, sortOrder) " +
                "VALUES (50, 10, 22, 'Show One', 'https://example.test/show.jpg', NULL, 2026, 8.4, 'Plot', 'series-50', 0)",
        )
        db.execSQL("INSERT INTO seasons (id, seriesId, seasonNumber, name, remoteId) VALUES (60, 50, 1, 'Season 1', 'season-1')")
        db.execSQL(
            "INSERT INTO episodes (id, seriesId, seasonId, seasonNumber, episodeNumber, name, plot, streamUrl, durationSecs, containerExt, remoteId) " +
                "VALUES (70, 50, 60, 1, 1, 'Episode 1', NULL, 'https://example.test/episode1.mp4', 3600, 'mp4', 'episode-70')",
        )
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (80, 1, '${MediaType.LIVE.name}', 30, 100)")
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (81, 1, '${MediaType.MOVIE.name}', 40, 101)")
        db.execSQL("INSERT INTO favorites (id, profileId, mediaType, itemId, addedAt) VALUES (82, 1, '${MediaType.SERIES.name}', 50, 102)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (85, 1, '${MediaType.MOVIE.name}', 40, 110)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (90, 1, '${MediaType.MOVIE.name}', 40, 120000, 7200000, 200)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (91, 1, '${MediaType.EPISODE.name}', 70, 150000, 3600000, 201)")
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (1, -1, 'news', 'News')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (10, -1, 'news', 1000, 2000, 'News One', 'A')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (11, -1, 'news', 1000, 2000, 'News Duplicate', 'B')")
    }

    private fun executeSchemaQueries(db: SQLiteDatabase, assetPath: String) {
        val json = JSONObject(testContext.assets.open(assetPath).bufferedReader().use { it.readText() })
        val database = json.getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setupQueries = database.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            db.execSQL(setupQueries.getString(index))
        }
    }

    private fun seedVersion2Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO profiles (id, name, avatarColor, avatarId, isKids, pinHash, createdAt) VALUES (1, 'Primary', 1122867, 7, 0, NULL, 1)")
        db.execSQL("INSERT INTO sources (id, name, type, url, username, password, userAgent, epgUrl, createdAt, lastSyncAt) VALUES (10, 'Playlist', '${SourceType.XTREAM.name}', 'https://example.test', 'user', 'pass', NULL, NULL, 2, 3)")
        db.execSQL("INSERT INTO profile_source (profileId, sourceId) VALUES (1, 10)")

        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (20, 10, '${MediaType.LIVE.name}', 'Live', 'cat-live', 0)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (21, 10, '${MediaType.MOVIE.name}', 'Movies', 'cat-movies', 1)")
        db.execSQL("INSERT INTO categories (id, sourceId, mediaType, name, remoteId, sortOrder) VALUES (22, 10, '${MediaType.SERIES.name}', 'Series', 'cat-series', 2)")

        db.execSQL("INSERT INTO channels (id, sourceId, categoryId, name, logoUrl, streamUrl, epgChannelId, number, remoteId, sortOrder) VALUES (30, 10, 20, 'News', 'https://example.test/logo.png', 'https://example.test/live.m3u8', 'news-epg', 1, 'ch-30', 0)")
        db.execSQL("INSERT INTO movies (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, durationSecs, plot, streamUrl, containerExt, remoteId, addedAt, sortOrder) VALUES (40, 10, 21, 'Movie One', 'https://example.test/movie.jpg', NULL, 2026, 8.1, 7200, 'Plot', 'https://example.test/movie.mp4', 'mp4', 'movie-40', 4, 0)")
        db.execSQL("INSERT INTO series (id, sourceId, categoryId, name, posterUrl, backdropUrl, year, rating, plot, remoteId, sortOrder) VALUES (50, 10, 22, 'Series One', 'https://example.test/show.jpg', NULL, 2026, 8.4, 'Plot', 'series-50', 0)")
        db.execSQL("INSERT INTO seasons (id, seriesId, seasonNumber, name, remoteId) VALUES (60, 50, 1, 'Season 1', 'season-1')")
        db.execSQL("INSERT INTO episodes (id, seriesId, seasonId, seasonNumber, episodeNumber, name, plot, streamUrl, durationSecs, containerExt, remoteId) VALUES (70, 50, 60, 1, 1, 'Episode 1', 'Plot', 'https://example.test/episode1.mp4', 3600, 'mp4', 'episode-70')")

        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (80, 1, '${MediaType.LIVE.name}', 30, 100)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (81, 1, '${MediaType.MOVIE.name}', 40, 101)")
        db.execSQL("INSERT INTO watch_history (id, profileId, mediaType, itemId, watchedAt) VALUES (82, 1, '${MediaType.SERIES.name}', 50, 102)")

        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (90, 1, '${MediaType.MOVIE.name}', 40, 120000, 7200000, 200)")
        db.execSQL("INSERT INTO playback_progress (id, profileId, mediaType, itemId, positionMs, durationMs, updatedAt) VALUES (91, 1, '${MediaType.EPISODE.name}', 70, 150000, 3600000, 201)")
    }

    private fun seedVersion7Data(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (1, -1, 'news', 'News')")
        db.execSQL("INSERT INTO epg_channels (id, sourceId, epgChannelId, displayName) VALUES (2, -1, 'sports', 'Sports')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (10, -1, 'news', 1000, 2000, 'News One', 'A')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (11, -1, 'news', 1000, 2000, 'News Duplicate', 'B')")
        db.execSQL("INSERT INTO epg_programmes (id, sourceId, epgChannelId, startMs, stopMs, title, description) VALUES (12, -1, 'sports', 3000, 4000, 'Sports One', 'C')")
    }

    private fun assertTableExists(db: SQLiteConnection, table: String) {
        assertEquals(1L, countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table)))
    }

    private fun assertIndexExists(db: SQLiteConnection, index: String) {
        assertEquals(1L, countRows(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf<Any?>(index)))
    }

    private fun assertColumnExists(db: SQLiteConnection, table: String, column: String) {
        assertEquals(
            1L,
            countRows(
                db,
                "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = ?",
                arrayOf<Any?>(column),
            ),
        )
    }

    private fun assertColumnValue(db: SQLiteConnection, table: String, column: String, rowId: Long, expected: Long?) {
        db.prepare("SELECT `$column` FROM `$table` WHERE id = ?").use { statement ->
            statement.bindLong(1, rowId)
            if (!statement.step()) throw AssertionError("Row $rowId not found in $table")
            val actual = if (statement.isNull(0)) null else statement.getLong(0)
            assertEquals(expected, actual)
        }
    }

    private fun assertCount(db: SQLiteConnection, table: String, expected: Long) {
        assertEquals(expected, countRows(db, "SELECT COUNT(*) FROM `$table`"))
    }

    private fun countRows(db: SQLiteConnection, sql: String, args: Array<Any?> = emptyArray()): Long {
        db.prepare(sql).use { statement ->
            // Binds are 1-based on the driver API; only text and long appear in this file.
            args.forEachIndexed { i, arg ->
                when (arg) {
                    null -> statement.bindNull(i + 1)
                    is Long -> statement.bindLong(i + 1, arg)
                    is Int -> statement.bindInt(i + 1, arg)
                    else -> statement.bindText(i + 1, arg.toString())
                }
            }
            if (!statement.step()) return 0L
            return statement.getLong(0)
        }
    }

    /**
     * A driver connection to the test database file.
     *
     * Phase B configures a SQLiteDriver, and `RoomDatabase.openHelper` throws once one is set — so
     * this test can no longer reach the file that way, and it should not want to: the whole point
     * of the phase is that the driver path is the one users run. Room's public `useWriterConnection`
     * hands back a `Transactor`, which is not a `SQLiteConnection`, so the schema assertions open
     * their own connection to the same file instead.
     */
    /**
     * A driver connection to the migrated database.
     *
     * **Room opens lazily, and touching a connection is what runs the migration chain.** The old
     * code got that for free from `db.openHelper.readableDatabase`; a raw `driver.open(path)` does
     * not — it opens the file exactly as it was on disk. Porting this test to the driver API
     * without the line below made nine of eleven tests read a pre-migration database and report
     * `v2 did not reach the current version expected:<42> but was:<2>`.
     */
    private fun openForAssertions(db: OwnTVDatabase): SQLiteConnection {
        runBlocking { db.useWriterConnection { } }
        val connection = BundledSQLiteDriver().open(context.getDatabasePath(DB_NAME).absolutePath)
        // Foreign keys are OFF by default on a bare SQLite connection; Room turns them on for its
        // own. Without this the cascade assertions silently pass their DELETE and then find the
        // child rows still present — which is how the port first failed, reporting a trending
        // snapshot count of 2 where 1 was expected.
        connection.execSQL("PRAGMA foreign_keys=ON")
        return connection
    }

    /** `SQLiteConnection` has no `.version`; the schema version is PRAGMA user_version. */
    private fun userVersionOf(db: SQLiteConnection): Int =
        db.prepare("PRAGMA user_version").use { if (it.step()) it.getInt(0) else -1 }

    private fun <R> withConnection(db: OwnTVDatabase, block: (SQLiteConnection) -> R): R =
        openForAssertions(db).use(block)

    companion object {
        private const val DB_NAME = "owntv-migration-test.db"

        /**
         * The end of the migration chain, not a hand-kept number: as a constant this fell three
         * versions behind, and a stale value here does not fail loudly — it quietly stops testing the
         * newest hops, which are exactly the ones nobody has upgraded across yet.
         *
         * `@Database(version = …)` cannot be read back (Room's annotation is not retained at runtime),
         * but this is the better source anyway, because it is self-checking: if the chain ever stops
         * short of the entity version, [everyExportedSchemaVersionMigratesToCurrent] fails on the
         * version it actually reached instead of silently agreeing with itself.
         */
        private val CURRENT_VERSION = OwnTVDatabase.ALL_MIGRATIONS.maxOf { it.endVersion }

        /**
         * Every version with an exported schema that a real database can be sitting at.
         * Deliberate omissions:
         *  - 1 and 8 were never exported, so no database can be reconstructed at them.
         *  - 5 exists on disk but was never public and has no migration out of it (MIGRATION_4_6
         *    jumps over it); see [migration4to6IsANoOpBecauseVersion5WasNeverPublic].
         */
        /** Exported by no release; see [MIGRATABLE_START_VERSIONS]. */
        private val WITHDRAWN_VERSIONS = setOf(37)

        /**
         * Versions a user could actually be upgrading from.
         *
         * 5 and 8 never shipped, and neither did **37**: that version was withdrawn mid-development
         * after it had already run on a test device, and the change was reissued as v38 — so no
         * `37.json` was ever exported. Leaving it in this range made the whole sweep die with
         * `FileNotFoundException: …/37.json`, which meant the one test guarding against wiping a
         * user's profiles and history could not finish. Derived from the schemas that exist rather
         * than from a hand-written range, so the next withdrawn version cannot repeat this.
         */
        private val MIGRATABLE_START_VERSIONS =
            (listOf(2, 3, 4, 6, 7, 9) + (10 until CURRENT_VERSION)) - WITHDRAWN_VERSIONS

    }
}
