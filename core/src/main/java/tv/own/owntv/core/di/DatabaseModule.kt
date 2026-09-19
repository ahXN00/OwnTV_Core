package tv.own.owntv.core.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.core.database.OwnTVDatabase

/**
 * Provides the Room database (WAL journal mode for fast concurrent reads during large imports) and
 * each DAO. Foreign-key enforcement is on by default in Room.
 *
 * There is deliberately NO destructive-migration fallback: [OwnTVDatabase.ALL_MIGRATIONS] covers every
 * shipped version, and a wipe-on-mismatch "safety net" would silently delete a user's profiles,
 * sources, favorites, history and resume positions on the first schema surprise. A missing or
 * failing migration must surface as an error we can fix, not as an empty app.
 */
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), OwnTVDatabase::class.java, OwnTVDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Plan Phase C. Phase B moved to the driver API on the platform engine so that any
            // breakage there could only be the rewrite's fault; this is the engine swap itself, and
            // it really is one line — which was the whole reason for splitting the two phases.
            //
            // What it buys: minSdk 26 means an Android 8 device runs SQLite 3.18, which has no
            // UPSERT (3.24), no window functions (3.25), no UPDATE…FROM (3.33) and no RETURNING
            // (3.35). Bundling the engine makes those available on every device instead of none,
            // which is what Phase D spends.
            //
            // Revert path, if 3.x ever misbehaves on a specific device: change this to
            // AndroidSQLiteDriver(). Nothing else in the codebase knows the difference.
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*OwnTVDatabase.ALL_MIGRATIONS)
            .addCallback(object : RoomDatabase.Callback() {
                // Self-heal index/FTS drift on every open (no-op when healthy): an interrupted bulk
                // import can leave BulkInsertHelper's dropped indexes missing, which is invisible
                // now but fails Room's full-schema validation at the NEXT version bump (the
                // 4.0.x → 4.1.0 crash-loop). Healing here repairs drift long before that migration.
                //
                // ST4: gated behind a single sqlite_master count so a healthy open pays one index
                // lookup instead of ~30 DDL statements on the thread issuing the first query. The
                // heal itself stays in onOpen — moving it off would let a query beat it to a missing
                // index. The OwnTVPerf timeline reports both the cost and whether it healed.
                override fun onOpen(connection: SQLiteConnection) {
                    // Connection tuning, applied before anything queries. Both settings are per-open,
                    // so they belong here rather than in a migration.
                    //
                    // synchronous=NORMAL is the documented companion to WAL: FULL fsyncs the WAL on
                    // every single commit, which on a TV's eMMC is the dominant cost of a sync writing
                    // tens of thousands of rows. NORMAL still fsyncs at checkpoints, so the durability
                    // it trades away is only "the last few committed transactions survive a sudden
                    // power cut" — not database integrity, which WAL still guarantees. A cut power
                    // cable can cost the tail of a catalogue import, and that re-syncs.
                    //
                    // The page cache is per-connection and defaults to ~2MB. Home alone opens a dozen
                    // profile-scoped queries over the same handful of index pages, and every screen
                    // after it re-reads them; a negative value is SQLite's "this many KiB" form.
                    runCatching {
                        connection.execSQL("PRAGMA synchronous=NORMAL")
                        connection.execSQL("PRAGMA cache_size=-8000")
                    }
                    // Plan B5, answered in code rather than by reading a log off a device.
                    // `setJournalMode(WRITE_AHEAD_LOGGING)` is a Support-path builder API and it is
                    // undocumented whether it still applies once a driver is configured. Losing WAL
                    // silently would look like a general slowdown with no cause, so this reads the
                    // mode back and sets it if the builder did not. Both are cheap and idempotent.
                    val journal = runCatching {
                        connection.prepare("PRAGMA journal_mode").use { if (it.step()) it.getText(0) else "" }
                    }.getOrDefault("")
                    val journalFixed = if (!journal.equals("wal", ignoreCase = true)) {
                        runCatching {
                            connection.prepare("PRAGMA journal_mode=WAL").use { it.step() }
                        }.isSuccess
                    } else {
                        false
                    }
                    // Plan C2: record which engine actually ran, so a release logcat answers it
                    // without guesswork. Release builds are obfuscated — ask for mapping.txt
                    // alongside any crash trace from this phase.
                    val engineVersion = runCatching {
                        connection.prepare("SELECT sqlite_version()").use { if (it.step()) it.getText(0) else "?" }
                    }.getOrDefault("?")
                    val healed = runCatching { OwnTVDatabase.healSchemaIfDrifted(connection) }.getOrDefault(false)
                    // The stamp is not optional. Under a driver, a Callback.onOpen written against
                    // the old Support type is silently skipped — no exception, nothing in CI, and
                    // the PRAGMAs and the schema self-heal simply stop running. This line is the
                    // only thing that makes that failure visible, so it reports what actually ran.
                    tv.own.owntv.core.util.Perf.stamp(
                        "db-open(sqlite=" + engineVersion + ", journal=" + journal.lowercase() +
                            (if (journalFixed) " forced-wal" else "") +
                            ", heal=" + (if (healed) "repaired" else "clean") + ")",
                    )
                }
            })
            .build()
    }

    single { get<OwnTVDatabase>().profileDao() }
    single { get<OwnTVDatabase>().sourceDao() }
    single { get<OwnTVDatabase>().categoryDao() }
    single { get<OwnTVDatabase>().channelDao() }
    single { get<OwnTVDatabase>().movieDao() }
    single { get<OwnTVDatabase>().seriesDao() }
    single { get<OwnTVDatabase>().favoriteDao() }
    single { get<OwnTVDatabase>().historyDao() }
    single { get<OwnTVDatabase>().progressDao() }
    single { get<OwnTVDatabase>().contentOrderDao() }
    single { get<OwnTVDatabase>().playbackPrefsDao() }
    single { get<OwnTVDatabase>().customCategoryDao() }
    single { get<OwnTVDatabase>().seriesSortOrderDao() }
    single { get<OwnTVDatabase>().tombstoneDao() }
    single { get<OwnTVDatabase>().tvProviderProgramDao() }
    single { get<OwnTVDatabase>().downloadDao() }
    single { get<OwnTVDatabase>().recordingDao() }
    single { get<OwnTVDatabase>().catalogBackfillDao() }
    single { get<OwnTVDatabase>().epgDao() }
    single { get<OwnTVDatabase>().metadataDao() }
    single { get<OwnTVDatabase>().trendingDao() }
    single { get<OwnTVDatabase>().subtitleDao() }
}
