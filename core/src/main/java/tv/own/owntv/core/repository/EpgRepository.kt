package tv.own.owntv.core.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XmltvParser
import tv.own.owntv.core.parser.XtreamClient

/**
 * Fetches and stores the bulk XMLTV guide for a source (Xtream `xmltv.php`, or a M3U playlist's
 * `url-tvg`). Only programmes overlapping a rolling window are kept, and finished rows are pruned, so
 * the guide stays bounded. The grid reads from [EpgDao]; per-channel now/next still uses Xtream's
 * short-EPG separately.
 */
class EpgRepository(
    private val epgDao: EpgDao,
    private val http: HttpClient,
    private val xtream: XtreamClient,
    private val channelDao: ChannelDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
    private val context: android.content.Context,
    private val db: tv.own.owntv.core.database.OwnTVDatabase,
    private val bulkInsertHelper: BulkInsertHelper,
    private val sourceDao: tv.own.owntv.core.database.dao.SourceDao,
    private val stalkerEpg: tv.own.owntv.core.stalker.StalkerEpgLoader,
) {

    /** Where a source's downloaded XMLTV is cached, so a later smart-match can top up programmes from it
     *  without re-hitting the network (see [storeProgrammesForIdsFromCache]). */
    private fun cacheFile(storeId: Long) = java.io.File(context.cacheDir, "epg_$storeId$EPG_CACHE_SUFFIX")

    /**
     * Where the download is tee'd *while it runs*. It only becomes [cacheFile] once the parse has
     * completed cleanly, so a guide truncated by a network drop is never left behind under the name
     * readers trust (E1). The `.tmp` suffix also keeps it out of [storeProgrammesForIdsFromCache]'s
     * `endsWith(".xmltv")` scan while it's being written.
     */
    private fun tempCacheFile(storeId: Long) = java.io.File(context.cacheDir, "epg_$storeId$EPG_CACHE_TEMP_SUFFIX")

    /**
     * Drop temp caches abandoned by a killed process. Only files older than the cache TTL are touched:
     * a *fresh* `.tmp` may belong to another source syncing right now, and deleting it would silently
     * cost that sync its cache.
     */
    private fun sweepOrphanedTempCaches(now: Long) {
        runCatching {
            context.cacheDir.listFiles()
                ?.forEach { if (isOrphanedEpgTempCache(it.name, it.lastModified(), now, CACHE_TTL_MS)) it.delete() }
        }
    }

    /**
     * Ensure every non-unique `epg_programmes` index exists — the Guide read-index
     * `(sourceId, epgChannelId)` turns the "which channels have guide data" lookup from a
     * multi-second full scan into a fast index scan; the others are Room-declared and get dropped
     * by an eligible fresh EPG bulk import, so this pass must recreate ALL of them (the canonical
     * list is shared with BulkInsertHelper's restore and the migration heal — a missing index would
     * crash the next migration's schema validation). Indexes are **permanent** and
     * **auto-maintained** by SQLite, so this builds them exactly once (in the background) and is an
     * instant no-op every call after. Run at startup (for users who already have EPG) and after
     * each EPG sync (for brand-new EPG).
     */
    suspend fun ensureEpgIndexes() = withContext(Dispatchers.IO) {
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val w = db.openHelper.writableDatabase
            tv.own.owntv.core.database.OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES
                .getValue("epg_programmes")
                .forEach { w.execSQL(it) }
            Log.d("EpgRepository", "ensureEpgIndexes ms=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("EpgRepository", "Unable to create EPG guide index", e)
        }
    }

    suspend fun countProgrammes(storeId: Long): Int = withContext(Dispatchers.IO) {
        epgDao.countForSources(listOf(storeId))
    }

    /**
     * The set of normalised EPG ids the user's channels actually reference: every channel's tvg-id plus
     * any persisted smart-match overrides. Public XMLTV feeds carry thousands of channels the user doesn't
     * have, so the bulk sync filters programmes to this set instead of storing the whole feed. Returns null
     * when we can't determine any ids (no channels / no tvg-ids) — caller then stores everything (never
     * filter the guide down to empty).
     */
    private suspend fun neededEpgIds(): Set<String>? {
        val ids = HashSet<String>()
        try {
            channelDao.allEpgChannelIds().forEach { ids.add(it) } // already lower+trim from SQL
        } catch (c: CancellationException) {
            throw c
        } catch (e: NoProgrammesInWindowException) {
            throw e
        } catch (e: Exception) {
            Log.w("EpgRepository", "Unable to read channel EPG ids — using unfiltered guide sync", e)
        }
        try {
            val pid = settings.activeProfileId.first()
            if (pid >= 0) {
                customize.observe(pid, MediaType.LIVE).first().epgMatches.values
                    .forEach { ids.add(it.trim().lowercase()) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("EpgRepository", "Unable to read custom EPG matches — using unfiltered guide sync", e)
        }
        return ids.ifEmpty { null }
    }
    /** The guide URL for a source, or null if it has no EPG feed. A manual EPG URL always wins. */
    fun guideUrl(source: SourceEntity): String? = when (source.type) {
        SourceType.XTREAM -> source.epgUrl?.takeIf { it.isNotBlank() } ?: xtream.xmltvUrl(source)
        SourceType.M3U -> source.epgUrl?.takeIf { it.isNotBlank() }
        SourceType.LOCAL_BACKUP -> null
        // Stalker: user-pasted XMLTV, or the portal-advertised one adopted into epgUrl at sync
        // (StalkerSyncer.adoptPortalXmltvUrl, Phase E §5.5) — and failing both, the portal's OWN
        // guide, addressed by the marker URL below — unless the user has switched that off for this
        // playlist. A portal that offers no XMLTV is the common case, and it used to mean no guide and
        // no catch-up at all.
        SourceType.STALKER -> source.epgUrl?.takeIf { it.isNotBlank() }
            ?: stalkerGuideUrl(source.id).takeIf { source.importPortalEpg }
    }

    /**
     * Every guide feed a source offers. An M3U header may advertise several XMLTV feeds in one
     * comma-separated `url-tvg` (a provider covering two countries, say), and the whole string used to
     * be stored — and requested — as one address, which can only 404. Splitting here rather than at
     * import means playlists that already stored a joined value are fixed without a migration.
     */
    fun guideUrls(source: SourceEntity): List<String> = splitGuideUrls(guideUrl(source))

    fun hasGuide(source: SourceEntity): Boolean = guideUrl(source) != null

    /**
     * Refresh one playlist source's guide (used by the one-time migration). Returns programmes written.
     * Only the first feed of a comma-separated header is synced *here*: everything stored under one id
     * is one feed's worth of guide, and a second download would prune the first one's programmes as
     * stale. The further feeds are registered as EPG sources of their own, each with its own id, and
     * refreshed through [refreshUrl] like any other feed.
     */
    suspend fun refresh(source: SourceEntity, onProgress: (channels: Int, programmes: Int) -> Unit = { _, _ -> }): Int {
        val url = guideUrls(source).firstOrNull() ?: return 0
        return refreshUrl(source.id, url, source.userAgent, onProgress)
    }

    /**
     * Download [url] (XMLTV, gzip-aware) into the guide tables keyed by [storeId], keeping only the
     * rolling window. Used for standalone EPG sources (negative ids). Throws on network/parse failure.
     */
    suspend fun refreshUrl(
        storeId: Long,
        url: String,
        userAgent: String?,
        onProgress: (channels: Int, programmes: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        // A portal guide is not a download, so it cannot go through the XMLTV path below — but it is
        // stored, pruned, matched and read exactly like one, which is why it is addressed as a URL
        // and lives in the same EPG-source list with the same "sync now" button and error line.
        stalkerSourceIdOf(url)?.let { portalSourceId ->
            return@withContext refreshFromPortal(storeId, portalSourceId, onProgress)
        }
        val now = System.currentTimeMillis()
        val startedAt = SystemClock.elapsedRealtime()
        val from = now - WINDOW_BACK_MS
        val to = now + WINDOW_AHEAD_MS
        val globallyEmpty = bulkInsertHelper.tableIsEmpty("epg_programmes")
        val sourceEmpty = epgDao.countForSources(listOf(storeId)) == 0
        val freshSource = sourceEmpty

        // Filter programmes to the channels the user actually has (tvg-ids + persisted matches). Public
        // feeds carry far more channels than the user owns, so this is the single biggest sync speedup —
        // ~10–20× fewer rows. We still store ALL <channel> entries (cheap, needed as smart-match candidates).
        // Null = couldn't determine any ids → don't filter (never reduce the guide to empty).
        val needed = neededEpgIds()
        Log.d("EpgRepository", "EPG channel filter sourceId=$storeId ids=${needed?.size ?: 0} active=${needed != null}")
        val chunkSize = if (freshSource) BulkInsertHelper.CHUNK_FRESH else BulkInsertHelper.CHUNK
        val channels = LinkedHashMap<String, EpgChannelEntity>()
        val channelsWithProgrammes = HashSet<String>()
        val buffer = ArrayList<EpgProgrammeEntity>(chunkSize)
        // Per-channel, lazily-loaded and capped hash tracker: huge guides (millions of programme rows)
        // must never be mirrored into one giant in-memory map on a low-RAM TV box. Channels beyond the
        // cap degrade to write-through (REPLACE on the natural-key unique index keeps that correct).
        val hashTracker = ProgrammeHashTracker(epgDao, storeId, freshSource, MAX_TRACKED_PROGRAMMES)
        var processedCount = 0
        var writtenCount = 0
        var inserted = 0
        var updated = 0
        var skipped = 0
        var parseCompleted = false
        var removedProgrammes = 0
        var removedChannels = 0

        // Stream the feed: parse it as it downloads (no waiting for the whole file) while ALSO teeing the raw
        // bytes into a local cache file. The retained file lets a later smart-match top up just that channel's
        // programmes without another network round-trip — without slowing the live sync. The first
        // replacement batch is committed atomically so readers keep seeing the old guide until the new rows
        // are ready.
        // …tee'd to a temp name and promoted only on a clean parse: a truncated file would otherwise be
        // indistinguishable from a complete one for a full day, and a smart-match reading it would
        // conclude the missing channels simply have no schedule.
        val file = cacheFile(storeId)
        val tmpFile = tempCacheFile(storeId)
        sweepOrphanedTempCaches(now)
        suspend fun storeProgrammes(batch: List<EpgProgrammeEntity>) {
            if (batch.isEmpty()) return
            val startedAt = SystemClock.elapsedRealtime()
            db.withTransaction {
                epgDao.upsertProgrammes(batch)
            }
            Log.d(
                "EpgRepository",
                "EPG batch store sourceId=$storeId rows=${batch.size} writeMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
        try {
            bulkInsertHelper.withOptimizedBulkInsert(
                "epg_programmes",
                ftsTable = null,
                eligible = globallyEmpty,
                ftsOnly = false,
            ) {
                tmpFile.outputStream().use { out ->
                    http.get(url, userAgent, maxAttempts = EPG_DOWNLOAD_ATTEMPTS) { input ->
                        XmltvParser.parse(
                            TeeInputStream(input, out),
                            onChannel = { id, name, icon ->
                                // Ids are stored normalized (trim+lowercase) so guide lookups can use the
                                // (epgChannelId, startMs) index directly — XMLTV ids often differ from the
                                // panel's epg_channel_id only in case.
                                val key = id.trim().lowercase()
                                channels.getOrPut(key) {
                                    EpgChannelEntity(sourceId = storeId, epgChannelId = key, displayName = name, iconUrl = icon)
                                }
                            },
                            onProgramme = { channelId, startMs, stopMs, title, desc ->
                                val key = channelId.trim().lowercase()
                                if (stopMs > from && startMs < to && (needed == null || key in needed)) {
                                    channelsWithProgrammes.add(key)
                                    val programme = EpgProgrammeEntity(
                                        sourceId = storeId,
                                        epgChannelId = key,
                                        startMs = startMs,
                                        stopMs = stopMs,
                                        title = title,
                                        description = desc,
                                    )
                                    val hash = programme.computeContentHash()
                                    when (val decision = hashTracker.observe(key, startMs, hash)) {
                                        is ProgrammeDecision.New -> {
                                            buffer.add(programme.copy(contentHash = hash))
                                            inserted++
                                        }
                                        is ProgrammeDecision.Changed -> {
                                            buffer.add(programme.copy(id = decision.id, contentHash = hash))
                                            updated++
                                        }
                                        ProgrammeDecision.Unchanged -> skipped++
                                        // Over the memory cap (or duplicate-in-feed fallback): write through —
                                        // the unique (sourceId, epgChannelId, startMs) index + REPLACE dedupe.
                                        ProgrammeDecision.WriteThrough -> {
                                            buffer.add(programme.copy(contentHash = hash))
                                            inserted++
                                        }
                                    }
                                    processedCount++
                                    if (buffer.size >= chunkSize) {
                                        val batchSize = buffer.size
                                        storeProgrammes(buffer.toList())
                                        writtenCount += batchSize
                                        buffer.clear()
                                        onProgress(channelsWithProgrammes.size, processedCount)
                                    } else if (processedCount == 1 || processedCount % PROGRESS_PROGRAMME_STEP == 0) {
                                        onProgress(channelsWithProgrammes.size, processedCount)
                                    }
                                }
                            },
                            channelFilter = needed,
                        )
                    }
                }
                if (buffer.isNotEmpty()) {
                    val batchSize = buffer.size
                    storeProgrammes(buffer.toList())
                    writtenCount += batchSize
                    buffer.clear()
                }
                if (channels.isNotEmpty()) {
                    val channelsStartedAt = SystemClock.elapsedRealtime()
                    epgDao.upsertChannels(channels.values.toList())
                    Log.d(
                        "EpgRepository",
                        "EPG channels store sourceId=$storeId rows=${channels.size} ms=${SystemClock.elapsedRealtime() - channelsStartedAt}",
                    )
                }
                parseCompleted = true
            }
            if (parseCompleted && channels.isNotEmpty() && processedCount == 0) {
                throw NoProgrammesInWindowException()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: NoProgrammesInWindowException) {
            throw e
        } catch (e: Exception) {
            // Network drop or an unrecoverable parse position. Keep whatever we already pulled — a partial
            // guide beats none — and only fail outright if we got nothing at all.
            if (writtenCount == 0 && buffer.isEmpty() && channels.isEmpty()) throw e
            if (buffer.isNotEmpty()) {
                val batchSize = buffer.size
                storeProgrammes(buffer.toList())
                writtenCount += batchSize
                buffer.clear()
            }
            if (channels.isNotEmpty()) {
                val channelsStartedAt = SystemClock.elapsedRealtime()
                epgDao.upsertChannels(channels.values.toList())
                Log.d(
                    "EpgRepository",
                    "EPG channels store sourceId=$storeId rows=${channels.size} ms=${SystemClock.elapsedRealtime() - channelsStartedAt}",
                )
            }
            Log.w("EpgRepository", "EPG sync incomplete — keeping partial (written=$writtenCount accepted=$processedCount inserted=$inserted updated=$updated skipped=$skipped)", e)
        } finally {
            // The rows already written stay (a partial guide still beats none) — only the *cache file*
            // is held to a completeness bar, because unlike the rows it gets re-read later as if it were
            // the whole feed. Anything short of a clean parse leaves the previous good cache in place.
            if (parseCompleted && tmpFile.length() > 0) {
                if (!(file.delete() || !file.exists()) || !tmpFile.renameTo(file)) {
                    Log.w("EpgRepository", "EPG cache promote failed storeId=$storeId — cache skipped this run")
                }
            }
            if (tmpFile.exists()) tmpFile.delete() // no-op after a successful rename
        }
        if (parseCompleted) {
            removedProgrammes = pruneRemovedProgrammes(storeId, hashTracker)
            removedChannels = pruneRemovedChannels(storeId, channels.keys)
        }
        val pruneStartedAt = SystemClock.elapsedRealtime()
        epgDao.prune(now - WINDOW_BACK_MS)
        Log.d("EpgRepository", "EPG prune sourceId=$storeId ms=${SystemClock.elapsedRealtime() - pruneStartedAt}")
        ensureEpgIndexes() // new/refreshed EPG → make sure the Guide read-index exists (no-op if already there)
        val analyzeStartedAt = SystemClock.elapsedRealtime()
        runCatching { bulkInsertHelper.analyzeTables("epg_programmes", "epg_channels") }
            .onSuccess {
                Log.d("EpgRepository", "EPG analyze sourceId=$storeId ms=${SystemClock.elapsedRealtime() - analyzeStartedAt}")
            }
            .onFailure { Log.w("EpgRepository", "Unable to analyze EPG tables", it) }
        onProgress(channelsWithProgrammes.size, processedCount)
        Log.i(
            "EpgRepository",
            "EPG incremental sync sourceId=$storeId inserted=$inserted updated=$updated skipped=$skipped removedProgrammes=$removedProgrammes removedChannels=$removedChannels " +
                "accepted=$processedCount written=$writtenCount ms=${SystemClock.elapsedRealtime() - startedAt}",
        )
        writtenCount
    }

    /**
     * Crawl a Stalker portal's own guide into the tables keyed by [storeId] (Phase E, replacing
     * "bulk get_epg_info is never used"). Written in batches as it parses, so the payload is never
     * held; rows are keyed by the portal's channel id, which is what `StalkerSyncer` gives a portal
     * channel that has no XMLTV id of its own.
     *
     * A failed crawl throws with the guide left as it was. A crawl that returns nothing throws
     * [NoProgrammesInWindowException], the same answer the XMLTV path gives for a feed that carries
     * no programmes we can use — the apps already explain that one.
     */
    private suspend fun refreshFromPortal(
        storeId: Long,
        portalSourceId: Long,
        onProgress: (channels: Int, programmes: Int) -> Unit,
    ): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val source = sourceDao.getById(portalSourceId)
            ?: throw java.io.IOException("Portal guide source $portalSourceId no longer exists")
        val now = System.currentTimeMillis()
        val from = now - WINDOW_BACK_MS
        val to = now + WINDOW_AHEAD_MS
        var written = 0
        val channels = LinkedHashSet<String>()
        // The portal keys its guide by its OWN channel id, but a channel that came with an `xmltv_id`
        // is stored under that instead — so the two disagree on exactly the channels most likely to
        // have a guide. Translate every row into the key its channel is actually stored under; a
        // portal id with no channel of its own keeps the portal id, which is what the fallback in
        // StalkerSyncer gives those channels anyway.
        val guideKeys = channelDao.guideKeysForSource(portalSourceId)
            .associate { it.remoteId.trim().lowercase() to it.epgChannelId.trim().lowercase() }
        val channelIds = channelDao.remoteIdsForSource(portalSourceId)
        val outcome = stalkerEpg.crawl(
            source = source,
            from = from,
            to = to,
            cacheDir = context.cacheDir,
            channelIds = channelIds,
            // Straight through to the EPG screen, so a portal guide counts up like any other feed
            // instead of sitting on "Connecting…" until it finishes.
            onProgress = { seenChannels, programmes -> onProgress(seenChannels, programmes) },
        ) { batch ->
            db.withTransaction {
                epgDao.upsertProgrammes(
                    batch.map {
                        EpgProgrammeEntity(
                            sourceId = storeId,
                            epgChannelId = guideKeys[it.epgChannelId] ?: it.epgChannelId,
                            startMs = it.startMs,
                            stopMs = it.stopMs,
                            title = it.title,
                            description = it.description,
                        )
                    },
                )
            }
            written += batch.size
            batch.forEach { channels.add(guideKeys[it.epgChannelId] ?: it.epgChannelId) }
        }
        if (outcome.programmes == 0) throw NoProgrammesInWindowException()
        // A guide channel row per channel we saw, so the EPG matcher and the "guide channels" counts
        // treat a portal guide like any other. The portal's EPG payload carries no display name, so
        // the id doubles as the label — the matcher keys on the id regardless.
        epgDao.upsertChannels(channels.map { EpgChannelEntity(sourceId = storeId, epgChannelId = it, displayName = it, iconUrl = null) })
        // Programmes this crawl did not replace and the window no longer covers. The portal is the
        // whole truth for this store, so anything outside the window it just served is stale.
        epgDao.pruneOutsideWindow(storeId, from, to)
        epgDao.prune(now - WINDOW_BACK_MS)
        ensureEpgIndexes()
        runCatching { bulkInsertHelper.analyzeTables("epg_programmes", "epg_channels") }
            .onFailure { Log.w("EpgRepository", "Unable to analyze EPG tables", it) }
        onProgress(channels.size, written)
        Log.i(
            "EpgRepository",
            "EPG portal sync storeId=$storeId portalSourceId=$portalSourceId method=${outcome.method} " +
                "period=${outcome.periodDays} channels=${channels.size} written=$written " +
                "ms=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return written
    }

    class NoProgrammesInWindowException : java.io.IOException()

    private suspend fun pruneRemovedProgrammes(sourceId: Long, tracker: ProgrammeHashTracker): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val staleIds = tracker.staleTrackedIds()
        staleIds.chunked(QUERY_CHUNK).forEach { epgDao.deleteProgrammesByIds(it) }
        Log.d("EpgRepository", "EPG programme prune sourceId=$sourceId stale=${staleIds.size} overflowed=${tracker.overflowed} ms=${SystemClock.elapsedRealtime() - startedAt}")
        return staleIds.size
    }

    private suspend fun pruneRemovedChannels(sourceId: Long, seenChannelIds: Set<String>): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val existingChannelIds = epgDao.epgChannelIdsForSource(sourceId)
        val staleIds = if (seenChannelIds.isEmpty()) {
            if (existingChannelIds.isNotEmpty()) {
                epgDao.clearChannelsForSource(sourceId)
                epgDao.clearSource(sourceId)
            }
            existingChannelIds
        } else {
            existingChannelIds.filterNot(seenChannelIds::contains).also { stale ->
                stale.chunked(QUERY_CHUNK).forEach {
                    epgDao.deleteChannelsByEpgIds(sourceId, it)
                    // The per-channel hash tracker never loads channels absent from the feed, so
                    // their leftover programmes must be dropped here rather than by the id prune.
                    epgDao.deleteProgrammesForChannels(sourceId, it)
                }
            }
        }
        Log.d("EpgRepository", "EPG channel prune sourceId=$sourceId stale=${staleIds.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
        return staleIds.size
    }

    /**
     * Top up programmes for newly-matched [epgIds] from the **cached** XMLTV file(s) — no network. Used right
     * after a smart-match so the channels' guide fills in instantly (the bulk sync filters to the channels you
     * have, so a freshly-matched id wasn't stored). Parses each cache file ONCE for the whole id set. Returns
     * false if there's no fresh (≤24h) cache to read — the caller then does a full network re-sync.
     */
    suspend fun storeProgrammesForIdsFromCache(epgIds: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val keys = epgIds.map { it.trim().lowercase() }.filterTo(HashSet()) { it.isNotBlank() }
        if (keys.isEmpty()) return@withContext true
        val now = System.currentTimeMillis()
        val from = now - WINDOW_BACK_MS; val to = now + WINDOW_AHEAD_MS
        // Only promoted (complete) caches are eligible — an in-flight or abandoned `.xmltv.tmp` is
        // excluded by name, so a truncated download can't be mistaken for the whole feed (E1).
        val files = context.cacheDir.listFiles()
            ?.filter { isUsableEpgCache(it.name, it.length(), it.lastModified(), now, CACHE_TTL_MS) }
            ?: emptyList<java.io.File>()
        if (files.isEmpty()) return@withContext false // no fresh cache → caller falls back to a network re-sync
        Log.d("EpgRepository", "Cached EPG channel filter ids=${keys.size} files=${files.size}")

        val storeFiles = files.mapNotNull { f ->
            epgCacheStoreId(f.name)?.let { it to f }
        }
        val storeIds = storeFiles.map { it.first }
        val collected = HashMap<String, MutableList<EpgProgrammeEntity>>()
        for ((storeId, file) in storeFiles) {
            var foundHere = 0
            runCatching {
                file.inputStream().use { input ->
                    XmltvParser.parse(
                        input,
                        onChannel = { _, _, _ -> },
                        onProgramme = { channelId, startMs, stopMs, title, desc ->
                            val key = channelId.trim().lowercase()
                            if (key in keys && stopMs > from && startMs < to) {
                                collected.getOrPut(key) { ArrayList() }
                                    .add(EpgProgrammeEntity(sourceId = storeId, epgChannelId = key, startMs = startMs, stopMs = stopMs, title = title, description = desc))
                                foundHere++
                            }
                        },
                        channelFilter = keys,
                    )
                }
            }.onFailure { Log.w("EpgRepository", "cacheFill parse failed storeId=$storeId (other sources unaffected)", it) }
        }

        var insertedTotal = 0
        for ((key, rows) in collected) {
            if (rows.isEmpty()) continue
            epgDao.clearChannelForSources(key, storeIds)
            rows.chunked(QUERY_CHUNK).forEach { epgDao.upsertProgrammes(it) }
            insertedTotal += rows.size
        }
        // Report handled ONLY if the cache actually yielded programmes for a requested id. A fresh cache
        // that contains none of them (stale file, or a channel whose programmes weren't in the earlier
        // tee'd download) must fall through to a network re-sync — the match is now persisted, so the
        // re-sync's channel filter includes it and will fetch the missing schedule. Returning true here
        // used to leave a just-matched channel permanently empty with no fallback.
        insertedTotal > 0
    }

    /**
     * Fill guide gaps left by a catalog sync that finished after the guide sync started (S9).
     *
     * [refreshUrl] filters the feed down to the channels the user owns, and reads that set **once**,
     * at the start. Sync a playlist and a guide at the same time and every channel the playlist
     * hadn't written yet is treated as "not mine" and skipped — the sync still reports success, and
     * the missing programmes stay missing until the user happens to re-sync EPG. (Observed: a guide
     * synced alongside a Stalker import stored 130k programmes; the same feed re-synced afterwards
     * stored 220k.) The same hole opens whenever a later resync adds channels.
     *
     * This runs after a catalog sync and repairs it **from the cached feed — no network**. Only
     * channels with no guide data at all are considered, so a normal resync finds nothing and does no
     * work beyond one indexed query. Returns the number of channels it filled.
     *
     * Deliberately never starts a download: EPG is opt-in, and no cache means the user hasn't synced
     * a guide for this feed yet.
     */
    suspend fun fillGuideGapsForSource(sourceId: Long): Int = withContext(Dispatchers.IO) {
        // Nothing to repair before the user has ever synced a guide — and without this the query
        // below would return every channel the source has.
        if (bulkInsertHelper.tableIsEmpty("epg_programmes")) return@withContext 0
        val missing = channelDao.epgChannelIdsWithoutProgrammes(sourceId)
            .filterTo(HashSet()) { it.isNotBlank() }
        if (missing.isEmpty()) return@withContext 0

        // Most channels without guide data are not a gap at all — their tvg-id simply isn't in any
        // feed the user subscribes to (observed in the field: 760 of 11,527). Parsing the whole
        // cached XMLTV for those takes ~14s and will never find anything, so don't repeat an attempt
        // that already came up empty against the same missing set and the same cache files. Held in
        // memory only: one wasted pass per process is a fair price for never persisting a decision
        // that could go stale.
        val fingerprint = "${missing.size}|${missing.hashCode()}|${cacheFingerprint()}"
        if (futileGapFills[sourceId] == fingerprint) {
            Log.d("EpgRepository", "guide gap fill sourceId=$sourceId skipped — same gap and same cache as the last empty attempt")
            return@withContext 0
        }
        Log.i("EpgRepository", "guide gap fill sourceId=$sourceId channelsWithoutProgrammes=${missing.size}")
        val filled = runCatching { storeProgrammesForIdsFromCache(missing) }
            .onFailure { Log.w("EpgRepository", "guide gap fill failed sourceId=$sourceId", it) }
            .getOrDefault(false)
        // A false return means no fresh cache, or the cache genuinely carries nothing for these ids
        // (they may simply not be in this feed) — either way there is nothing more to do offline.
        val remaining = if (filled) channelDao.epgChannelIdsWithoutProgrammes(sourceId).size else missing.size
        val repaired = (missing.size - remaining).coerceAtLeast(0)
        Log.i("EpgRepository", "guide gap fill done sourceId=$sourceId repaired=$repaired stillMissing=$remaining")
        if (repaired == 0) futileGapFills[sourceId] = fingerprint else futileGapFills.remove(sourceId)
        repaired
    }

    /** Per-source fingerprint of the last gap fill that found nothing — see [fillGuideGapsForSource]. */
    private val futileGapFills = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** Identifies the current set of usable cache files, so a new/refreshed guide invalidates a skip. */
    private fun cacheFingerprint(): String {
        val now = System.currentTimeMillis()
        return context.cacheDir.listFiles()
            ?.filter { isUsableEpgCache(it.name, it.length(), it.lastModified(), now, CACHE_TTL_MS) }
            ?.sortedBy { it.name }
            ?.joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }
            .orEmpty()
    }

    /** An InputStream that mirrors every byte it reads into [out] — lets us parse a download while caching it. */
    private class TeeInputStream(
        private val src: java.io.InputStream,
        private val out: java.io.OutputStream,
    ) : java.io.InputStream() {
        override fun read(): Int { val b = src.read(); if (b >= 0) out.write(b); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len); if (n > 0) out.write(b, off, n); return n
        }
        override fun close() = src.close() // the caller owns [out] (closed by its own use{})
    }

    /** Drop a removed EPG source's stored programmes. */
    suspend fun clear(storeId: Long) = withContext(Dispatchers.IO) {
        epgDao.clearSource(storeId)
        epgDao.clearChannelsForSource(storeId)
    }

    companion object {
        /**
         * How a Stalker portal's own guide is addressed where a URL is expected. Not a real scheme and
         * never fetched: the EPG machinery — the sources list, the sync worker, the staleness check,
         * the error line — is all keyed by a source's URL, so giving the portal guide one lets it reuse
         * every bit of that instead of growing a parallel path with its own screen and its own bugs.
         */
        private const val STALKER_GUIDE_SCHEME = "stalker://"

        fun stalkerGuideUrl(sourceId: Long): String = "$STALKER_GUIDE_SCHEME$sourceId"

        /** The playlist id inside a [stalkerGuideUrl], or null when this is an ordinary feed URL. */
        fun stalkerSourceIdOf(url: String): Long? =
            url.takeIf { it.startsWith(STALKER_GUIDE_SCHEME) }?.removePrefix(STALKER_GUIDE_SCHEME)?.trim()?.toLongOrNull()

        // Keep up to ~7 days of just-aired programmes so the Guide can browse a long catch-up archive
        // (still bounded, and ultimately limited by how much past data the EPG feed actually provides —
        // many xmltv.php feeds only return 1–2 days of past programmes, so storage rarely reaches 7 days).
        private const val WINDOW_BACK_MS = 7L * 24 * 60 * 60 * 1000
        private const val WINDOW_AHEAD_MS = 48L * 60 * 60 * 1000 // and 48h ahead
        private const val QUERY_CHUNK = 500
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // reuse a cached XMLTV for incremental matches up to 24h
        private const val PROGRESS_PROGRAMME_STEP = 500
        private const val EPG_DOWNLOAD_ATTEMPTS = 3

        // Upper bound on programme (id, hash) entries kept in memory during an incremental EPG sync.
        // ~100k entries is roughly 15–20 MB of Java heap — safe on low-RAM TV boxes. Channels beyond
        // the cap fall back to write-through (correct via the natural-key unique index + REPLACE),
        // they just lose the skip-unchanged and precise-prune optimizations for this run.
        private const val MAX_TRACKED_PROGRAMMES = 100_000
    }
}

/** Decision for one parsed programme, from [ProgrammeHashTracker.observe]. */
private sealed interface ProgrammeDecision {
    data object New : ProgrammeDecision
    data class Changed(val id: Long) : ProgrammeDecision
    data object Unchanged : ProgrammeDecision
    data object WriteThrough : ProgrammeDecision
}

/**
 * Memory-bounded replacement for the old whole-source programme hash map. Hashes are loaded lazily
 * per EPG channel (an indexed point query on the natural key) and only while the total entry count
 * stays under [maxEntries]; channels first seen after that are handled write-through. For a fresh
 * source nothing is loaded — the per-channel maps only dedupe repeats inside the feed itself.
 */
private class ProgrammeHashTracker(
    private val dao: tv.own.owntv.core.database.dao.EpgDao,
    private val sourceId: Long,
    private val freshSource: Boolean,
    private val maxEntries: Int,
) {
    // channel -> startMs -> (rowId, contentHash); rowId 0 = row inserted during this run
    private val byChannel = HashMap<String, MutableMap<Long, Pair<Long, Int>>>()
    private val seenByChannel = HashMap<String, MutableSet<Long>>()
    private val untrackedChannels = HashSet<String>()
    private var entries = 0
    var overflowed = false
        private set

    suspend fun observe(channel: String, startMs: Long, hash: Int): ProgrammeDecision {
        if (channel in untrackedChannels) return ProgrammeDecision.WriteThrough
        val map = byChannel[channel] ?: run {
            if (entries >= maxEntries) {
                untrackedChannels.add(channel)
                if (!overflowed) {
                    overflowed = true
                    Log.w("EpgRepository", "EPG hash tracker cap ($maxEntries) reached for sourceId=$sourceId — further channels sync write-through")
                }
                return ProgrammeDecision.WriteThrough
            }
            val loaded: MutableMap<Long, Pair<Long, Int>> = if (freshSource) {
                HashMap()
            } else {
                dao.epgHashesForChannel(sourceId, channel)
                    .associateTo(HashMap()) { it.startMs to (it.id to it.contentHash) }
            }
            entries += loaded.size
            byChannel[channel] = loaded
            loaded
        }
        if (!freshSource) seenByChannel.getOrPut(channel) { HashSet() }.add(startMs)
        val existing = map[startMs]
        return when {
            existing == null -> {
                map[startMs] = 0L to hash
                entries++
                ProgrammeDecision.New
            }
            existing.second == hash -> ProgrammeDecision.Unchanged
            else -> {
                map[startMs] = existing.first to hash
                ProgrammeDecision.Changed(existing.first)
            }
        }
    }

    /** Row ids of tracked programmes that existed before this run but never appeared in the feed. */
    fun staleTrackedIds(): List<Long> {
        if (freshSource) return emptyList()
        val stale = ArrayList<Long>()
        for ((channel, map) in byChannel) {
            val seen = seenByChannel[channel] ?: emptySet()
            for ((startMs, value) in map) {
                if (value.first != 0L && startMs !in seen) stale.add(value.first)
            }
        }
        return stale
    }
}

/**
 * Split a stored guide address into the feeds it actually names. Commas are legal inside a URL, so a
 * value is only treated as a list when every part is itself an absolute http(s) address — which also
 * keeps the Stalker portal marker URL intact. Whitespace is trimmed and duplicates dropped.
 */
fun splitGuideUrls(raw: String?): List<String> {
    val url = raw?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    if (',' !in url) return listOf(url)
    val parts = url.split(',').map { it.trim() }
    if (parts.any { !it.startsWith("http://", true) && !it.startsWith("https://", true) }) return listOf(url)
    return parts.distinct()
}
