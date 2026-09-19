package tv.own.owntv.core.database

import android.os.SystemClock
import android.util.Log
import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BulkInsertHelper(
    private val db: OwnTVDatabase,
) {
    /** Writers currently inside [withOptimizedBulkInsert] for one table, plus its active bulk mode. */
    private class TableBulkState {
        var writers = 0
        var dropped: BulkIndexState? = null
        var ftsOnly = false
    }

    private val tableStates = HashMap<String, TableBulkState>()

    suspend fun <T> withOptimizedBulkInsert(
        table: String,
        ftsTable: String? = null,
        eligible: Boolean,
        ftsOnly: Boolean = false,
        block: suspend () -> T,
    ): T {
        // Register as a writer on this table. The FIRST eligible writer on an empty table drops the
        // indexes/FTS trigger; anyone arriving while that mode is active — eligible or not — joins
        // the writer count instead of racing it, so the restore below can never run while another
        // sync is still inserting. (The old shape checked tableIsEmpty BEFORE taking the lock, so a
        // second source could bypass the mode once the first had inserted rows, then collide with
        // its restore — the truncated-movies/skipped-series concurrent-sync bug.) block() runs
        // OUTSIDE the lock, so concurrent syncs fetch/parse/insert in parallel.
        stateMutex.withLock {
            val state = tableStates.getOrPut(table) { TableBulkState() }
            if (eligible && state.dropped == null && tableIsEmpty(table)) {
                state.dropped = dropIndexesForBulkInsert(table, ftsTable)
                state.ftsOnly = ftsOnly
            }
            state.writers++
        }
        try {
            return block()
        } finally {
            // NonCancellable: a cancelled sync must still deregister and (as the last writer)
            // restore — otherwise the table stays index-less until the next app open heals it.
            withContext(NonCancellable) {
                stateMutex.withLock {
                    val state = tableStates.getValue(table)
                    state.writers--
                    val toRestore = state.dropped?.takeIf { state.writers == 0 }
                    if (toRestore != null) {
                        state.dropped = null
                        restoreIndexes(toRestore, ftsOnly = state.ftsOnly)
                    }
                }
            }
        }
    }

    suspend fun analyzeTables(vararg tables: String) {
        tables.forEach { requireKnownAnalyzeTable(it) }
        db.useWriterConnection { connection ->
            tables.forEach { connection.execSQL("ANALYZE `$it`") }
        }
    }

    suspend fun tableIsEmpty(table: String): Boolean {
        requireKnownTable(table)
        return db.useWriterConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM `$table`") { it.step() && it.getLong(0) == 0L }
        }
    }

    private suspend fun dropIndexesForBulkInsert(table: String, ftsTable: String?): BulkIndexState {
        requireKnownTable(table)
        if (ftsTable != null) requireKnownFtsTable(ftsTable)

        val indexSqls = mutableListOf<String>()
        var triggerSql: String? = null
        db.useWriterConnection { connection ->
            // Collect first, drop second. The statement is reading sqlite_master while DROP INDEX
            // mutates it, and stepping a cursor over a catalogue being rewritten underneath is the
            // kind of thing that works until it does not.
            val doomed = mutableListOf<Pair<String, String>>()
            connection.usePrepared(
                "SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='$table' AND sql IS NOT NULL",
            ) { statement ->
                while (statement.step()) {
                    val name = statement.getText(0)
                    val sql = if (statement.isNull(1)) null else statement.getText(1)
                    if (sql != null && !sql.contains("UNIQUE", ignoreCase = true)) doomed.add(name to sql)
                }
            }
            doomed.forEach { (name, sql) ->
                indexSqls.add(sql)
                connection.execSQL("DROP INDEX IF EXISTS `$name`")
            }

            val triggerName = ftsTable?.let { "room_fts_content_sync_${it}_AFTER_INSERT" }
            if (triggerName != null) {
                triggerSql = connection.usePrepared(
                    "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='$triggerName'",
                ) { statement -> if (statement.step() && !statement.isNull(0)) statement.getText(0) else null }
                if (triggerSql != null) connection.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
            }
        }

        Log.i(TAG, "Dropped ${indexSqls.size} indexes${if (triggerSql != null) " + FTS trigger" else ""} on $table for bulk insert")
        return BulkIndexState(table, ftsTable, indexSqls, triggerSql)
    }

    private suspend fun restoreIndexes(state: BulkIndexState, ftsOnly: Boolean = false) {
        val start = SystemClock.elapsedRealtime()
        // Restore the canonical Room-expected set, not the pre-drop snapshot: if the DB was already
        // drifted when the snapshot was taken, the snapshot would preserve the gap and the next
        // migration's schema validation would crash the app.
        val canonical = OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES[state.table].orEmpty()
        db.useWriterConnection { connection ->
            if (!ftsOnly) {
                canonical.forEach { connection.execSQL(it) }
            }
            if (state.ftsTable != null) {
                connection.execSQL("INSERT INTO `${state.ftsTable}`(`${state.ftsTable}`) VALUES('rebuild')")
            }
            if (state.triggerSql != null) connection.execSQL(state.triggerSql)
        }

        val restoredIndexCount = if (ftsOnly) 0 else canonical.size
        val msg = "Restored $restoredIndexCount indexes${if (state.ftsTable != null) " + FTS" else ""} on ${state.table} ms=${SystemClock.elapsedRealtime() - start}"
        // Zero is never valid for a table whose indexes we just dropped: it means the canonical set
        // is missing, so the table is now permanently index-less. Shout about it.
        if (!ftsOnly && canonical.isEmpty()) {
            Log.e(TAG, "$msg — no canonical indexes for ${state.table}, indexes were dropped and NOT restored")
        } else {
            Log.i(TAG, msg)
        }
    }

    private fun requireKnownTable(table: String) {
        require(table in KNOWN_TABLES) { "Unknown table: $table" }
    }

    private fun requireKnownAnalyzeTable(table: String) {
        require(table in KNOWN_ANALYZE_TABLES) { "Unknown table: $table" }
    }

    private fun requireKnownFtsTable(ftsTable: String) {
        require(ftsTable in KNOWN_FTS) { "Unknown FTS table: $ftsTable" }
    }

    private data class BulkIndexState(
        val table: String,
        val ftsTable: String?,
        val indexCreateSqls: List<String>,
        val triggerSql: String?,
    )

    companion object {
        const val CHUNK = 5_000
        const val CHUNK_FRESH = 10_000

        private const val TAG = "BulkInsertHelper"
        // Derived, never hand-maintained: restoreIndexes() rebuilds a table from its canonical
        // entry in EXPECTED_NON_UNIQUE_INDEXES, so a table that is bulk-droppable but missing from
        // that map would have its indexes dropped and never restored — silently, until the next
        // migration's schema validation crash-loops the app on upgrade (the 4.1.0 failure mode).
        // Deriving the set makes that combination impossible to express.
        internal val KNOWN_TABLES: Set<String> get() = OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.keys
        // Deliberately wider than KNOWN_TABLES: these are analyzed but never bulk-dropped.
        private val KNOWN_ANALYZE_TABLES: Set<String> get() = KNOWN_TABLES + setOf("categories", "epg_channels")
        private val KNOWN_FTS = setOf("channels_fts", "movies_fts", "series_fts")

        // One app-wide lock for bulk-mode state + index/FTS DDL — held only for the short state
        // transitions (and the last writer's restore), never across a sync's insert loop.
        private val stateMutex = Mutex()
    }
}
