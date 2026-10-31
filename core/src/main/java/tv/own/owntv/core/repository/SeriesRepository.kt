package tv.own.owntv.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StalkerClient.StalkerAuthException
import tv.own.owntv.core.stalker.StalkerCredentials
import tv.own.owntv.core.stalker.stalkerCredentials

/**
 * What opening a show's episode list came to. A provider refusing the request used to read exactly
 * like a show with no episodes; [Failed] keeps the two apart on screen.
 */
sealed interface EpisodeLoad {
    /** Episodes are stored — cached, or just fetched. */
    data object Ready : EpisodeLoad

    /** Nothing stored, and there is nothing to fetch or the provider sent none. */
    data object Empty : EpisodeLoad

    /** Nothing stored, because the request failed. [reason] is technical and untranslated. */
    data class Failed(val reason: String) : EpisodeLoad
}

/**
 * The reason a [EpisodeLoad.Failed] shows: the provider's HTTP status, else the exception's kind.
 * Never the exception's message — it can carry the provider's host.
 */
internal fun episodeLoadFailure(e: Exception): String =
    if (e is HttpClient.HttpStatusException) "HTTP ${e.code}" else e.javaClass.simpleName.ifEmpty { e.javaClass.name }

/** Loads a series' seasons/episodes on demand (Xtream `get_series_info`; Stalker paged
 *  `get_ordered_list&movie_id=` season rows). Episodes carry their season number
 *  directly, so no separate Season rows are needed for browsing. */
