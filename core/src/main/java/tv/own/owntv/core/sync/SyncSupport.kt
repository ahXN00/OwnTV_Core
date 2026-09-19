package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentHashProjection
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import kotlin.coroutines.CoroutineContext

/**
 * Shared building blocks for the per-source-type syncers ([XtreamSyncer], [M3uSyncer], later
 * StalkerSyncer): the chunked streaming inserter with cross-pass dedupe, the hash-diffed stable
 * upsert / fresh insert pair, category refresh + stable upsert, and stale-row pruning. Extracted
 * from SyncManager so each syncer stays focused on its own protocol flow while any fix to the
 * shared machinery lands once.
 */
internal class SyncSupport(
    private val categoryDao: CategoryDao,
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    val sourceDao: SourceDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) {
    val channelAdapter = ContentAdapter<ChannelEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        sourceIdOf = { it.sourceId },
        updateAll = { channelDao.updateAll(it) },
        insertAll = { channelDao.insertAll(it) },
        moveAll = { m -> channelDao.applyPositionMoves(m.map { it.id }, m.map { it.sortOrder }) },
        moveRanges = { src, r -> channelDao.applyPositionRanges(src, r.map { it.fromSortOrder }, r.map { it.toSortOrder }, r.map { it.delta }) },
        remoteIdsForSource = { channelDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> channelDao.deleteByRemoteIds(src, ids) },
        loadHashes = { channelDao.contentHashesForSource(it) },
        remoteIdsInCategories = { src, cats -> channelDao.remoteIdsInCategories(src, cats) },
    )

    val movieAdapter = ContentAdapter<MovieEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        sourceIdOf = { it.sourceId },
        updateAll = { movieDao.updateAll(it) },
        insertAll = { movieDao.insertAll(it) },
        moveAll = { m -> movieDao.applyPositionMoves(m.map { it.id }, m.map { it.sortOrder }) },
        moveRanges = { src, r -> movieDao.applyPositionRanges(src, r.map { it.fromSortOrder }, r.map { it.toSortOrder }, r.map { it.delta }) },
        remoteIdsForSource = { movieDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> movieDao.deleteByRemoteIds(src, ids) },
        loadHashes = { movieDao.contentHashesForSource(it) },
        countsByCategory = { src -> movieDao.countsByCategoryOnce(src).associate { it.categoryId to it.itemCount } },
        remoteIdsForCategory = { src, cat -> movieDao.remoteIdsForCategory(src, cat) },
        remoteIdsInCategories = { src, cats -> movieDao.remoteIdsInCategories(src, cats) },
    )

    val seriesAdapter = ContentAdapter<SeriesEntity>(
        remoteIdOf = { it.remoteId },
        hashOf = { it.computeContentHash() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> if (id != null) row.copy(id = id, contentHash = hash) else row.copy(contentHash = hash) },
        sourceIdOf = { it.sourceId },
        updateAll = { seriesDao.updateSeries(it) },
        insertAll = { seriesDao.insertSeries(it) },
        moveAll = { m -> seriesDao.applyPositionMoves(m.map { it.id }, m.map { it.sortOrder }) },
        moveRanges = { src, r -> seriesDao.applyPositionRanges(src, r.map { it.fromSortOrder }, r.map { it.toSortOrder }, r.map { it.delta }) },
        remoteIdsForSource = { seriesDao.remoteIdsForSource(it) },
        deleteByRemoteIds = { src, ids -> seriesDao.deleteByRemoteIds(src, ids) },
        loadHashes = { seriesDao.contentHashesForSource(it) },
        countsByCategory = { src -> seriesDao.countsByCategoryOnce(src).associate { it.categoryId to it.itemCount } },
        remoteIdsForCategory = { src, cat -> seriesDao.remoteIdsForCategory(src, cat) },
        remoteIdsInCategories = { src, cats -> seriesDao.remoteIdsInCategories(src, cats) },
    )

    /**
     * Hash-diffed stable upsert: unchanged rows are skipped, changed rows keep their local id.
     *
     * There are three outcomes, not two (S1). `sortOrder` is deliberately not part of
     * `computeContentHash()` — folding it in would change every stored hash at once, so the first
     * resync after such a change rewrites a 170k-row catalog end to end. Instead the stored
     * `sortOrder` travels alongside the hash and is compared separately: a row whose content is
     * identical but whose provider position moved is written (so browse order follows the provider)
     * and counted as `moved`, while a row that matches on both is still skipped entirely. Manual
     * reorder is unaffected — it lives in `content_order` and is applied over the top of this.
     */
    suspend fun <T> upsertStable(
        rows: List<T>,
        hashDeferred: Deferred<Map<String, StoredRow>>,
        adapter: ContentAdapter<T>,
    ): UpsertStats = upsertStable(rows, hashDeferred.await(), adapter)

    /** First-ever import: no diffing, just hash + insert. */
    suspend fun <T> insertFresh(rows: List<T>, adapter: ContentAdapter<T>): UpsertStats {
        val hashed = rows.map { adapter.copyWith(it, null, adapter.hashOf(it)) }
        adapter.insertAll(hashed)
        return UpsertStats(inserted = hashed.size)
    }

    private fun List<ContentHashProjection>.toHashLookup(): Map<String, StoredRow> =
        associateBy({ it.remoteId }, { StoredRow(it.id, it.contentHash, it.sortOrder) })

    fun asyncHashLoad(
        scope: CoroutineScope,
        label: String,
        sourceId: Long,
        load: suspend () -> List<ContentHashProjection>,
    ): Deferred<Map<String, StoredRow>> = scope.async {
        val start = SystemClock.elapsedRealtime()
        load().toHashLookup().also {
            Log.d(TAG, "$label hash map loaded sourceId=$sourceId size=${it.size} ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    /**
     * Catalog-shrink guard. A pass that *looks* complete but saw only a fraction of the catalog (a
     * provider serving a truncated list, a panel briefly answering with a tiny payload) would
     * otherwise delete most of the user's content — and with it every favorite, history entry and
     * resume position keyed to those rows. So when a source already holds a meaningful number of
     * rows and this pass would remove more than half of them, the prune is skipped and the run
     * reports a warning instead. A fresh/small source (≤ [PRUNE_MIN_ROWS]) prunes normally, and a
     * user-requested force-clean sync ([SyncStatsCollector.forcePrune]) bypasses the guard entirely
     * for the case where the catalog really did shrink.
     *
     * Returns true when the prune may proceed.
     */
    private fun pruneAllowed(label: String, sourceId: Long, stored: Int, stale: Int, stats: SyncStatsCollector): Boolean {
        if (shouldPrune(stored, stale, stats.forcePrune)) return true
        val percent = (stale * 100) / stored
        Log.w(TAG, "$label prune skipped sourceId=$sourceId reason=catalog_shrink stored=$stored stale=$stale")
        stats.addWarning(
            SyncWarning(
                phase = label,
                kind = SyncWarningKind.CATALOG_SHRINK(stored = stored, percentFewer = percent),
            ),
        )
        return false
    }

    suspend fun pruneCategories(sourceId: Long, type: MediaType, seenRemoteIds: Set<String>, label: String, stats: SyncStatsCollector) {
        val start = SystemClock.elapsedRealtime()
        val existing = categoryDao.remoteIdsForSource(sourceId, type)
        val stale = existing.filterNot(seenRemoteIds::contains)
        if (!pruneAllowed("$label categories", sourceId, existing.size, stale.size, stats)) return
        stale.chunked(QUERY_CHUNK).forEach { categoryDao.deleteByRemoteIds(sourceId, type, it) }
        if (stale.isNotEmpty()) stats.processedCounts.merge(CATEGORIES_REMOVED_KEY, stale.size, Int::plus)
        Log.i(TAG, "$label category prune sourceId=$sourceId type=$type stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    suspend fun pruneRemoteIds(
        label: String,
        sourceId: Long,
        seenRemoteIds: Set<String>,
        stats: SyncStatsCollector,
        loadExisting: suspend (Long) -> List<String>,
        deleteRemoteIds: suspend (Long, List<String>) -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        val existing = loadExisting(sourceId)
        val stale = existing.filterNot(seenRemoteIds::contains)
        if (!pruneAllowed(label, sourceId, existing.size, stale.size, stats)) return
        stale.chunked(QUERY_CHUNK).forEach { deleteRemoteIds(sourceId, it) }
        Log.i(TAG, "$label content prune sourceId=$sourceId stale=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
    }

    suspend fun refreshCategories(
        s: SourceEntity,
        type: MediaType,
        parsed: List<tv.own.owntv.core.parser.XtCategory>,
        stats: SyncStatsCollector,
    ): CategoryRefresh {
        val start = SystemClock.elapsedRealtime()
        // s.lastSyncAt never changes during a sync run (SyncManager stamps it only after the syncer
        // returns), so this is safe to derive here rather than threading a freshSource param through
        // every call site.
        val freshSource = s.lastSyncAt == null
        Log.d(TAG, "refreshCategories start sourceId=${s.id} type=$type count=${parsed.size}")
        val uniqueCategories = parsed.distinctBy { it.id }
        val existing = existingCategoriesByRemoteId(s.id, type, uniqueCategories.map { it.id })
        // sortOrder = provider index, so the rail follows the provider's category order.
        val entities = uniqueCategories.mapIndexed { i, c ->
            CategoryEntity(
                id = existing[c.id]?.id ?: 0,
                sourceId = s.id,
                mediaType = type,
                name = c.name,
                remoteId = c.id,
                sortOrder = i,
            )
        }
        val upsertStart = SystemClock.elapsedRealtime()
        val upsert = upsertCategoriesStable(s.id, type, entities, existing)
        Log.d(
            TAG,
            "refreshCategories upsert sourceId=${s.id} type=$type rows=${entities.size} " +
                "dbInserted=${upsert.stats.inserted} dbUpdated=${upsert.stats.updated} " +
                "dbSkipped=${upsert.stats.skippedUnchanged} ms=${SystemClock.elapsedRealtime() - upsertStart}",
        )
        // Everything is technically "new" on a fresh source's first sync — neither the hide-by-default
        // preference nor the sync summary is meaningful there, only on a genuine resync.
        if (!freshSource && upsert.newRows.isNotEmpty()) {
            stats.processedCounts.merge(CATEGORIES_ADDED_KEY, upsert.newRows.size, Int::plus)
            applyHideNewCategoriesDefault(s.id, type, upsert.newRows)
        }
        // C5: ids come straight from the upsert (existing rows + returned insert rowids) — the old
        // second existingCategoriesByRemoteId round-trip only re-fetched just-upserted rows.
        return CategoryRefresh(idsByRemoteId = upsert.idsByRemoteId, seenRemoteIds = uniqueCategories.mapTo(HashSet()) { it.id }).also {
            Log.d(TAG, "refreshCategories end sourceId=${s.id} type=$type mapped=${it.idsByRemoteId.size} totalMs=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private suspend fun existingCategoriesByRemoteId(sourceId: Long, type: MediaType, remoteIds: List<String>): Map<String, CategoryEntity> =
        remoteIds.distinct().chunked(QUERY_CHUNK).flatMap { categoryDao.findByRemoteIds(sourceId, type, it) }
            .mapNotNull { category -> category.remoteId?.let { it to category } }
            .toMap()

    private class CategoryUpsert(val stats: UpsertStats, val idsByRemoteId: Map<String, Long>, val newRows: List<CategoryEntity>)

    private suspend fun upsertCategoriesStable(
        sourceId: Long,
        type: MediaType,
        rows: List<CategoryEntity>,
        existingByRemoteId: Map<String, CategoryEntity>,
    ): CategoryUpsert {
        val inserts = ArrayList<CategoryEntity>()
        val updates = ArrayList<CategoryEntity>()
        var skipped = 0
        val ids = HashMap<String, Long>()
        rows.forEach { row ->
            val current = row.remoteId?.let(existingByRemoteId::get)
            when {
                current == null -> inserts.add(row)
                row != current -> { updates.add(row); ids[row.remoteId] = current.id }
                else -> { skipped++; ids[row.remoteId] = current.id }
            }
        }
        if (updates.isNotEmpty()) categoryDao.updateAll(updates)
        if (inserts.isNotEmpty()) {
            val rowIds = categoryDao.insertAll(inserts)
            val missed = ArrayList<String>()
            inserts.forEachIndexed { i, row ->
                val rid = row.remoteId ?: return@forEachIndexed
                val id = rowIds.getOrNull(i) ?: -1L
                if (id > 0) ids[rid] = id else missed.add(rid)
            }
            // IGNOREd conflicts return −1 (shouldn't happen — inserts were pre-checked by remoteId);
            // heal by re-fetching just those rows rather than everything.
            if (missed.isNotEmpty()) {
                existingCategoriesByRemoteId(sourceId, type, missed).forEach { (rid, cat) -> ids[rid] = cat.id }
            }
        }
        return CategoryUpsert(
            stats = UpsertStats(inserted = inserts.size, updated = updates.size, skippedUnchanged = skipped),
            idsByRemoteId = ids,
            newRows = inserts,
        )
    }

    /** Applies each profile's own "hide new categories" preference (same across Live/Movies/Series) to
     *  categories just discovered on a resync — a source can be shared by several profiles.
     *  Non-private: M3uSyncer discovers categories incrementally during the stream, so it applies
     *  this itself at end-of-parse instead of going through [refreshCategories]. */
    suspend fun applyHideNewCategoriesDefault(sourceId: Long, type: MediaType, newRows: List<CategoryEntity>) {
        val profileIds = sourceDao.profileIdsForSource(sourceId)
        if (profileIds.isEmpty()) return
        val keys = newRows.map { CustomizeKeys.category(it) }
        profileIds.forEach { profileId ->
            if (settings.hideNewCategoriesDefault(profileId).first()) {
                customize.setCategoriesHidden(profileId, type, keys, hidden = true)
            }
        }
    }

    /**
     * Drives a push-stream [producer] that feeds items into [add]; flushes to the DB via [insert] in
     * chunks of [BulkInsertHelper.CHUNK], reporting progress. Cancellation is checked each chunk.
     *
     * The database write runs in its own coroutine, fed batches over a channel, so downloading and
     * parsing the provider's response OVERLAPS with writing the previous batch instead of the two
     * taking turns. This used to be strictly sequential: every time the buffer filled, the parse
     * stopped dead — socket idle — until the insert, the dedupe filter and (on a fresh import) the
     * content hashing of ten thousand rows had all finished. On a 170k-title first sync that is
     * seventeen full stop-the-world flushes, and the same again in reverse while the database sits
     * waiting for the next batch to be parsed.
     *
     * A RENDEZVOUS channel, not a buffered one, deliberately. The producer refills its buffer while
     * the writer works and only the hand-off itself blocks, which is all the overlap that is wanted:
     * back-pressure survives exactly as before (a slow database still throttles the parse, so a huge
     * catalog cannot pile up in memory), and peak memory is unchanged at two chunks — the buffer
     * being refilled, plus the batch being written.
     */
    suspend fun <T, R> chunked(
        ctx: CoroutineContext,
        phase: SyncPhase,
        label: String,
        progress: SyncCounters,
        insert: suspend (List<T>) -> UpsertStats,
        total: IntArray, // shared [0] running unique count for the whole media type, so progress never resets
        seenKeys: MutableSet<String>? = null,
        uniqueKey: ((T) -> String?)? = null,
        chunkSize: Int = BulkInsertHelper.CHUNK,
        producer: suspend (add: suspend (T) -> Unit) -> R,
    ): R = coroutineScope {
        var chunkIndex = 0
        var skippedDuplicates = 0
        // Plan A. These are the numbers that diagnosed issue #192 and they are the numbers that
        // prove the fix, so they are reported at info level — the per-chunk line below is debug and
        // release builds strip it.
        var totalInserted = 0
        var totalUpdated = 0
        var totalMoved = 0
        var totalMoveRanges = 0
        var totalMoveScattered = 0
        val chunkRunStart = SystemClock.elapsedRealtime()
        val batches = Channel<List<T>>(Channel.RENDEZVOUS)
        // A single writer, so `seenKeys`, `total` and the counters stay confined to one coroutine and
        // need no synchronisation; the `writer.join()` below happens-before the caller reads them.
        val writer = launch {
            for (batch in batches) {
                ctx.ensureActive()
                chunkIndex++
                val rawCount = batch.size
                val flushStart = SystemClock.elapsedRealtime()
                val pendingKeys = ArrayList<String>()
                val rows = batch.filterNewItems(seenKeys, uniqueKey, pendingKeys)
                val filterMs = SystemClock.elapsedRealtime() - flushStart
                val skipped = rawCount - rows.size
                skippedDuplicates += skipped
                if (rows.isEmpty()) {
                    Log.d(
                        TAG,
                        "$label chunk skipped phase=${phase.name} chunk=$chunkIndex raw=$rawCount skipped=$skipped " +
                            "totalSkipped=$skippedDuplicates totalUnique=${total[0]} filterMs=$filterMs elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
                    )
                    continue
                }
                val insertStart = SystemClock.elapsedRealtime()
                val upsertStats = insert(rows)
                val insertMs = SystemClock.elapsedRealtime() - insertStart
                totalInserted += upsertStats.inserted
                totalUpdated += upsertStats.updated
                totalMoved += upsertStats.moved
                totalMoveRanges += upsertStats.movedByRange
                totalMoveScattered += upsertStats.movedScattered
                seenKeys?.addAll(pendingKeys)
                total[0] += rows.size
                if (shouldLogChunk(chunkIndex, insertMs, skipped)) {
                    Log.d(
                        TAG,
                        "$label chunk applied phase=${phase.name} chunk=$chunkIndex raw=$rawCount accepted=${rows.size} " +
                            "dbInserted=${upsertStats.inserted} dbUpdated=${upsertStats.updated} dbSkipped=${upsertStats.skippedUnchanged} " +
                            "dbMoved=${upsertStats.moved} dbMoveRanges=${upsertStats.movedByRange} " +
                            "dedupeSkipped=$skipped totalDedupeSkipped=$skippedDuplicates totalUnique=${total[0]} " +
                            "filterMs=$filterMs applyMs=$insertMs elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
                    )
                }
                progress.update(phase, total[0])
            }
        }
        val buffer = ArrayList<T>(chunkSize)
        // No try/finally around the producer: a throw here leaves the channel open, but structured
        // concurrency cancels the writer along with this scope, so nothing is left waiting on it. A
        // throw from the *writer* (a database failure) cancels the scope the other way round and the
        // producer's send unblocks — which is what keeps a broken insert surfacing as a failed phase
        // (and, for Xtream, a drop to the per-category fallback) rather than as a hang.
        val result = producer { item ->
            buffer.add(item)
            if (buffer.size >= chunkSize) {
                batches.send(ArrayList(buffer))
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) batches.send(buffer)
        batches.close()
        writer.join()
        Log.i(
            TAG,
            "$label stream done phase=${phase.name} chunks=$chunkIndex totalUnique=${total[0]} " +
                "inserted=$totalInserted updated=$totalUpdated moved=$totalMoved moveRanges=$totalMoveRanges " +
                "moveScattered=$totalMoveScattered " +
                "skippedDuplicates=$skippedDuplicates elapsedMs=${SystemClock.elapsedRealtime() - chunkRunStart}",
        )
        result
    }

    private fun shouldLogChunk(chunkIndex: Int, insertMs: Long, skipped: Int): Boolean =
        chunkIndex <= 3 || chunkIndex % 20 == 0 || insertMs >= SLOW_INSERT_LOG_MS || skipped > 0

    private fun <T> List<T>.filterNewItems(
        seenKeys: MutableSet<String>?,
        uniqueKey: ((T) -> String?)?,
        pendingKeys: MutableList<String>,
    ): List<T> {
        if (seenKeys == null || uniqueKey == null) return this
        val rows = ArrayList<T>(size)
        val batchKeys = HashSet<String>()
        forEach { item ->
            val key = uniqueKey(item)
            if (key == null) {
                rows.add(item)
            } else if (!seenKeys.contains(key) && batchKeys.add(key)) {
                pendingKeys.add(key)
                rows.add(item)
            }
        }
        return rows
    }

    companion object {
        /** Shared log tag — kept as "SyncManager" across the split so existing logcat filters still work. */
        const val TAG = "SyncManager"
        const val QUERY_CHUNK = 500


        /** Below this many stored rows a prune is always allowed (fresh/small sources). */
        private const val PRUNE_MIN_ROWS = 100

        /** A prune may remove at most this fraction of the stored rows before the guard trips. */
        private const val PRUNE_MAX_SHRINK = 0.5

        /**
         * The catalog-shrink decision on its own, free of DAOs and logging so it can be unit tested:
         * true when deleting [stale] of [stored] rows is a plausible provider update rather than a
         * truncated response.
         */
        /**
         * The hash-diff itself, free of DAOs and logging so it can be unit tested. See the instance
         * overload for why `sortOrder` is compared here rather than folded into the content hash.
         */
        suspend fun <T> upsertStable(
            rows: List<T>,
            stored: Map<String, StoredRow>,
            adapter: ContentAdapter<T>,
        ): UpsertStats {
            val inserts = ArrayList<T>()
            val updates = ArrayList<T>()
            // Stored position → the new position, for rows whose content did not change at all.
            val moves = ArrayList<Pair<Int, PositionMove>>()
            // Every moved row belongs to the same playlist — a pass only ever covers one.
            var moveSourceId = -1L
            var skipped = 0
            rows.forEach { row ->
                val existing = adapter.remoteIdOf(row)?.let { stored[it] }
                val hash = adapter.hashOf(row)
                val order = adapter.sortOrderOf(row)
                when {
                    existing == null -> inserts.add(adapter.copyWith(row, null, hash))
                    hash != existing.contentHash -> updates.add(adapter.copyWith(row, existing.id, hash))
                    // Plan A2. The content is byte-identical and only the provider's position
                    // changed, so this must not go through @Update: that writes all ~20 columns,
                    // re-runs the title parse in withProviderCatalogMetadata, and fires the FTS
                    // content-sync trigger — for a row nothing has actually happened to. 1,397 real
                    // changes used to produce 255,775 of these full-row rewrites (§2).
                    order != existing.sortOrder -> {
                        moveSourceId = adapter.sourceIdOf(row)
                        moves.add(existing.sortOrder to PositionMove(existing.id, order))
                    }
                    else -> skipped++
                }
            }

            // Plan A3. Where a whole span shifted by one delta — the usual shape, because removing
            // an item renumbers everything below it — one statement replaces one write per row.
            val occupied = HashMap<Int, Int>(stored.size)
            stored.values.forEach { occupied[it.sortOrder] = (occupied[it.sortOrder] ?: 0) + 1 }
            val (ranges, singles) = collapsePositionRuns(moves, occupied)

            // Order matters. Ranges shift by stored position, so they must run before anything
            // rewrites a row to its final position — otherwise a range would shift an already-
            // corrected row a second time. Content updates carry their own sortOrder and are
            // written last, which also makes them immune to a range that overlapped them.
            if (ranges.isNotEmpty()) adapter.moveRanges(moveSourceId, ranges)
            if (singles.isNotEmpty()) adapter.moveAll(singles)
            if (updates.isNotEmpty()) adapter.updateAll(updates)
            if (inserts.isNotEmpty()) adapter.insertAll(inserts)
            return UpsertStats(
                inserted = inserts.size,
                updated = updates.size,
                skippedUnchanged = skipped,
                moved = moves.size,
                movedByRange = ranges.size,
                movedScattered = singles.size,
            )
        }

        /**
         * Collapse position changes into range statements where it is provably safe (plan A3).
         *
         * [moves] is every row whose content is unchanged and whose position moved, as
         * (storedSortOrder → [PositionMove]). [occupied] counts how many stored rows of this source
         * sit at each stored position — *all* of them, not only the ones being moved.
         *
         * A range statement says `sortOrder = sortOrder + delta WHERE sortOrder BETWEEN lo AND hi`,
         * so it hits every stored row in that span, including ones the caller never mentioned. That
         * is the trap in this optimisation, and [occupied] is what closes it: a run is collapsed
         * only when the number of rows being moved across the span equals the number of rows that
         * actually live there. A span containing a row that is not moving — a pruned leftover, a row
         * the provider dropped — falls back to per-row moves rather than dragging it along.
         *
         * Returns the ranges plus the moves that could not be collapsed; together they are always
         * exactly equivalent to applying every move individually.
         */
        fun collapsePositionRuns(
            moves: List<Pair<Int, PositionMove>>,
            occupied: Map<Int, Int>,
        ): Pair<List<PositionRange>, List<PositionMove>> {
            if (moves.isEmpty()) return emptyList<PositionRange>() to emptyList()
            val ranges = ArrayList<PositionRange>()
            val singles = ArrayList<PositionMove>()
            // By stored position, so "contiguous" is a property of the span we are about to write.
            val sorted = moves.sortedBy { it.first }

            var i = 0
            while (i < sorted.size) {
                val (startAt, _) = sorted[i]
                val delta = sorted[i].second.sortOrder - startAt
                var j = i + 1
                // Extend while the next row sits at the very next occupied position and shifts by
                // the same amount. Duplicate stored positions are allowed: several rows may share
                // one slot, and they all move together or not at all.
                while (j < sorted.size) {
                    val (at, move) = sorted[j]
                    val prevAt = sorted[j - 1].first
                    if (move.sortOrder - at != delta) break
                    if (at != prevAt && at != prevAt + 1) break
                    j++
                }
                val run = sorted.subList(i, j)
                val lo = run.first().first
                val hi = run.last().first
                // The guard: every stored row in [lo, hi] must be one of the rows we are moving.
                val rowsInSpan = (lo..hi).sumOf { occupied[it] ?: 0 }
                // A delta of zero is not a move at all, and a single row is cheaper as one statement
                // than as a range.
                if (delta != 0 && run.size > 1 && rowsInSpan == run.size) {
                    ranges.add(PositionRange(fromSortOrder = lo, toSortOrder = hi, delta = delta))
                } else {
                    run.forEach { if (it.second.sortOrder != it.first) singles.add(it.second) }
                }
                i = j
            }
            return ranges to singles
        }

        fun shouldPrune(stored: Int, stale: Int, force: Boolean): Boolean =
            force || stored <= PRUNE_MIN_ROWS || stale <= stored * PRUNE_MAX_SHRINK
        const val CATEGORY_REQUEST_DELAY_MS = 150L // pace per-category fallback requests (avoid HTTP 429)
        private const val SLOW_INSERT_LOG_MS = 250L
        val IgnoreByteProgress: (Long, Long?) -> Unit = { _, _ -> }

        /** [SyncStatsCollector.processedCounts] keys aggregating category changes across all phases,
         *  for the sync-complete summary — not shown at all when a fresh source makes every category "new". */
        const val CATEGORIES_ADDED_KEY = "categoriesAdded"
        const val CATEGORIES_REMOVED_KEY = "categoriesRemoved"
    }
}

/**
 * Per-entity DAO/mapping lambdas so ONE [SyncSupport.upsertStable]/[SyncSupport.insertFresh]/prune
 * implementation serves channels, movies and series (C5) — any fix to the hash-diff/prune logic now
 * lands once.
 */
internal class ContentAdapter<T>(
    val remoteIdOf: (T) -> String?,
    val hashOf: (T) -> Int,
    /** Provider position, compared against the stored one outside the hash — see [SyncSupport.upsertStable]. */
    val sortOrderOf: (T) -> Int,
    /** Which playlist the row belongs to — a range shift is scoped to one source (plan A3). */
    val sourceIdOf: (T) -> Long,
    /** Copy with contentHash set; a non-null [id] rekeys the row to the existing local row. */
    val copyWith: (row: T, id: Long?, hash: Int) -> T,
    val updateAll: suspend (List<T>) -> Unit,
    val insertAll: suspend (List<T>) -> Unit,
    /** Write only `sortOrder`, for rows whose content did not change (plan A2). */
    val moveAll: suspend (List<PositionMove>) -> Unit,
    /** Shift a whole span of positions in one statement, within one source (plan A3). */
    val moveRanges: suspend (sourceId: Long, List<PositionRange>) -> Unit,
    val remoteIdsForSource: suspend (Long) -> List<String>,
    val deleteByRemoteIds: suspend (Long, List<String>) -> Unit,
    val loadHashes: suspend (Long) -> List<ContentHashProjection>,
    /** Re-sync delta check (paged catalogs): current per-category item counts; null = not supported. */
    val countsByCategory: (suspend (sourceId: Long) -> Map<Long, Int>)? = null,
    /** RemoteIds of one category's existing rows — protects a delta-skipped category from pruning. */
    val remoteIdsForCategory: (suspend (sourceId: Long, categoryId: Long) -> List<String>)? = null,
    /** RemoteIds across a set of categories — the prune scope after a per-category fallback (S2). */
    val remoteIdsInCategories: (suspend (sourceId: Long, categoryIds: List<Long>) -> List<String>)? = null,
)

/** What the DB already holds for one remote id: local row id, content hash, and provider position. */
internal data class StoredRow(val id: Long, val contentHash: Int, val sortOrder: Int)

/** One row that only changed position: write the single column, never the whole entity (plan A2). */
internal data class PositionMove(val id: Long, val sortOrder: Int)

/**
 * A whole span of positions shifted by the same amount (plan A3).
 *
 * `[fromSortOrder, toSortOrder]` are the rows' **stored** positions, and every stored row of the
 * source inside that span moves by [delta]. One statement replaces one write per row: the measured
 * Live phase of issue #192 was 30,689 rows at a uniform delta of -2, i.e. exactly one of these.
 */
internal data class PositionRange(val fromSortOrder: Int, val toSortOrder: Int, val delta: Int)

internal data class UpsertStats(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skippedUnchanged: Int = 0,
    /**
     * Rows repositioned without being rewritten. No longer a subset of [updated] — since plan A2
     * these go through `moveAll`/`moveRanges` and touch one column, so counting them as updates
     * would hide the very thing that fixed issue #192.
     */
    val moved: Int = 0,
    /** How many of [moved] were served by a range statement rather than one write each (plan A3). */
    val movedByRange: Int = 0,
    /**
     * Moves a range could not collapse, which is exactly the population plan D2's
     * `UPDATE … FROM (VALUES …)` batch exists to serve. Reported so D2 can be measured rather than
     * assumed — a re-sync where this is small does not exercise it at all.
     */
    val movedScattered: Int = 0,
)

/**
 * Result of an Xtream per-category fallback pass (S2). [succeededCategoryRemoteIds] are the
 * categories whose list was fetched *completely* — a truncated, failed, skipped or never-reached
 * category is absent, so its rows are left alone. Top-level (not nested in XtreamSyncer) so
 * [pruneScope] can be unit tested.
 */
internal class FallbackOutcome(
    val succeededCategoryRemoteIds: List<String>,
    val aborted: Boolean,
    val stoppedEarly: Boolean,
    val attempted: Int,
) {
    val complete: Boolean get() = !aborted && !stoppedEarly && succeededCategoryRemoteIds.size == attempted

    /**
     * Local category ids a prune may touch: only the fully-fetched categories, mapped through the
     * refresh's remoteId→id table. Empty means prune nothing. Rows with no category are never in
     * scope by construction — a per-category request can't return them, so a source-wide prune here
     * would delete every uncategorized item.
     */
    fun pruneScope(idsByRemoteId: Map<String, Long>): List<Long> =
        succeededCategoryRemoteIds.mapNotNull(idsByRemoteId::get).distinct()
}

internal data class CategoryRefresh(
    val idsByRemoteId: Map<String, Long>,
    val seenRemoteIds: Set<String>,
)

internal class SyncCounters(
    contentTypes: SyncContentTypes,
    private val onProgress: (ImportStage) -> Unit,
) {
    private val lock = Any()
    private val liveActive = contentTypes.live
    private val moviesActive = contentTypes.movies
    private val seriesActive = contentTypes.series
    private var liveProcessed = 0
    private var moviesProcessed = 0
    private var seriesProcessed = 0

    fun update(phase: SyncPhase, count: Int): ImportStage {
        val snapshot = synchronized(lock) {
            when (phase) {
                SyncPhase.LIVE -> liveProcessed = count
                SyncPhase.MOVIES -> moviesProcessed = count
                SyncPhase.SERIES -> seriesProcessed = count
            }
            snapshotLocked()
        }
        onProgress(snapshot)
        return snapshot
    }

    fun completeAll(): ImportStage {
        val snapshot = synchronized(lock) { snapshotLocked() }
        onProgress(snapshot)
        return snapshot
    }

    private fun snapshotLocked() = ImportStage(
        liveProcessed = liveProcessed,
        moviesProcessed = moviesProcessed,
        seriesProcessed = seriesProcessed,
        liveActive = liveActive,
        moviesActive = moviesActive,
        seriesActive = seriesActive,
    )
}

internal class SyncStatsCollector(val sourceId: Long) {
    val startedAt = System.currentTimeMillis()
    val phaseTiming = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val processedCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val phaseErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val warningFacts = java.util.concurrent.CopyOnWriteArrayList<SyncWarning>()
    @Volatile var usedFallback = false

    /**
     * Set by a user-requested "force clean sync": bypasses the catalog-shrink prune guard so a
     * genuinely shrunken provider catalog can be trimmed down. Off for every automatic sync.
     */
    @Volatile var forcePrune = false

    fun addWarning(warning: SyncWarning) { warningFacts += warning }

    fun warnings(): List<SyncWarning> = warningFacts.toList() + phaseErrors.map { (phase, message) -> SyncWarning(phase, message) }

    fun build(result: SyncResult) = SyncRunStats(
        sourceId = sourceId,
        startedAt = startedAt,
        finishedAt = System.currentTimeMillis(),
        result = result,
        phaseTiming = phaseTiming.toMap(),
        processedCounts = processedCounts.toMap(),
        phaseErrors = phaseErrors.toMap(),
        usedFallback = usedFallback,
    )
}
