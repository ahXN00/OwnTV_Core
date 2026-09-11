package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.SourceEntity

/** Per-type counts after a playlist import, for the success breakdown. */
data class SyncCounts(val channels: Int, val movies: Int, val series: Int, val epg: Int = 0)

/**
 * Runs after a playlist syncs: returns the per-type content counts for the success message. EPG is NOT
 * auto-created/synced here — that's slow and not everyone wants it. The user adds a guide explicitly via
 * Settings → EPG sources, where the form pre-fills the playlist's own guide URL (Xtream `xmltv.php` /
 * M3U `url-tvg`) so it's still one tap.
 */
class ImportFinalizer(
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val db: OwnTVDatabase,
    private val bulkInsertHelper: BulkInsertHelper,
    private val metadataDao: tv.own.owntv.core.database.dao.MetadataDao,
    private val epgSourceStore: tv.own.owntv.core.epg.EpgSourceStore,
) {
    suspend fun finalize(source: SourceEntity, deferIndexes: Boolean = false): SyncCounts {
        val counts = contentCounts(source.id)
        registerPortalGuide(source)
        // C4: bounded TTL eviction of the TMDB caches — they grow without limit as the user
        // browses. Piggy-backed here (the operation that changes data), never on cold start.
        // Indexed DELETE on updatedAt; evicted rows simply re-fetch on next focus.
        runCatching {
            val cutoff = System.currentTimeMillis() - METADATA_TTL_MS
            val cache = metadataDao.evictCacheOlderThan(cutoff)
            val matches = metadataDao.evictMatchesOlderThan(cutoff)
            if (cache > 0 || matches > 0) Log.i(TAG, "Metadata eviction: cache=$cache matches=$matches")
        }
        if (!deferIndexes) {
            // A sync does REPLACE on 100k+ rows, which invalidates SQLite's planner statistics
            // (sqlite_stat1). With stale stats the query planner IGNORES the single-column and
            // composite indices on channels/movies/series and falls back to a full table scan +
            // temp B-tree sort — the Movies/Series grids' 2–3s cold-open. ANALYZE refreshes the
            // stats so the existing indices get used again, dropping the open back to <300ms. Same
            // trick EPG uses after its bulk sync (EpgRepository.refreshUrl). Safe, idempotent,
            // <1s even on 100k rows.
            ensureContentIndexes()
        }
        return counts
    }

    /**
     * A Stalker portal that publishes no XMLTV feed still HAS a guide — its own — and until now there
     * was no way to reach it, so those users saw an empty Guide and no catch-up list while the same
     * portal worked in other players. Put it on the Settings → EPG list once the portal has synced, so
     * it is there to be pressed.
     *
     * Deliberately register-only. Nothing is downloaded here: EPG has been user-initiated since
     * v2.2.0, and an automatic guide crawl after every catalog sync is exactly what that decision
     * ruled out. A portal that advertises an XMLTV feed keeps using it (it is the better guide, with
     * real channel names) — this is only for the portals that offer nothing else.
     */
    private suspend fun registerPortalGuide(source: SourceEntity) {
        if (source.type != tv.own.owntv.core.model.SourceType.STALKER) return
        if (!source.epgUrl.isNullOrBlank()) return
        // The user can switch a portal's own guide off per playlist; respect it, and do not put the
        // entry back on the list every time the catalog syncs.
        if (!source.importPortalEpg) return
        runCatching {
            epgSourceStore.ensure(
                source.name,
                tv.own.owntv.core.repository.EpgRepository.stalkerGuideUrl(source.id),
                source.userAgent,
            )
        }.onFailure { Log.w(TAG, "Unable to register the portal guide for sourceId=${source.id}", it) }
    }

    /** Current content counts for a source (no EPG) — for the success message and the Playlists list rows. */
    suspend fun contentCounts(sourceId: Long): SyncCounts = SyncCounts(
        channels = channelDao.countForSourceOnce(sourceId),
        movies = movieDao.countForSourceOnce(sourceId),
        series = seriesDao.countForSourceOnce(sourceId),
    )

    /**
     * Ensure the Movies/Series/Channels grid read-indices exist and the planner statistics are fresh.
     * Fresh imports may drop the non-unique content indices to speed up bulk inserts, so this rebuilds the
     * single-column and composite read paths, then refreshes `sqlite_stat1` with ANALYZE so SQLite keeps
     * choosing them instead of reverting to a full-table sort. Mirrors `EpgRepository.ensureEpgIndexes`.
     * Called inline for normal re-syncs and from the deferred background worker for first-ever imports.
     */
    suspend fun ensureContentIndexes() = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "ensureContentIndexes start")
        runCatching {
            val w = db.openHelper.writableDatabase
            val indexesStartedAt = SystemClock.elapsedRealtime()
            // Idempotent (IF NOT EXISTS): no-op once the indices exist. Covers DBs that somehow lack
            // them. Canonical list shared with BulkInsertHelper's restore and the migration heal, so
            // this pass can never miss an index those paths expect (the old hand-copied list omitted
            // the rating-sort indexes — drift that crashed the next migration's schema validation).
            listOf("channels", "movies", "series").forEach { table ->
                OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.getValue(table).forEach { w.execSQL(it) }
            }
            Log.d(TAG, "ensureContentIndexes create indexes ms=${SystemClock.elapsedRealtime() - indexesStartedAt}")

            // Refresh planner stats so the indices above are chosen for the grids' "WHERE … ORDER BY …" —
            // without this, stale stats from a bulk REPLACE make SQLite ignore them and full-sort.
            val analyzeStartedAt = SystemClock.elapsedRealtime()
            bulkInsertHelper.analyzeTables("movies", "series", "channels", "categories")
            Log.d(TAG, "ensureContentIndexes analyze ms=${SystemClock.elapsedRealtime() - analyzeStartedAt}")
        }.onSuccess {
            Log.i(TAG, "ensureContentIndexes end ms=${SystemClock.elapsedRealtime() - startedAt}")
        }.onFailure {
            Log.w(TAG, "ensureContentIndexes failed ms=${SystemClock.elapsedRealtime() - startedAt}", it)
        }
    }

    companion object {
        private const val TAG = "ImportFinalizer"

        /** TMDB cache TTL (C4): entries untouched for this long are evicted after a sync.
         *  90 days ≫ the repository's own refresh window, so this only drops long-abandoned rows. */
        private const val METADATA_TTL_MS = 90L * 24 * 60 * 60 * 1000
    }
}