class SeriesRepository(
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
    private val xtream: XtreamClient,
    private val userData: tv.own.owntv.core.backup.UserDataResolver,
    private val stalkerClient: StalkerClient,
    private val stalkerAuth: StalkerAuthManager,
) {
    /**
     * Returns true if episodes are available (cached or freshly fetched). Xtream + Stalker.
     *
     * The cache used to be write-once — `episodeCount > 0` meant "done, never ask again" — and since
     * the syncers deliberately don't fetch episodes either, nothing in the app could ever add an
     * episode to a show the user had already opened. New episodes only appeared if the source was
     * deleted and re-added (which cascade-drops the rows). Now the cache expires, and a
     * source sync invalidates it outright.
     */
    suspend fun loadEpisodes(series: SeriesEntity): EpisodeLoad = withContext(Dispatchers.IO) {
        val cachedCount = seriesDao.episodeCount(series.id)
        val hasCache = cachedCount > 0
        val stored = if (hasCache) EpisodeLoad.Ready else EpisodeLoad.Empty
        val source = sourceDao.getById(series.sourceId) ?: return@withContext stored

        // M3U is not affected and deliberately doesn't take this path: its episodes come from
        // the playlist during the sync itself, and `M3uSyncer.flushSeries` folds the episode list
        // into the show's content hash — so a playlist that gains an episode rewrites that show's
        // hierarchy on the next resync. There is nothing to fetch on demand and no cache to expire.
        if (source.type != SourceType.XTREAM && source.type != SourceType.STALKER) return@withContext stored

        // Read the stamp from the database rather than the passed-in entity: callers hold list rows
        // that may have been loaded before a sync invalidated the cache.
        val syncedAt = seriesDao.getSeriesById(series.id)?.episodesSyncedAt ?: series.episodesSyncedAt
        if (!shouldRefreshEpisodes(cachedCount, syncedAt, System.currentTimeMillis())) return@withContext EpisodeLoad.Ready

        val remoteId = series.remoteId
        if (remoteId.isNullOrBlank()) return@withContext stored
        // A failed or empty fetch must never empty a populated show: a provider hiccup would
        // otherwise wipe a season the user was midway through. Leave the stamp alone so the next
        // open retries instead of waiting out the TTL on nothing.
        val fetched = try {
            when (source.type) {
                SourceType.XTREAM -> fetchXtreamEpisodes(series, source, remoteId)
                else -> fetchStalkerEpisodes(series, source, remoteId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "${source.type} episode load failed seriesId=${series.id}", e)
            return@withContext if (hasCache) EpisodeLoad.Ready else EpisodeLoad.Failed(episodeLoadFailure(e))
        }
        if (fetched.isNullOrEmpty()) return@withContext stored
        applyEpisodes(series.id, fetched)
        EpisodeLoad.Ready
    }

    /**
     * Writes a fetched list over the stored one, preserving the row id of every episode that
     * survives — see [planEpisodeMerge]. Rebuilding the rows wholesale would detach watch history,
     * resume positions and next-episode autoplay on every single refresh.
     */
    private suspend fun applyEpisodes(seriesId: Long, fetched: List<EpisodeEntity>) {
        val plan = planEpisodeMerge(seriesDao.episodesBySeriesOnce(seriesId), fetched)
        plan.updates.chunked(CHUNK).forEach { seriesDao.updateEpisodes(it) }
        plan.inserts.chunked(CHUNK).forEach { seriesDao.upsertEpisodes(it) }
        plan.deleteIds.chunked(CHUNK).forEach { seriesDao.deleteEpisodesByIds(it) }
        seriesDao.markEpisodesSynced(seriesId, System.currentTimeMillis())
        android.util.Log.i(
            TAG,
            "episodes seriesId=$seriesId fetched=${fetched.size} new=${plan.inserts.size} " +
                "updated=${plan.updates.size} removed=${plan.deleteIds.size}",
        )
        // Episode rows just appeared — restored/pending episode history and resume can attach now.
        if (plan.inserts.isNotEmpty()) runCatching { userData.resolvePending() }
    }

    private suspend fun fetchXtreamEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): List<EpisodeEntity> {
        val info = xtream.getSeriesInfo(source, remoteId)
        return info.episodes.map { e ->
            EpisodeEntity(
                seriesId = series.id,
                seasonId = null,
                seasonNumber = e.seasonNumber,
                episodeNumber = e.episodeNumber,
                name = e.title,
                streamUrl = xtream.seriesEpisodeUrl(source, e.id, e.containerExt),
                containerExt = e.containerExt,
                remoteId = e.id,
                airDateMs = e.airDateMs,
            )
        }
    }

    /**
     * Stalker: the show's seasons come from paged `get_ordered_list&movie_id=<remoteId>`,
     * each row carrying a `series` array of episode numbers. Every episode stores the SEASON cmd as
     * its streamUrl (the item's identity); the playable per-episode URL is minted at play time via
     * `create_link&type=vod&series=<episode>` (StreamUrlResolver) — never at listing time, the links
     * are short-lived.
     */
    private suspend fun fetchStalkerEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): List<EpisodeEntity>? {
        val mac = source.mac?.let { StalkerClient.canonicalizeMac(it) } ?: return null
        val creds = source.stalkerCredentials(mac)
        if (source.id in vodShowSources) return fetchStalkerVodEpisodes(series, mac, creds, remoteId)
        val seasons = ArrayList<StalkerClient.SeasonItem>()
        try {
            var page = 1
            while (page <= MAX_SEASON_PAGES) {
                val p = stalkerAuth.withAuthRetry(creds) { session ->
                    stalkerClient.getSeriesSeasons(
                        session.apiBase, mac, session.token, creds.userAgent,
                        categoryId = null, movieId = remoteId, page = page,
                    )
                }
                seasons += p.items
                val maxPer = p.maxPageItems.takeIf { it > 0 } ?: p.items.size
                val pages = if (maxPer > 0) (p.totalItems + maxPer - 1) / maxPer else 1
                if (p.items.isEmpty() || page >= pages) break
                page++
            }
        } catch (e: StalkerAuthException) {
            // A portal with no `type=series` module answers `{"js":false}`, which reads as an expired
            // token even after a fresh one. The show may be filed in the VOD module instead; if that
            // does not answer either, the original failure stands.
            return try {
                fetchStalkerVodEpisodes(series, mac, creds, remoteId).also { vodShowSources += source.id }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                throw e
            }
        }
        // No seasons from the series module: the show may be filed in the VOD module instead.
        if (seasons.isEmpty()) {
            return fetchStalkerVodEpisodes(series, mac, creds, remoteId).also { if (it.isNotEmpty()) vodShowSources += source.id }
        }
        val episodes = ArrayList<EpisodeEntity>()
        seasons.forEachIndexed { index, season ->
            val cmd = season.cmd ?: return@forEachIndexed
            val seasonNo = season.seasonNumber ?: (index + 1)
            season.episodes.forEach { epNo ->
                episodes.add(
                    EpisodeEntity(
                        seriesId = series.id,
                        seasonId = null,
                        seasonNumber = seasonNo,
                        episodeNumber = epNo,
                        // The guide has no provider title for these Stalker rows. Keep the persisted
                        // value locale-neutral; Compose renders the episode number when it is blank.
                        name = "",
                        streamUrl = cmd, // season cmd — resolved per-episode at play time
                        containerExt = null,
                        remoteId = "${season.id}:$epNo",
                    )
                )
            }
        }
        android.util.Log.i(
            TAG,
            "stalker episodes seriesId=${series.id} remoteId=$remoteId seasons=${seasons.size} episodes=${episodes.size}",
        )
        return episodes
    }

    /**
     * Stalker, for a show filed in the VOD module (`is_series` on a `type=vod` row, on portals with no
     * `type=series` module): seasons from `type=vod&movie_id=`, then each season's episodes from
     * `…&season_id=`. These rows carry the episode's own title. Each stores a [StalkerClient.vodEpisodeRef];
     * its file is listed, and its URL minted, at play time.
     */
    private suspend fun fetchStalkerVodEpisodes(
        series: SeriesEntity, mac: String, creds: StalkerCredentials, remoteId: String,
    ): List<EpisodeEntity> {
        suspend fun <T> pages(fetch: suspend (Int) -> StalkerClient.Page<T>): List<T> {
            val out = ArrayList<T>()
            var page = 1
            while (page <= MAX_SEASON_PAGES) {
                val p = fetch(page)
                out += p.items
                val maxPer = p.maxPageItems.takeIf { it > 0 } ?: p.items.size
                val total = if (maxPer > 0) (p.totalItems + maxPer - 1) / maxPer else 1
                if (p.items.isEmpty() || page >= total) break
                page++
            }
            return out
        }
        val seasons = pages { page ->
            stalkerAuth.withAuthRetry(creds) { s -> stalkerClient.getVodSeasons(s.apiBase, mac, s.token, creds.userAgent, remoteId, page) }
        }
        val episodes = ArrayList<EpisodeEntity>()
        seasons.forEachIndexed { index, season ->
            val seasonNo = season.number ?: StalkerClient.seasonNumberOf(season.id, season.name) ?: (index + 1)
            val rows = pages { page ->
                stalkerAuth.withAuthRetry(creds) { s ->
                    stalkerClient.getVodEpisodes(s.apiBase, mac, s.token, creds.userAgent, remoteId, season.id, page)
                }
            }
            rows.forEachIndexed { i, ep ->
                episodes.add(
                    EpisodeEntity(
                        seriesId = series.id,
                        seasonId = null,
                        seasonNumber = seasonNo,
                        episodeNumber = ep.number ?: (i + 1),
                        name = ep.title.orEmpty(),
                        streamUrl = StalkerClient.vodEpisodeRef(remoteId, season.id, ep.id),
                        containerExt = null,
                        remoteId = ep.id,
                    )
                )
            }
        }
        android.util.Log.i(
            TAG,
            "stalker vod episodes seriesId=${series.id} remoteId=$remoteId seasons=${seasons.size} episodes=${episodes.size}",
        )
        return episodes
    }

    /**
     * Stalker sources whose shows turned out to be filed in the VOD module. Asking the series module
     * first costs such a portal a refused request and a re-handshake on every show opened; once one
     * show has answered from the VOD module, the rest of the session goes there directly.
     */
    private val vodShowSources: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private companion object {
        const val TAG = "SeriesRepository"

        /** Safety cap on season paging (a show never has hundreds of season pages). */
        const val MAX_SEASON_PAGES = 20

        /** Write batch size for the episode merge. */
        const val CHUNK = 500
    }
}
