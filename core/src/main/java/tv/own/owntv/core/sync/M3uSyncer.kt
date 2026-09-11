package tv.own.owntv.core.sync

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.InputStream
import java.util.Objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeasonEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.drm.DrmConfig
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.parser.M3uParser

/**
 * The M3U import flow (split out of SyncManager, Phase 0 of the Stalker plan). M3U playlists carry
 * no provider item ids, so a stable key is synthesized per item — `name|group` (with an `#n` suffix
 * for in-playlist duplicates) stored in `remoteId` — and resyncs run the same hash-diffed stable
 * upsert as Xtream/Stalker: unchanged rows are skipped, changed rows keep their local id (so
 * favorites/history/progress/manual-order survive resyncs), vanished rows are pruned. `sortOrder`
 * is compared alongside the hash, so playlist reordering still propagates. Pruning only touches a
 * content type that actually appeared in the playlist, so a failed download or a live-only playlist
 * never wipes previously-imported rows of other types.
 */
internal class M3uSyncer(
    private val context: android.content.Context,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val m3u: M3uParser,
    private val http: HttpClient,
    private val bulkInsertHelper: BulkInsertHelper,
    private val support: SyncSupport,
) {
    // Channels and movies use the shared adapters (S1): sortOrder no longer needs folding into the
    // M3U hash, because upsertStable now compares the stored sortOrder separately, so a reordered
    // playlist still counts as changed without a bespoke hash here.
    private val channelAdapter get() = support.channelAdapter
    private val movieAdapter get() = support.movieAdapter

    suspend fun sync(s: SourceEntity, progress: SyncCounters, stats: SyncStatsCollector) {
        val channelsStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        val freshSource = s.lastSyncAt == null
        val chunkSize = if (freshSource) BulkInsertHelper.CHUNK_FRESH else BulkInsertHelper.CHUNK
        val reportBytes = SyncSupport.IgnoreByteProgress
        // A locally-picked playlist file (in-app StorageBrowser gives an absolute path; also tolerate
        // file://content:// URIs) is read straight from the device; a normal URL is downloaded. Same parser.
        val isLocal = s.url.startsWith("/") || s.url.startsWith("file://") || s.url.startsWith("content://")
        val localBytes = if (isLocal) localPlaylistSize(s.url) else null
        Log.i(TAG, "M3U phase start sourceId=${s.id} local=$isLocal fresh=$freshSource bytesTotal=${localBytes ?: -1}")
        progress.update(SyncPhase.LIVE, 0)

        var processed = 0
        var moviesProcessed = 0
        var seriesProcessed = 0
        val header = bulkInsertHelper.withOptimizedBulkInsert(
            "channels",
            "channels_fts",
            eligible = freshSource,
            ftsOnly = true,
        ) {
            // Existing rows' (remoteId -> id, contentHash) lookups — loaded lazily per type so a
            // live-only playlist never pays for movie/series queries. Loaded even on a fresh source:
            // a retried first sync may have leftover rows from the failed attempt, and diffing
            // against them (instead of blind-inserting) keeps the path single and idempotent.
            var channelHashes: Map<String, StoredRow>? = null
            var movieHashes: Map<String, StoredRow>? = null
            var seriesHashes: Map<String, StoredRow>? = null
            suspend fun channelHashLookup() = channelHashes ?: loadHashLookup("M3U live", s.id) {
                channelDao.contentHashesForSource(it)
            }.also { channelHashes = it }
            suspend fun movieHashLookup() = movieHashes ?: loadHashLookup("M3U movies", s.id) {
                movieDao.contentHashesForSource(it)
            }.also { movieHashes = it }
            suspend fun seriesHashLookup() = seriesHashes ?: loadHashLookup("M3U series", s.id) {
                seriesDao.contentHashesForSource(it)
            }.also { seriesHashes = it }

            // Stable-key bookkeeping: occurrence counters make in-playlist duplicate keys unique and
            // deterministic; seen sets drive the end-of-parse prune.
            val channelKeyCounters = HashMap<String, Int>()
            val movieKeyCounters = HashMap<String, Int>()
            // Parallel counters reproducing the pre-1.0.30 truncated keys, for [rescueTruncatedKeys].
            val legacyChannelKeyCounters = HashMap<String, Int>()
            val legacyMovieKeyCounters = HashMap<String, Int>()
            val seenChannelKeys = HashSet<String>()
            val seenMovieKeys = HashSet<String>()
            val seenSeriesKeys = HashSet<String>()
            val seenGroupsByType = HashMap<MediaType, MutableSet<String>>()
            val newCategoriesByType = HashMap<MediaType, MutableList<CategoryEntity>>()

            // Categories are per-mediaType: the same group-title can exist for both live and VOD.
            val groupToCategoryId = HashMap<Pair<MediaType, String>, Long>()
            val pendingCategoryKeys = LinkedHashSet<Pair<MediaType, String>>()
            val pendingCategories = ArrayList<CategoryEntity>(chunkSize)
            val buffer = ArrayList<PendingM3uChannel>(chunkSize)
            val movieBuffer = ArrayList<PendingM3uChannel>(chunkSize)
            var order = 0 // playlist position — lets "Playlist order" sorting replay the file's order
            var categoryOrder = 0

            fun queueCategory(type: MediaType, group: String) {
                seenGroupsByType.getOrPut(type) { HashSet() }.add(group)
                val key = type to group
                if (groupToCategoryId.containsKey(key) || !pendingCategoryKeys.add(key)) return
                pendingCategories.add(
                    CategoryEntity(
                        sourceId = s.id,
                        mediaType = type,
                        name = group,
                        remoteId = group,
                        sortOrder = categoryOrder++,
                    ),
                )
            }

            // Stable category upsert (mirrors SyncSupport.upsertCategoriesStable, but incremental —
            // M3U discovers groups mid-stream): existing groups keep their category id, so content
            // rows' categoryId — and the hashes derived from it — stay stable across resyncs.
            suspend fun flushCategories() {
                if (pendingCategories.isEmpty()) return
                ctx.ensureActive()
                val start = SystemClock.elapsedRealtime()
                pendingCategories.groupBy { it.mediaType }.forEach { (type, cats) ->
                    val existing = cats.mapNotNull { it.remoteId }.chunked(SyncSupport.QUERY_CHUNK)
                        .flatMap { categoryDao.findByRemoteIds(s.id, type, it) }
                        .associateBy { it.remoteId }
                    val inserts = ArrayList<CategoryEntity>()
                    val updates = ArrayList<CategoryEntity>()
                    cats.forEach { row ->
                        val current = existing[row.remoteId]
                        when {
                            current == null -> inserts.add(row)
                            row.name != current.name || row.sortOrder != current.sortOrder -> {
                                updates.add(row.copy(id = current.id))
                                groupToCategoryId[type to row.remoteId!!] = current.id
                            }
                            else -> groupToCategoryId[type to row.remoteId!!] = current.id
                        }
                    }
                    if (updates.isNotEmpty()) categoryDao.updateAll(updates)
                    if (inserts.isNotEmpty()) {
                        val ids = categoryDao.insertAll(inserts)
                        val missed = ArrayList<String>()
                        inserts.forEachIndexed { i, row ->
                            val rid = row.remoteId ?: return@forEachIndexed
                            val id = ids.getOrNull(i) ?: -1L
                            if (id > 0) groupToCategoryId[type to rid] = id else missed.add(rid)
                        }
                        // IGNOREd conflicts return −1 (shouldn't happen — inserts were pre-checked);
                        // heal by re-fetching just those rows.
                        if (missed.isNotEmpty()) {
                            missed.chunked(SyncSupport.QUERY_CHUNK)
                                .flatMap { categoryDao.findByRemoteIds(s.id, type, it) }
                                .forEach { cat -> cat.remoteId?.let { groupToCategoryId[type to it] = cat.id } }
                        }
                        if (!freshSource) newCategoriesByType.getOrPut(type) { ArrayList() }.addAll(inserts)
                    }
                }
                Log.d(TAG, "M3U categories flush sourceId=${s.id} rows=${pendingCategories.size} ms=${SystemClock.elapsedRealtime() - start}")
                pendingCategoryKeys.clear()
                pendingCategories.clear()
            }

            suspend fun flushChannels() {
                if (buffer.isEmpty()) return
                flushCategories()
                ctx.ensureActive()
                val channels = buffer.map { item ->
                    val entry = item.entry
                    val group = entry.groupTitle
                    ChannelEntity(
                        sourceId = s.id,
                        categoryId = group?.let { groupToCategoryId[MediaType.LIVE to it] },
                        name = entry.name,
                        logoUrl = entry.logo,
                        streamUrl = entry.streamUrl,
                        epgChannelId = entry.tvgId,
                        number = entry.tvgChno,
                        remoteId = stableKey(channelKeyCounters, entry.name, group),
                        sortOrder = item.order,
                        catchup = entry.catchup != null,
                        catchupDays = entry.catchupDays ?: 0,
                        catchupSource = entry.catchupSource,
                        catchupType = entry.catchup,
                        httpHeaders = StreamHeaders.encode(entry.headers),
                        drmConfig = DrmConfig.encode(entry.drm),
                    )
                }
                channels.forEach { seenChannelKeys.add(it.remoteId!!) }
                val legacyKeys = buffer.map { legacyStableKey(legacyChannelKeyCounters, it.entry.name, it.entry.groupTitle) }
                val start = SystemClock.elapsedRealtime()
                val stored = rescueTruncatedKeys(channelHashLookup(), channels.map { it.remoteId!! }.zip(legacyKeys))
                val upsert = support.upsertStable(channels, CompletableDeferred(stored), channelAdapter)
                processed += channels.size
                Log.d(
                    TAG,
                    "M3U channel flush sourceId=${s.id} rows=${channels.size} dbInserted=${upsert.inserted} " +
                        "dbUpdated=${upsert.updated} dbSkipped=${upsert.skippedUnchanged} processed=$processed " +
                        "ms=${SystemClock.elapsedRealtime() - start}",
                )
                buffer.clear()
                progress.update(SyncPhase.LIVE, processed)
            }

            suspend fun flushMovies() {
                if (movieBuffer.isEmpty()) return
                flushCategories()
                ctx.ensureActive()
                val movies = movieBuffer.map { item ->
                    val entry = item.entry
                    val group = entry.groupTitle
                    MovieEntity(
                        sourceId = s.id,
                        categoryId = group?.let { groupToCategoryId[MediaType.MOVIE to it] },
                        name = entry.name,
                        posterUrl = entry.logo,
                        streamUrl = entry.streamUrl,
                        remoteId = stableKey(movieKeyCounters, entry.name, group),
                        sortOrder = item.order,
                        httpHeaders = StreamHeaders.encode(entry.headers),
                        drmConfig = DrmConfig.encode(entry.drm),
                    )
                }
                movies.forEach { seenMovieKeys.add(it.remoteId!!) }
                val legacyKeys = movieBuffer.map { legacyStableKey(legacyMovieKeyCounters, it.entry.name, it.entry.groupTitle) }
                val start = SystemClock.elapsedRealtime()
                val stored = rescueTruncatedKeys(movieHashLookup(), movies.map { it.remoteId!! }.zip(legacyKeys))
                val upsert = support.upsertStable(movies, CompletableDeferred(stored), movieAdapter)
                moviesProcessed += movies.size
                Log.d(
                    TAG,
                    "M3U movie flush sourceId=${s.id} rows=${movies.size} dbInserted=${upsert.inserted} " +
                        "dbUpdated=${upsert.updated} dbSkipped=${upsert.skippedUnchanged} processed=$moviesProcessed " +
                        "ms=${SystemClock.elapsedRealtime() - start}",
                )
                movieBuffer.clear()
                progress.update(SyncPhase.MOVIES, moviesProcessed)
            }

            // Series-tagged entries are per-EPISODE lines ("Show S01E05"); they're grouped by show
            // name into series → seasons → episodes and written once at the end of the parse (series
            // playlists are small — hundreds to a few thousand lines — so buffering them is cheap).
            val seriesAccumulator = LinkedHashMap<String, M3uShowAccumulator>()

            // S5 — bound the accumulator. A large series playlist (tens of thousands of episode
            // lines) would otherwise hold every episode of every show in memory until end-of-parse.
            // Once this many episode rows are buffered they are written out and the accumulator is
            // cleared. Episodes of one show are contiguous in every real playlist, so a show is
            // normally complete when the threshold trips; a show that *is* split across two flushes
            // is appended to rather than rewritten (see flushSeries), so no episodes are lost.
            var pendingEpisodeRows = 0
            // key → series row id for shows already written during this parse, so a second flush of
            // the same show appends instead of colliding on the unique (sourceId, remoteId) index.
            val flushedShows = HashMap<String, Long>()
            val flushedShowHashes = HashMap<String, Int>()

            suspend fun writeEpisodes(seriesId: Long, show: M3uShowAccumulator): Int {
                val seasonNumbers = show.episodes.map { it.season }.distinct().sorted()
                val seasonIds = seriesDao.upsertSeasonsReturnIds(
                    seasonNumbers.map { n -> SeasonEntity(seriesId = seriesId, seasonNumber = n, name = n.toString()) },
                )
                val seasonIdByNumber = seasonNumbers.zip(seasonIds).toMap()
                seriesDao.upsertEpisodes(
                    show.episodes.map { ep ->
                        EpisodeEntity(
                            seriesId = seriesId,
                            seasonId = seasonIdByNumber[ep.season],
                            seasonNumber = ep.season,
                            episodeNumber = ep.episode,
                            name = ep.title,
                            streamUrl = ep.streamUrl,
                            httpHeaders = ep.httpHeaders,
                            drmConfig = ep.drmConfig,
                        )
                    },
                )
                return show.episodes.size
            }

            /** [final] = the end-of-parse flush. A bounded mid-parse flush passes false: shows may
             *  still grow, so the unchanged fast path is disabled and every written show is recorded
             *  in [flushedShows] so later episodes append to it. */
            suspend fun flushSeries(final: Boolean = true) {
                if (seriesAccumulator.isEmpty()) return
                flushCategories()
                ctx.ensureActive()
                val start = SystemClock.elapsedRealtime()
                val shows = seriesAccumulator.values.toList()
                val existing = rescueTruncatedKeys(
                    seriesHashLookup(),
                    shows.map { show ->
                        val group = show.group.orEmpty()
                        "${show.name}$KEY_SEPARATOR$group" to "${show.name.substringAfterLast(',').trim()}$KEY_SEPARATOR$group"
                    },
                )
                val inserts = ArrayList<Pair<SeriesEntity, M3uShowAccumulator>>()
                val changed = ArrayList<Pair<SeriesEntity, M3uShowAccumulator>>() // entity already rekeyed to local id
                val appended = ArrayList<Pair<SeriesEntity, M3uShowAccumulator>>() // continued from an earlier flush
                var skipped = 0
                shows.forEach { show ->
                    // Accumulator entries are unique per show name, so name|group needs no counter.
                    val key = "${show.name}$KEY_SEPARATOR${show.group.orEmpty()}"
                    seenSeriesKeys.add(key)
                    val entity = SeriesEntity(
                        sourceId = s.id,
                        categoryId = show.group?.let { groupToCategoryId[MediaType.SERIES to it] },
                        name = show.name,
                        posterUrl = show.logo,
                        remoteId = key,
                        sortOrder = show.order,
                    )
                    // The series hash folds in the episode list: a playlist that only adds S01E06
                    // must count as changed even though the show row itself is identical.
                    val hash = Objects.hash(entity.computeContentHash(), entity.sortOrder, episodesHash(show))
                    val current = existing[key]
                    val alreadyFlushed = flushedShows[key]
                    when {
                        // Continuation of a show whose earlier episodes were written by a bounded
                        // flush this same parse: its hierarchy was already cleared then, so append.
                        // The hash folds the previous partial hash so the stored value still covers
                        // the whole episode list.
                        alreadyFlushed != null -> {
                            val combined = Objects.hash(flushedShowHashes[key] ?: 0, hash)
                            flushedShowHashes[key] = combined
                            appended.add(entity.copy(id = alreadyFlushed, contentHash = combined) to show)
                        }
                        current == null -> inserts.add(entity.copy(contentHash = hash) to show)
                        // Mid-parse (bounded) flushes can't use the unchanged fast path: the show may
                        // still gain episodes later in the file, and skipping now would leave the
                        // previous sync's hierarchy in place for a continuation to append onto.
                        !final || current.contentHash != hash ->
                            changed.add(entity.copy(id = current.id, contentHash = hash) to show)
                        else -> skipped++ // episodes untouched too — they only change with the folded hash
                    }
                }
                var episodesWritten = 0
                if (inserts.isNotEmpty()) {
                    val ids = seriesDao.upsertSeriesReturnIds(inserts.map { it.first })
                    inserts.forEachIndexed { i, (entity, show) ->
                        val id = ids.getOrNull(i)?.takeIf { it > 0 } ?: return@forEachIndexed
                        episodesWritten += writeEpisodes(id, show)
                        if (!final) {
                            val key = entity.remoteId!!
                            flushedShows[key] = id
                            flushedShowHashes[key] = entity.contentHash
                        }
                    }
                }
                if (changed.isNotEmpty()) {
                    seriesDao.updateSeries(changed.map { it.first })
                    changed.forEach { (entity, show) ->
                        // Rewrite the hierarchy so vanished episodes disappear; the series id is kept.
                        seriesDao.deleteSeasons(entity.id)
                        seriesDao.deleteEpisodes(entity.id)
                        episodesWritten += writeEpisodes(entity.id, show)
                        if (!final) {
                            val key = entity.remoteId!!
                            flushedShows[key] = entity.id
                            flushedShowHashes[key] = entity.contentHash
                        }
                    }
                }
                if (appended.isNotEmpty()) {
                    // Hierarchy already cleared by the earlier flush — only the row hash is refreshed.
                    seriesDao.updateSeries(appended.map { it.first })
                    appended.forEach { (entity, show) -> episodesWritten += writeEpisodes(entity.id, show) }
                }
                // Continuations must not be counted twice; flushedShows is the set of distinct shows
                // written by bounded flushes so far.
                seriesProcessed += inserts.size + changed.size + skipped
                Log.d(
                    TAG,
                    "M3U series flush sourceId=${s.id} final=$final shows=${shows.size} dbInserted=${inserts.size} " +
                        "dbUpdated=${changed.size} dbAppended=${appended.size} dbSkipped=$skipped episodes=$episodesWritten " +
                        "totalShows=$seriesProcessed ms=${SystemClock.elapsedRealtime() - start}",
                )
                seriesAccumulator.clear()
                pendingEpisodeRows = 0
                progress.update(SyncPhase.SERIES, seriesProcessed)
            }

            val onEntry: suspend (tv.own.owntv.core.parser.M3uEntry) -> Unit = { e ->
                when {
                    // type="series" / tvg-type="series" → grouped into the Series tab.
                    e.isSeries -> {
                        e.groupTitle?.let { queueCategory(MediaType.SERIES, it) }
                        val parsed = parseM3uEpisode(e.name)
                        val show = seriesAccumulator.getOrPut(parsed.show.lowercase()) {
                            M3uShowAccumulator(name = parsed.show, logo = e.logo, group = e.groupTitle, order = order++)
                        }
                        val episode = if (parsed.episode > 0) parsed.episode else show.episodes.count { it.season == parsed.season } + 1
                        show.episodes.add(
                            M3uEpisodeRow(
                                season = parsed.season,
                                episode = episode,
                                title = parsed.title ?: parsed.show,
                                streamUrl = e.streamUrl,
                                httpHeaders = StreamHeaders.encode(e.headers),
                                drmConfig = DrmConfig.encode(e.drm),
                            ),
                        )
                        pendingEpisodeRows++
                        if (pendingEpisodeRows >= SERIES_EPISODE_FLUSH_ROWS) flushSeries(final = false)
                    }
                    // Other VOD tags (type="vod"/"movie", tvg-type="vod"/"movie") → the movie grid.
                    e.isVod -> {
                        e.groupTitle?.let { queueCategory(MediaType.MOVIE, it) }
                        movieBuffer.add(PendingM3uChannel(order = order++, entry = e))
                        if (movieBuffer.size >= chunkSize) {
                            flushMovies()
                        }
                    }
                    else -> {
                        e.groupTitle?.let { queueCategory(MediaType.LIVE, it) }
                        buffer.add(PendingM3uChannel(order = order++, entry = e))
                        if (buffer.size >= chunkSize) {
                            flushChannels()
                        }
                    }
                }
            }
            val header = if (isLocal) {
                openLocalPlaylist(s.url).use { input -> m3u.parse(input, onEntry) }
            } else {
                http.get(s.url, s.userAgent, reportBytes) { input -> m3u.parse(input, onEntry) }
            }
            if (buffer.isNotEmpty()) {
                flushChannels()
            }
            if (movieBuffer.isNotEmpty()) {
                flushMovies()
            }
            flushSeries()

            // Prune — only for content types that actually appeared, so a live-only playlist (or one
            // whose VOD section failed to parse) never wipes previously-imported rows of other types.
            // Runs only after a fully successful parse (an exception above skips it), and also drops
            // legacy null-remoteId rows from the clear-then-insert era (one-time upgrade cleanup).
            if (processed > 0) {
                support.pruneRemoteIds("M3U live", s.id, seenChannelKeys, stats, { channelDao.remoteIdsForSource(it) }) { src, ids ->
                    channelDao.deleteByRemoteIds(src, ids)
                }
                channelDao.deleteNullRemoteIds(s.id)
                support.pruneCategories(s.id, MediaType.LIVE, seenGroupsByType[MediaType.LIVE].orEmpty(), "M3U live", stats)
            }
            if (moviesProcessed > 0) {
                support.pruneRemoteIds("M3U movies", s.id, seenMovieKeys, stats, { movieDao.remoteIdsForSource(it) }) { src, ids ->
                    movieDao.deleteByRemoteIds(src, ids)
                }
                movieDao.deleteNullRemoteIds(s.id)
                support.pruneCategories(s.id, MediaType.MOVIE, seenGroupsByType[MediaType.MOVIE].orEmpty(), "M3U movies", stats)
            }
            if (seriesProcessed > 0) {
                support.pruneRemoteIds("M3U series", s.id, seenSeriesKeys, stats, { seriesDao.remoteIdsForSource(it) }) { src, ids ->
                    seriesDao.deleteByRemoteIds(src, ids)
                }
                seriesDao.deleteNullRemoteIds(s.id) // seasons/episodes cascade
                support.pruneCategories(s.id, MediaType.SERIES, seenGroupsByType[MediaType.SERIES].orEmpty(), "M3U series", stats)
            }

            // "Hide new categories on resync" — same behavior as Xtream/Stalker's refreshCategories.
            newCategoriesByType.forEach { (type, cats) ->
                if (cats.isNotEmpty()) {
                    stats.processedCounts.merge(SyncSupport.CATEGORIES_ADDED_KEY, cats.size, Int::plus)
                    support.applyHideNewCategoriesDefault(s.id, type, cats)
                }
            }
            header
        }
        // Persist the playlist's EPG url (url-tvg) for the EPG engine if the source didn't have one.
        if (!header.urlTvg.isNullOrBlank() && s.epgUrl.isNullOrBlank()) {
            sourceDao.update(s.copy(epgUrl = header.urlTvg))
        }
        progress.update(SyncPhase.LIVE, processed)
        if (moviesProcessed > 0) progress.update(SyncPhase.MOVIES, moviesProcessed)
        if (seriesProcessed > 0) progress.update(SyncPhase.SERIES, seriesProcessed)
        stats.phaseTiming["channels"] = System.currentTimeMillis() - channelsStart
        stats.processedCounts["channels"] = processed
        if (moviesProcessed > 0) stats.processedCounts["movies"] = moviesProcessed
        if (seriesProcessed > 0) stats.processedCounts["series"] = seriesProcessed
        Log.i(TAG, "M3U phase end sourceId=${s.id} processed=$processed movies=$moviesProcessed series=$seriesProcessed ms=${SystemClock.elapsedRealtime() - elapsedStart}")
    }

    private suspend fun loadHashLookup(
        label: String,
        sourceId: Long,
        load: suspend (Long) -> List<tv.own.owntv.core.database.entity.ContentHashProjection>,
    ): Map<String, StoredRow> {
        val start = SystemClock.elapsedRealtime()
        return load(sourceId).associateBy({ it.remoteId }, { StoredRow(it.id, it.contentHash, it.sortOrder) }).also {
            Log.d(TAG, "$label hash map loaded sourceId=$sourceId size=${it.size} ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private data class PendingM3uChannel(
        val order: Int,
        val entry: tv.own.owntv.core.parser.M3uEntry,
    )

    /** One M3U series-tagged show being accumulated during a playlist parse. */
    private class M3uShowAccumulator(
        val name: String,
        val logo: String?,
        val group: String?,
        val order: Int,
    ) {
        val episodes = ArrayList<M3uEpisodeRow>()
    }

    private data class M3uEpisodeRow(
        val season: Int,
        val episode: Int,
        val title: String,
        val streamUrl: String,
        val httpHeaders: String? = null,
        val drmConfig: String? = null,
    )

    private data class ParsedM3uEpisode(val show: String, val season: Int, val episode: Int, val title: String?)

    /**
     * Splits an M3U series entry title like "Stranger Things S01E05" / "Show 2x03 - Pilot" into
     * show + season + episode (+ optional episode title). Entries without a recognizable pattern
     * become season 1 with sequential episode numbers ("Tales From The Crypt (1989-90s)").
     */
    private fun parseM3uEpisode(rawName: String): ParsedM3uEpisode {
        val name = rawName.trim()
        M3U_EPISODE_SXXEYY.find(name)?.let { m ->
            val show = name.substring(0, m.range.first).trim(' ', '-', '.', '_', ':')
            val title = name.substring(m.range.last + 1).trim(' ', '-', '.', '_', ':').takeIf { it.isNotEmpty() }
            if (show.isNotEmpty()) {
                return ParsedM3uEpisode(show, m.groupValues[1].toInt(), m.groupValues[2].toInt(), title)
            }
        }
        M3U_EPISODE_NXN.find(name)?.let { m ->
            val show = name.substring(0, m.range.first).trim(' ', '-', '.', '_', ':')
            val title = name.substring(m.range.last + 1).trim(' ', '-', '.', '_', ':').takeIf { it.isNotEmpty() }
            if (show.isNotEmpty()) {
                return ParsedM3uEpisode(show, m.groupValues[1].toInt(), m.groupValues[2].toInt(), title)
            }
        }
        return ParsedM3uEpisode(show = name, season = 1, episode = 0, title = null) // episode 0 → sequential
    }

    /**
     * Size of a local playlist, for the progress log — read without opening the stream, so the
     * descriptor is only ever held around the parse itself (S6). Null when it can't be determined.
     */
    private fun localPlaylistSize(url: String): Long? = when {
        url.startsWith("/") -> File(url).length().takeIf { it > 0 }
        url.startsWith("file://") -> Uri.parse(url).path?.let { File(it).length().takeIf { len -> len > 0 } }
        url.startsWith("content://") -> runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(url), "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        }.getOrNull()
        else -> null
    }

    /** Opens a local playlist for reading. The caller MUST close it — always via `use { }` (S6):
     *  before, an exception between opening and parsing leaked the fd (and, for `content://`, the
     *  provider's file handle) for the life of the process. */
    private fun openLocalPlaylist(url: String): InputStream = when {
        url.startsWith("/") -> File(url).inputStream()
        url.startsWith("file://") -> {
            val uri = Uri.parse(url)
            File(uri.path ?: throw java.io.IOException("playlist_file_unavailable")).inputStream()
        }
        url.startsWith("content://") ->
            context.contentResolver.openInputStream(Uri.parse(url))
                ?: throw java.io.IOException("playlist_file_unavailable")
        else -> throw java.io.IOException("playlist_path_unsupported")
    }

    companion object {
        private const val TAG = SyncSupport.TAG

        /** "S01E05" / "s1 e5" — the common episode marker in M3U series playlists. */
        private val M3U_EPISODE_SXXEYY = Regex("""(?i)\bS(\d{1,2})\s*[.\-_ ]?\s*E(\d{1,3})\b""")

        /** "1x05" alternative marker. */
        private val M3U_EPISODE_NXN = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")

        /** Bound on the in-memory series accumulator (S5) — episode rows buffered before an early
         *  flush. Matched to the bulk-insert chunk so a flush is one normal-sized DB batch. */
        private val SERIES_EPISODE_FLUSH_ROWS = BulkInsertHelper.CHUNK

        /** Separates name from group in synthesized keys; unlikely in real channel names. */
        private const val KEY_SEPARATOR = "\u0001"

        /**
         * Synthesized stable id for an M3U item: `name␁group`, with an `␁#n` suffix for the 2nd+
         * occurrence of the same name+group in one playlist (true duplicates, kept distinct so the
         * unique `(sourceId, remoteId)` index can't silently drop them). Deterministic across
         * resyncs as long as the playlist keeps its duplicates in file order.
         */
        /**
         * The key the pre-1.0.30 rule would have produced for the same entry: the name cut at its
         * last comma. Equal to the real key for any name without a comma, which is nearly all of them.
         */
        private fun legacyStableKey(counters: HashMap<String, Int>, name: String, group: String?): String =
            stableKey(counters, name.substringAfterLast(',').trim(), group)

        /**
         * One-time rescue for rows stored under a pre-1.0.30 truncated name.
         *
         * Until 1.0.30 the display name was `substringAfterLast(',')` of the `#EXTINF` line, so
         * `Movie, The (1999)` was stored as `The (1999)`. [stableKey] derives the row's stable id from
         * the name, so correcting the name also changes the key: left alone, the corrected entry is
         * inserted as a *new* row and the truncated one is pruned, taking that title's favourites,
         * history and resume position with it.
         *
         * So every current key the database doesn't know is tried once under the old rule. A hit maps
         * the new key onto the stored row, and the upsert then updates that row in place — same local
         * id, so everything pinned to it survives. After that sync the row carries the new key and
         * this does nothing.
         *
         * Deliberately skipped when the answer would be a guess: two current names collapsing onto one
         * legacy key, or a legacy key that is itself a name in this playlist (a real channel "Music"
         * alongside "Live, Love, Music"). Those keep the old behaviour — a new row, and the stale one
         * pruned.
         */
        @VisibleForTesting
        internal fun rescueTruncatedKeys(
            stored: Map<String, StoredRow>,
            keys: List<Pair<String, String>>,
        ): Map<String, StoredRow> {
            val candidates = keys.filter { (now, was) -> now != was && now !in stored && was in stored }
            if (candidates.isEmpty()) return stored
            val currentNames = keys.mapTo(HashSet()) { it.first }
            val claims = candidates.groupingBy { it.second }.eachCount()
            val rescued = candidates.filter { (_, was) -> claims[was] == 1 && was !in currentNames }
            if (rescued.isEmpty()) return stored
            Log.i(TAG, "M3U truncated-name rescue: ${rescued.size} row(s) relinked to their full name")
            return stored + rescued.map { (now, was) -> now to stored.getValue(was) }
        }

        private fun stableKey(counters: HashMap<String, Int>, name: String, group: String?): String {
            val base = "$name$KEY_SEPARATOR${group.orEmpty()}"
            val n = counters.merge(base, 1, Int::plus)!!
            return if (n == 1) base else "$base$KEY_SEPARATOR#$n"
        }

        /** Order-sensitive hash of a show's episode list, folded into the series content hash. */
        private fun episodesHash(show: M3uShowAccumulator): Int =
            show.episodes.fold(0) { acc, ep ->
                // httpHeaders and drmConfig folded only when present, so playlists without per-item
                // headers or DRM keep the hashes they already have and don't rewrite on first sync.
                val base = Objects.hash(ep.season, ep.episode, ep.title, ep.streamUrl)
                val withHeaders = if (ep.httpHeaders == null) base else Objects.hash(base, ep.httpHeaders)
                31 * acc + if (ep.drmConfig == null) withHeaders else Objects.hash(withHeaders, ep.drmConfig)
            }
    }
}
