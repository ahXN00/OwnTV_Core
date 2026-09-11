package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.parser.XtCategory
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StalkerCredentials
import tv.own.owntv.core.stalker.StalkerSession
import tv.own.owntv.core.stalker.stalkerCredentials
import java.io.IOException

/**
 * Stalker/Ministra portal import (plan Phase C). Unlike Xtream there is no single bulk endpoint —
 * content is inherently paged per genre — so this walks `get_genres` → per-genre `get_ordered_list`
 * pages, but reuses the shared [SyncSupport] machinery (chunked streaming insert with remoteId
 * dedupe, hash-diffed stable upsert / fresh insert, category refresh, pruning). The per-item `cmd`
 * is stored in `streamUrl` and resolved to a real URL at play time by StreamUrlResolver.
 *
 * LIVE (Phase C-1) uses a bulk `get_all_channels` fast path with per-genre paging fallback. VOD and
 * series (Phase D-1) have no *known* single-dump endpoint (there's no documented `get_all_vod`), so
 * they walk `get_categories` → per-category `get_ordered_list` pages through the shared
 * [syncPagedCatalog], which drains all pages through one global concurrency pool. If a portal turns
 * out to serve a bulk/all-category VOD dump (as `get_all_channels` surprised us for live), the
 * `page-plan`/`fetch done` logs will show it — probe `get_ordered_list&category=*` before assuming
 * paging is the only path. Series SHOW rows are stored here; episodes load lazily on open (Phase D-2).
 */
internal class StalkerSyncer(
    private val client: StalkerClient,
    private val auth: StalkerAuthManager,
    private val bulkInsertHelper: BulkInsertHelper,
    private val support: SyncSupport,
    private val sourceDao: tv.own.owntv.core.database.dao.SourceDao,
) {
    /**
     * Live runs first and alone (it has a one-shot bulk `get_all_channels` dump — seconds, not minutes).
     * Movies + series then run **concurrently**, but share ONE bounded request budget to the single
     * portal (like Xtream's 2-phase overlap) so the two phases interleave without doubling the number
     * of simultaneous requests the portal sees. Both are guarded so one section failing keeps the rest.
     */
    suspend fun sync(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector, contentTypes: SyncContentTypes) {
        val creds = credentialsFor(s)
        // Phase E (§5.5 priority 1): if the portal profile advertises an XMLTV feed, adopt it as the
        // source's guide URL so the existing (stream-parsed, rolling-window) XMLTV pipeline can sync
        // it from Settings → EPG. Best effort — a failure here must never fail the catalog sync.
        runCatching { adoptPortalXmltvUrl(s, creds) }
            .onFailure { Log.w(TAG, "portal XMLTV probe failed sourceId=${s.id}: ${it.message}") }
        // Shared adaptive budget: every phase draws from one gate, so the portal never sees more than
        // the learned limit regardless of how many are in flight. Live shares it too — it used to page
        // through a fixed six-wide window that learned nothing, which on a strict panel is precisely
        // the burst that earns a 403, and live is the phase everybody syncs.
        val budget = AdaptivePortalLimiter(isThrottle = ::isPortalThrottle)
        if (contentTypes.live) syncLive(s, progress, stats, creds, budget)
        coroutineScope {
            if (contentTypes.movies) launch { guardStep("movies", stats) { syncMovies(s, progress, stats, creds, budget) } }
            if (contentTypes.series) launch { guardStep("series", stats) { syncSeries(s, progress, stats, creds, budget) } }
        }
    }

    private suspend fun syncLive(
        s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector, creds: StalkerCredentials,
        budget: AdaptivePortalLimiter,
    ) = coroutineScope {
        val ctx = currentCoroutineContext()
        val freshSource = s.lastSyncAt == null
        val label = SyncPhase.LIVE.name
        val phaseStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        Log.i(TAG, "$label phase start sourceId=${s.id} fresh=$freshSource")
        progress.update(SyncPhase.LIVE, 0)

        val adapter = support.channelAdapter
        val hashDeferred = if (!freshSource) support.asyncHashLoad(this, label, s.id) { adapter.loadHashes(s.id) } else null

        // Genres first (one auth round trip, reused for every page below).
        val genres = auth.withAuthRetry(creds) { session ->
            client.getGenres(session.apiBase, creds.mac, session.token, creds.userAgent)
        }.filter { it.id.isNotBlank() && it.id != "*" }
        Log.i(TAG, "$label genres sourceId=${s.id} count=${genres.size}")

        val categories = support.refreshCategories(s, MediaType.LIVE, genres.map { XtCategory(it.id, it.title) }, stats)
        val catMap = categories.idsByRemoteId

        val insertFn: suspend (List<ChannelEntity>) -> UpsertStats = if (freshSource) {
            { rows -> support.insertFresh(rows, adapter) }
        } else {
            { rows -> support.upsertStable(rows, hashDeferred!!, adapter) }
        }
        val total = intArrayOf(0)
        val remoteIds = if (freshSource) null else HashSet<String>()
        // Genres/pages this pass could not fetch. A non-zero count means remoteIds is incomplete,
        // so pruning against it would delete the missing genres' channels as "stale".
        val pageFailures = java.util.concurrent.atomic.AtomicInteger(0)
        // Audit S7 diagnostic: the first few channels this pass builds, so their per-field
        // fingerprints can be compared across two consecutive syncs — see [logFieldFingerprints].
        val fieldDiffSample = ArrayList<ChannelEntity>(FIELD_DIFF_SAMPLE)
        var order = 0
        // Small flush chunk (vs Xtream's 10k fresh) so the count appears quickly instead of the UI
        // sitting on "Connecting to source…" until the first big insert — Stalker catalogs are small
        // (tens of thousands), so the extra inserts cost nothing.
        val chunkSize = STALKER_CHUNK

        bulkInsertHelper.withOptimizedBulkInsert("channels", "channels_fts", eligible = freshSource, ftsOnly = true) {
            support.chunked<ChannelEntity, Unit>(ctx, SyncPhase.LIVE, label, progress, insertFn, total, remoteIds, adapter.remoteIdOf, chunkSize) { add ->
                val emit: suspend (StalkerClient.Channel, String?) -> Unit = { ch, fallbackGenreId ->
                    val entity = ChannelEntity(
                            sourceId = s.id,
                            // A channel's own tv_genre_id wins; fall back to the genre we asked for.
                            categoryId = ch.genreId?.let { catMap[it] } ?: fallbackGenreId?.let { catMap[it] },
                            name = ch.name,
                            logoUrl = ch.logo,
                            streamUrl = ch.cmd, // portal command — resolved to a real URL at play time
                            // The portal's XMLTV id when it publishes one, else its own channel id —
                            // which is the key the portal's guide (`get_epg_info`) is written under,
                            // so a portal with no XMLTV still resolves now/next, the Guide and
                            // catch-up. A user's manual EPG match still wins over both at read time.
                            epgChannelId = ch.xmltvId ?: ch.id,
                            number = ch.number?.toIntOrNull(),
                            remoteId = ch.id,
                            sortOrder = order++,
                            catchup = ch.archive,
                            catchupDays = ch.archiveDuration,
                    )
                    if (fieldDiffSample.size < FIELD_DIFF_SAMPLE) fieldDiffSample.add(entity)
                    add(entity)
                }

                // FAST PATH: one bulk get_all_channels download (13k channels served ~14/page would
                // otherwise be ~1000 tiny requests). Buffered so it's all-or-nothing — a mid-download
                // failure emits nothing and drops to the per-genre fallback (no partial duplicates).
                val bulkStart = SystemClock.elapsedRealtime()
                val bulk = try {
                    auth.withAuthRetry(creds) { session ->
                        client.getAllChannels(session.apiBase, creds.mac, session.token, creds.userAgent)
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "$label get_all_channels failed (${e.message}) — falling back to per-genre paging")
                    null
                }
                // A non-empty bulk is not the same as a COMPLETE bulk: some portals answer
                // get_all_channels with a truncated list. Cross-check the size against the portal's
                // own declared total (the synthetic "*" genre's page 1, the same probe the VOD path
                // uses at syncPagedCatalog) and only trust the dump when it covers that total. When
                // the probe itself fails we can't judge, so the dump is accepted as before — the
                // catalog-shrink guard in SyncSupport still protects the prune.
                val declaredTotal = if (bulk != null && bulk.isNotEmpty()) {
                    try {
                        fetchPage(creds, "*", 1, budget).totalItems
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Log.w(TAG, "$label bulk verification probe failed (${e.message}) — accepting dump unverified")
                        0
                    }
                } else 0
                val bulkComplete = bulk != null && bulk.isNotEmpty() && bulk.size >= declaredTotal
                if (bulkComplete) {
                    Log.i(TAG, "$label get_all_channels ok count=${bulk.size} declaredTotal=$declaredTotal ms=${SystemClock.elapsedRealtime() - bulkStart}")
                    bulk.forEach { emit(it, null) }
                } else {
                    if (bulk != null && bulk.isNotEmpty()) {
                        Log.w(TAG, "$label get_all_channels truncated count=${bulk.size} declaredTotal=$declaredTotal — falling back to per-genre paging")
                    }
                    // FALLBACK: per-genre paged fetch (portal denied/emptied the bulk list). Pages fetched
                    // CONCURRENTLY in windows; add() runs single-threaded after each window's awaitAll.
                    Log.i(TAG, "$label per-genre fallback begin genres=${genres.size}")
                    genres.forEachIndexed { gi, genre ->
                        ctx.ensureActive()
                        // A genre that can't be fetched must not silently shrink the pass: count the
                        // failure so the prune below is skipped (mirrors the VOD path's pageFailures).
                        val first = try {
                            fetchPage(creds, genre.id, 1, budget)
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            pageFailures.incrementAndGet()
                            Log.w(TAG, "$label genre=${genre.id} page1 failed (${e.message})")
                            return@forEachIndexed
                        }
                        first.items.forEach { emit(it, genre.id) }
                        val maxPer = first.maxPageItems.takeIf { it > 0 } ?: first.items.size
                        val pages = (if (maxPer > 0) (first.totalItems + maxPer - 1) / maxPer else 1)
                            .coerceAtMost(MAX_PAGES_PER_GENRE)
                        Log.i(TAG, "$label genre ${gi + 1}/${genres.size} id=${genre.id} '${genre.title}' total=${first.totalItems} maxPer=$maxPer pages=$pages")
                        var page = 2
                        while (page <= pages) {
                            ctx.ensureActive()
                            // Spawned at the limiter's MAX; the adaptive gate inside fetchPage decides
                            // how many of them actually reach the portal at any moment.
                            val windowEnd = minOf(page + budget.max - 1, pages)
                            val window = coroutineScope {
                                (page..windowEnd).map { p ->
                                    async {
                                        try {
                                            fetchPage(creds, genre.id, p, budget)
                                        } catch (c: kotlinx.coroutines.CancellationException) {
                                            throw c
                                        } catch (e: Exception) {
                                            pageFailures.incrementAndGet()
                                            Log.w(TAG, "$label genre=${genre.id} page=$p failed (${e.message})")
                                            null
                                        }
                                    }
                                }.awaitAll()
                            }
                            window.forEach { pageResult -> pageResult?.items?.forEach { emit(it, genre.id) } }
                            page = windowEnd + 1
                        }
                    }
                }
            }
            if (pageFailures.get() > 0) {
                stats.addWarning(SyncWarning(SyncPhase.LIVE.name, kind = SyncWarningKind.PAGE_FAILURE, count = pageFailures.get()))
            }
            if (!freshSource && remoteIds != null) {
                if (pageFailures.get() == 0) {
                    support.pruneRemoteIds(label, s.id, remoteIds, stats, adapter.remoteIdsForSource, adapter.deleteByRemoteIds)
                    support.pruneCategories(s.id, MediaType.LIVE, categories.seenRemoteIds, label, stats)
                } else {
                    Log.i(TAG, "$label prune skipped sourceId=${s.id} reason=page_failures failures=${pageFailures.get()}")
                }
            }
        }

        logFieldFingerprints(label, s.id, fieldDiffSample)
        progress.update(SyncPhase.LIVE, total[0])
        stats.phaseTiming["channels"] = System.currentTimeMillis() - phaseStart
        stats.processedCounts["channels"] = total[0]
        Log.i(TAG, "$label phase end sourceId=${s.id} unique=${total[0]} ms=${SystemClock.elapsedRealtime() - elapsedStart}")
    }

    /**
     * Audit S7 diagnostic — which channel field makes Stalker rewrite ~99% of its rows every sync.
     *
     * Stalker resyncs report `dbUpdated≈1500 dbSkipped=0` with identical counts run after run, i.e.
     * the hash differs for nearly every channel even though the catalog plainly hasn't changed. The
     * audit's instruction is to identify the volatile field with a real field diff *before* changing
     * anything — excluding the wrong field from the hash would mask genuine provider updates.
     *
     * Each field is logged as a **hash, never a value**: a Stalker `cmd` can embed the portal host,
     * the MAC or a play token, and these lines get pasted into bug reports. Run a sync twice and diff
     * the two blocks — whichever `field=` number moves for the same `remoteId` is the culprit.
     */
    private fun logFieldFingerprints(label: String, sourceId: Long, sample: List<ChannelEntity>) {
        sample.forEach { c ->
            Log.i(
                TAG,
                "$label fieldDiff sourceId=$sourceId remoteId=${c.remoteId} hash=${c.computeContentHash()} " +
                    "categoryId=${c.categoryId} name=${c.name.hashCode()} logoUrl=${c.logoUrl.hashCode()} " +
                    "streamUrl=${c.streamUrl.hashCode()} epgChannelId=${c.epgChannelId.hashCode()} " +
                    "number=${c.number} catchup=${c.catchup} catchupDays=${c.catchupDays} " +
                    "catchupSource=${c.catchupSource.hashCode()} sortOrder=${c.sortOrder}",
            )
        }
    }

    private suspend fun syncMovies(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector, creds: StalkerCredentials, budget: AdaptivePortalLimiter) =
        syncPagedCatalog(
            s, progress, stats, creds, budget,
            phase = SyncPhase.MOVIES, mediaType = MediaType.MOVIE,
            table = "movies", ftsTable = "movies_fts", countsKey = "movies",
            adapter = support.movieAdapter,
            fetchCategories = { auth.withAuthRetry(creds) { client.getVodCategories(it.apiBase, creds.mac, it.token, creds.userAgent) } },
            fetchPage = { catId, page -> auth.withAuthRetry(creds) { client.getVodPage(it.apiBase, creds.mac, it.token, creds.userAgent, catId, page) } },
            map = { item, catDbId, order ->
                MovieEntity(
                    sourceId = s.id, categoryId = catDbId, name = item.name,
                    posterUrl = item.poster, rating = item.rating, plot = item.plot,
                    // Portal command — resolved to a real URL at play time (create_link, type=vod) in D-2.
                    streamUrl = item.cmd ?: "", containerExt = null, remoteId = item.id,
                    // Portal `added` date when the panel exposes one; null falls through to playlist order.
                    addedAt = item.addedAt, sortOrder = order,
                )
            },
        )

    private suspend fun syncSeries(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector, creds: StalkerCredentials, budget: AdaptivePortalLimiter) =
        syncPagedCatalog(
            s, progress, stats, creds, budget,
            phase = SyncPhase.SERIES, mediaType = MediaType.SERIES,
            table = "series", ftsTable = "series_fts", countsKey = "series",
            adapter = support.seriesAdapter,
            fetchCategories = { auth.withAuthRetry(creds) { client.getSeriesCategories(it.apiBase, creds.mac, it.token, creds.userAgent) } },
            fetchPage = { catId, page -> auth.withAuthRetry(creds) { client.getSeriesPage(it.apiBase, creds.mac, it.token, creds.userAgent, catId, page) } },
            // SHOW rows only — episodes are listed lazily when a show is opened (Phase D-2).
            map = { item, catDbId, order ->
                SeriesEntity(
                    sourceId = s.id, categoryId = catDbId, name = item.name,
                    posterUrl = item.poster, plot = item.plot, rating = item.rating,
                    year = item.year, remoteId = item.id, sortOrder = order,
                    // Portal `added` date when the panel exposes one; null falls through to playlist order.
                    addedAt = item.addedAt,
                )
            },
        )

    /**
     * Generic per-category paged importer for VOD/series (Stalker has no bulk endpoint for them):
     * `get_categories` → per-category `get_ordered_list` walked in concurrent page windows → chunked
     * fresh-insert (or hash-diffed stable upsert on re-sync) → prune. Mirrors the live per-genre
     * fallback loop but with the phase's own entity mapper and DAO adapter.
     */
    private suspend fun <T> syncPagedCatalog(
        s: SourceEntity,
        progress: SyncCounters,
        stats: SyncStatsCollector,
        creds: StalkerCredentials,
        budget: AdaptivePortalLimiter,
        phase: SyncPhase,
        mediaType: MediaType,
        table: String,
        ftsTable: String,
        countsKey: String,
        adapter: ContentAdapter<T>,
        fetchCategories: suspend (StalkerSession) -> List<StalkerClient.Genre>,
        fetchPage: suspend (catId: String, page: Int) -> StalkerClient.Page<StalkerClient.VodItem>,
        map: (item: StalkerClient.VodItem, categoryDbId: Long?, order: Int) -> T,
    ) = coroutineScope {
        val ctx = currentCoroutineContext()
        val freshSource = s.lastSyncAt == null
        val phaseStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        val label = phase.name
        Log.i(TAG, "$label phase start sourceId=${s.id} fresh=$freshSource")
        progress.update(phase, 0)

        val hashDeferred = if (!freshSource) support.asyncHashLoad(this, label, s.id) { adapter.loadHashes(s.id) } else null

        val cats = auth.withAuthRetry(creds) { fetchCategories(it) }
            .filter { it.id.isNotBlank() && it.id != "*" }
        Log.i(TAG, "$label categories sourceId=${s.id} count=${cats.size}")
        val categories = support.refreshCategories(s, mediaType, cats.map { XtCategory(it.id, it.title) }, stats)
        val catMap = categories.idsByRemoteId

        val insertFn: suspend (List<T>) -> UpsertStats = if (freshSource) {
            { rows -> support.insertFresh(rows, adapter) }
        } else {
            { rows -> support.upsertStable(rows, hashDeferred!!, adapter) }
        }
        val total = intArrayOf(0)
        val remoteIds = if (freshSource) null else HashSet<String>()
        var emitted = 0
        // Hoisted out of the fetch scope: a non-zero count means at least one category is partially
        // (or wholly) missing from this pass, so the prune below must be skipped — otherwise the
        // missing items would be deleted as "stale" (the plan's re-sync data-loss scenario).
        val pageFailures = java.util.concurrent.atomic.AtomicInteger(0)
        // Re-sync delta check: a category whose portal total_items equals its local row count very
        // likely didn't change — skip its pages entirely (the bulk of a re-sync's requests). The
        // count moves on any add/remove regardless of the portal's sort order (unlike "compare the
        // first item"). Blind spot: same-count swaps (1 added + 1 removed) keep the old rows until
        // a count change; item *detail* edits are invisible either way (hash-diff needs the row).
        val dbCategoryCounts: Map<Long, Int> =
            if (!freshSource) adapter.countsByCategory?.invoke(s.id) ?: emptyMap() else emptyMap()
        val skippedCatDbIds = ArrayList<Long>() // written by the single producer, read after its join

        bulkInsertHelper.withOptimizedBulkInsert(table, ftsTable, eligible = freshSource, ftsOnly = true) {
            // Unlike live (one bulk get_all_channels), VOD/series must be paged per category. Walking
            // categories sequentially with only page-level concurrency was the bottleneck on big VOD
            // libraries (many categories × ~14 items/page). Instead: fetch every category's page 1
            // concurrently to learn totals, then drain ALL remaining (category, page) fetches through a
            // single global pool of GLOBAL_CONCURRENCY workers. A lone single-threaded consumer calls
            // add() as items arrive over a channel (chunked's add() is NOT thread-safe).
            support.chunked<T, Unit>(ctx, phase, label, progress, insertFn, total, remoteIds, adapter.remoteIdOf, STALKER_CHUNK) { add ->
                coroutineScope {
                    // (item, its category's DB id, its provider-order sort key). The key is computed from
                    // (category index, page, index-in-page) so sortOrder is DETERMINISTIC provider order —
                    // worker-pool arrival order is racy, and since sortOrder isn't part of the content
                    // hash a scrambled first sync would be frozen forever.
                    val items = Channel<Triple<StalkerClient.VodItem, Long?, Int>>(capacity = 4096)
                    val fetchStart = SystemClock.elapsedRealtime()
                    val pagesFetched = java.util.concurrent.atomic.AtomicInteger(0)

                    val producer = launch {
                        try {
                            // Bulk fast path (mirrors live's get_all_channels): some portals return the
                            // WHOLE catalog for the synthetic "*" category in a single response. Probe it
                            // once and use it ONLY if it truly came back whole (items >= total_items);
                            // otherwise fall back to the proven per-category paging. Each item carries its
                            // own category_id, so categories still map (like live channels via tv_genre_id).
                            val bulk = try {
                                budget.withPermit { fetchPage("*", 1) }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (e: Exception) {
                                Log.w(TAG, "$label bulk probe failed (${e.message}) — using per-category paging")
                                null
                            }
                            if (bulk != null && bulk.items.isNotEmpty() && bulk.totalItems in 1..bulk.items.size) {
                                pagesFetched.incrementAndGet()
                                Log.i(TAG, "$label bulk fast path sourceId=${s.id} count=${bulk.items.size} single-dump=true")
                                bulk.items.forEachIndexed { i, item -> items.send(Triple(item, catMap[item.categoryId], i)) }
                                return@launch
                            }
                            Log.i(TAG, "$label bulk probe not a single-dump (items=${bulk?.items?.size ?: 0} total=${bulk?.totalItems ?: 0}) — per-category paging")

                            val sem = budget
                            // Page 1 of every category, concurrently (bounded) — learns per-category totals.
                            // retryTransient OUTSIDE withPermit: a backoff delay must not hold a slot, and
                            // every throttled attempt teaches the adaptive limiter.
                            val firsts = cats.map { cat ->
                                async {
                                    val page1 = try {
                                        retryTransient("$label cat=${cat.id} page=1") { sem.withPermit { fetchPage(cat.id, 1) } }
                                    } catch (c: CancellationException) {
                                        throw c
                                    } catch (e: Exception) {
                                        pageFailures.incrementAndGet()
                                        Log.w(TAG, "$label cat=${cat.id} page1 failed (${e.message})")
                                        null
                                    }
                                    Triple(cat, page1, catMap[cat.id])
                                }
                            }.awaitAll()
                            pagesFetched.addAndGet(cats.size)

                            val pageTasks = ArrayList<PageTask>()
                            var maxPerSeen = 0
                            // Categories are laid out one after another on the sort axis: each gets a
                            // contiguous [catBase, catBase + pages*maxPer) key range, in provider category
                            // order; within it, key = catBase + (page-1)*maxPer + indexInPage.
                            var catBase = 0
                            for ((cat, page1, catDbId) in firsts) {
                                if (page1 == null) continue
                                val maxPer = page1.maxPageItems.takeIf { it > 0 } ?: page1.items.size
                                if (maxPer > maxPerSeen) maxPerSeen = maxPer
                                val pages = (if (maxPer > 0) (page1.totalItems + maxPer - 1) / maxPer else 1)
                                    .coerceAtMost(MAX_PAGES_PER_GENRE)
                                // Unchanged count ⇒ keep the category's rows as-is, skip its pages.
                                // catBase still advances so the other categories' sort layout is stable.
                                if (catDbId != null && page1.totalItems > 0 && dbCategoryCounts[catDbId] == page1.totalItems) {
                                    skippedCatDbIds.add(catDbId)
                                    catBase += (pages * maxPer).coerceAtLeast(page1.items.size)
                                    continue
                                }
                                page1.items.forEachIndexed { i, item -> items.send(Triple(item, catDbId, catBase + i)) }
                                for (p in 2..pages) pageTasks.add(PageTask(cat.id, p, catDbId, catBase + (p - 1) * maxPer))
                                catBase += (pages * maxPer).coerceAtLeast(page1.items.size)
                            }
                            Log.i(TAG, "$label page-plan sourceId=${s.id} cats=${cats.size} itemsPerPage~=$maxPerSeen extraPages=${pageTasks.size} concurrency=adaptive(${budget.currentLimit}..${budget.max})")

                            // Remaining pages drained by a worker pool over a task channel. Workers are
                            // spawned at the limiter's MAX; the adaptive gate inside decides how many
                            // actually run in parallel at any moment.
                            val taskCh = Channel<PageTask>(Channel.UNLIMITED)
                            pageTasks.forEach { taskCh.trySend(it) }
                            taskCh.close()
                            val workers = (1..budget.max).map {
                                launch {
                                    for ((catId, p, catDbId, sortBase) in taskCh) {
                                        ctx.ensureActive()
                                        val pr = try {
                                            retryTransient("$label cat=$catId page=$p") { budget.withPermit { fetchPage(catId, p) } }
                                        } catch (c: CancellationException) {
                                            throw c
                                        } catch (e: Exception) {
                                            pageFailures.incrementAndGet()
                                            Log.w(TAG, "$label cat=$catId page=$p failed (${e.message})")
                                            continue
                                        }
                                        pagesFetched.incrementAndGet()
                                        pr.items.forEachIndexed { i, item -> items.send(Triple(item, catDbId, sortBase + i)) }
                                    }
                                }
                            }
                            workers.forEach { it.join() }
                        } finally {
                            items.close()
                        }
                    }

                    for ((item, catDbId, sortKey) in items) { add(map(item, catDbId, sortKey)); emitted++ }
                    producer.join()
                    Log.i(TAG, "$label fetch done sourceId=${s.id} pages=${pagesFetched.get()} failures=${pageFailures.get()} emitted=$emitted fetchMs=${SystemClock.elapsedRealtime() - fetchStart}")
                }
            }
            // A degraded pass must not report a clean success — surface it as a sync warning.
            if (pageFailures.get() > 0) {
                stats.addWarning(SyncWarning(phase.name, kind = SyncWarningKind.PAGE_FAILURE, count = pageFailures.get()))
            }
            if (!freshSource && remoteIds != null) {
                // Delta-skipped categories were never re-fetched, so their items aren't in this
                // pass's remoteIds — add their EXISTING rows or the prune would delete them all.
                if (skippedCatDbIds.isNotEmpty()) {
                    adapter.remoteIdsForCategory?.let { fetch ->
                        skippedCatDbIds.forEach { remoteIds.addAll(fetch(s.id, it)) }
                    }
                    Log.i(TAG, "$label delta-skip sourceId=${s.id} unchangedCategories=${skippedCatDbIds.size}")
                }
                if (pageFailures.get() == 0) {
                    support.pruneRemoteIds(label, s.id, remoteIds, stats, adapter.remoteIdsForSource, adapter.deleteByRemoteIds)
                    support.pruneCategories(s.id, mediaType, categories.seenRemoteIds, label, stats)
                } else {
                    // Failed pages mean this pass's remoteIds set is incomplete; pruning against it would
                    // delete every item of the missing pages/categories as "stale" (mirrors Xtream's
                    // prune-skipped-on-incomplete-bulk guard).
                    Log.i(TAG, "$label prune skipped sourceId=${s.id} reason=page_failures failures=${pageFailures.get()}")
                }
            }
        }

        progress.update(phase, total[0])
        stats.processedCounts[countsKey] = total[0]
        Log.i(TAG, "$label phase end sourceId=${s.id} unique=${total[0]} ms=${SystemClock.elapsedRealtime() - elapsedStart}")
    }

    /** One remaining page fetch for the worker pool: which category/page, and the page's first sort key. */
    private data class PageTask(val catId: String, val page: Int, val catDbId: Long?, val sortBase: Int)

    private suspend inline fun guardStep(phase: String, stats: SyncStatsCollector, block: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "$phase import failed — keeping the rest of the import", e)
            stats.phaseErrors[phase] = e.message ?: "unknown"
        } finally {
            stats.phaseTiming[phase] = System.currentTimeMillis() - start
        }
    }

    /**
     * Retry transient portal errors (429/5xx **and broken connections**) with a short backoff. The
     * portal occasionally 503s under concurrent paging; without a retry a failed page 1 silently
     * drops its WHOLE category, and on the next re-sync those items are pruned as stale — real data
     * loss, not just a gap.
     *
     * An overloaded portal does not always answer 503: just as often it resets the connection or
     * closes it mid-response, which surfaces as a socket-level [IOException] rather than a status
     * code. That is the same condition with the same cost, so it retries the same way.
     */
    private suspend fun <T> retryTransient(what: String, block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (!isTransientPortalError(e) || attempt >= PAGE_ATTEMPTS) throw e
                val reason = (e as? StalkerClient.StalkerHttpException)?.let { "HTTP ${it.code}" }
                    ?: "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "$what transient $reason — retrying (attempt $attempt/${PAGE_ATTEMPTS - 1})")
                delay(PAGE_RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    /**
     * One page fetch with the shared auth (re-handshakes once on token expiry), taking a slot from the
     * shared [budget]. `retryTransient` stays OUTSIDE `withPermit` so a backoff delay never holds a
     * slot and every throttled attempt teaches the limiter — the same shape movies and series use.
     */
    private suspend fun fetchPage(
        creds: StalkerCredentials, genreId: String, page: Int, budget: AdaptivePortalLimiter,
    ): StalkerClient.Page<StalkerClient.Channel> =
        retryTransient("${SyncPhase.LIVE.name} genre=$genreId page=$page") {
            budget.withPermit {
                auth.withAuthRetry(creds) { session ->
                    client.getLiveChannelsPage(session.apiBase, creds.mac, session.token, creds.userAgent, genreId, page)
                }
            }
        }

    private fun credentialsFor(s: SourceEntity): StalkerCredentials {
        val mac = s.mac?.let { StalkerClient.canonicalizeMac(it) }
            ?: throw IOException("Stalker source ${s.id} has no valid MAC address")
        return s.stalkerCredentials(mac)
    }

    /**
     * Phase E (§5.5): some portals advertise an XMLTV feed in their `get_profile` payload. If this
     * source has no guide URL yet, persist the advertised one into `epgUrl` — `EpgRepository.guideUrl`
     * then treats the source exactly like an M3U with `url-tvg`. A user-entered URL always wins
     * (never overwritten), and EPG sync itself stays user-initiated (Settings → EPG).
     */
    private suspend fun adoptPortalXmltvUrl(s: SourceEntity, creds: StalkerCredentials) {
        if (!s.epgUrl.isNullOrBlank()) return
        val profile = auth.sessionFor(creds).profile
        val url = XMLTV_PROFILE_KEYS.firstNotNullOfOrNull { key ->
            profile[key]?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        } ?: return
        Log.i(TAG, "adopting portal-advertised XMLTV url for sourceId=${s.id}")
        sourceDao.update(s.copy(epgUrl = url))
    }

    companion object {
        private const val TAG = SyncSupport.TAG

        /**
         * Portal answers worth one more attempt after a backoff — "ask again in a moment", not
         * "the answer is no". 429 and 5xx are the obvious ones; **403 belongs here too**, because
         * Ministra and its reseller panels use it for "this MAC has too many connections open"
         * rather than for a refused login (see `StalkerClient.httpFailure`). Retrying it after a
         * short wait is what a set-top box does, and it is what makes a portal that refuses a burst
         * of pages recover instead of dropping a whole category.
         *
         * Connection-level faults are handled by [isTransientNetworkError]; a
         * [StalkerClient.StalkerAuthException] is not retried here at all, having already been
         * re-handshaked one level down by [StalkerAuthManager].
         */
        internal fun isTransientPortalError(e: IOException): Boolean = when (e) {
            is StalkerClient.StalkerHttpException -> e.code == 403 || e.code == 429 || e.code in 500..599
            else -> isTransientNetworkError(e)
        }

        /**
         * Connection-level faults worth a second attempt — "the link broke", not "the portal said no".
         *
         * Deliberately narrow rather than a blanket `IOException`: a [StalkerClient.StalkerAuthException]
         * has already been re-handshaked one level down by [StalkerAuthManager], and a malformed payload
         * is deterministic, so repeating either only costs the portal two more pointless requests.
         */
        internal fun isTransientNetworkError(e: IOException): Boolean = when {
            e is StalkerClient.StalkerAuthException -> false
            e is java.net.SocketException -> true          // "Connection reset", "Broken pipe"
            e is java.io.InterruptedIOException -> true    // includes SocketTimeoutException
            e is javax.net.ssl.SSLException -> true
            e is java.net.UnknownHostException -> true     // DNS blip part-way through a long sync
            // A connection closed mid-response reaches us as a plain IOException ("unexpected end of
            // stream on …"), so the type alone cannot identify it — the wrapped EOFException can.
            else -> generateSequence(e as Throwable) { it.cause }.take(CAUSE_CHAIN_LIMIT)
                .any { it is java.io.EOFException }
        }

        /**
         * Throttle signals that shrink the adaptive budget: rate-limit/overload HTTP codes + timeouts.
         * **403 counts**, for the same reason it is retried — a panel refusing a burst is telling us
         * our concurrency is too high, and halving it is the only reply that helps.
         */
        internal fun isPortalThrottle(e: Throwable): Boolean = when (e) {
            is StalkerClient.StalkerHttpException -> e.code == 403 || e.code == 429 || e.code in 500..599
            is java.net.SocketTimeoutException -> true
            else -> false
        }

        /** `get_profile` fields observed in the wild to carry a portal's XMLTV feed URL (§5.5). */
        private val XMLTV_PROFILE_KEYS = listOf("xmltv_url", "epg_url", "tv_guide_url", "guide_url")

        /** Flush every N channels so import progress shows early (see [chunkSize] note). */
        private const val STALKER_CHUNK = 1_500

        /** How many channels the S7 field-diff diagnostic fingerprints per sync (see [logFieldFingerprints]). */
        private const val FIELD_DIFF_SAMPLE = 3

        /** Total attempts per page for transient 429/5xx errors (1 original + 2 retries). */
        private const val PAGE_ATTEMPTS = 3

        /** Backoff base between page retries (×attempt: 750ms, then 1.5s). */
        private const val PAGE_RETRY_DELAY_MS = 750L

        /** Guard against a self-referential `cause` chain while classifying a network failure. */
        private const val CAUSE_CHAIN_LIMIT = 5

        /** Safety cap: ignore an absurd total_items from a portal that mis-reports or ignores `p=`. */
        private const val MAX_PAGES_PER_GENRE = 5_000
    }
}
