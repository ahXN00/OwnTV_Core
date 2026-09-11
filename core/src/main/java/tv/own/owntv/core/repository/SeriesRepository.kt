package tv.own.owntv.core.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StalkerCredentials
import tv.own.owntv.core.stalker.stalkerCredentials

/** Loads a series' seasons/episodes on demand (Xtream `get_series_info`; Stalker paged
 *  `get_ordered_list&movie_id=` season rows, plan D-2). Episodes carry their season number
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
     * deleted and re-added (which cascade-drops the rows). That's S8; the cache now expires, and a
     * source sync invalidates it outright.
     */
    suspend fun loadEpisodes(series: SeriesEntity): Boolean = withContext(Dispatchers.IO) {
        val cachedCount = seriesDao.episodeCount(series.id)
        val hasCache = cachedCount > 0
        val source = sourceDao.getById(series.sourceId) ?: return@withContext hasCache

        // M3U is not affected by S8 and deliberately doesn't take this path: its episodes come from
        // the playlist during the sync itself, and `M3uSyncer.flushSeries` folds the episode list
        // into the show's content hash — so a playlist that gains an episode rewrites that show's
        // hierarchy on the next resync. There is nothing to fetch on demand and no cache to expire.
        if (source.type != SourceType.XTREAM && source.type != SourceType.STALKER) return@withContext hasCache

        // Read the stamp from the database rather than the passed-in entity: callers hold list rows
        // that may have been loaded before a sync invalidated the cache.
        val syncedAt = seriesDao.getSeriesById(series.id)?.episodesSyncedAt ?: series.episodesSyncedAt
        if (!shouldRefreshEpisodes(cachedCount, syncedAt, System.currentTimeMillis())) return@withContext true

        val remoteId = series.remoteId
        if (remoteId.isNullOrBlank()) return@withContext hasCache
        val fetched = when (source.type) {
            SourceType.XTREAM -> fetchXtreamEpisodes(series, source, remoteId)
            else -> fetchStalkerEpisodes(series, source, remoteId)
        }
        // A failed or empty fetch must never empty a populated show: a provider hiccup would
        // otherwise wipe a season the user was midway through. Leave the stamp alone so the next
        // open retries instead of waiting out the TTL on nothing.
        if (fetched.isNullOrEmpty()) return@withContext hasCache
        applyEpisodes(series.id, fetched)
        true
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

    private suspend fun fetchXtreamEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): List<EpisodeEntity>? = try {
        val info = xtream.getSeriesInfo(source, remoteId)
        info.episodes.map { e ->
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
    } catch (e: Exception) {
        android.util.Log.w(TAG, "xtream episode load failed seriesId=${series.id}", e)
        null
    }

    /**
     * Stalker (plan D-2): the show's seasons come from paged `get_ordered_list&movie_id=<remoteId>`,
     * each row carrying a `series` array of episode numbers. Every episode stores the SEASON cmd as
     * its streamUrl (the item's identity); the playable per-episode URL is minted at play time via
     * `create_link&type=vod&series=<episode>` (StreamUrlResolver) — never at listing time, the links
     * are short-lived.
     */
    private suspend fun fetchStalkerEpisodes(series: SeriesEntity, source: SourceEntity, remoteId: String): List<EpisodeEntity>? = try {
        val mac = source.mac?.let { StalkerClient.canonicalizeMac(it) } ?: return null
        val creds = source.stalkerCredentials(mac)
        val seasons = ArrayList<StalkerClient.SeasonItem>()
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
        episodes
    } catch (e: Exception) {
        android.util.Log.w(TAG, "stalker episode load failed seriesId=${series.id}", e)
        null
    }

    private companion object {
        const val TAG = "SeriesRepository"

        /** Safety cap on season paging (a show never has hundreds of season pages). */
        const val MAX_SEASON_PAGES = 20

        /** Write batch size for the episode merge. */
        const val CHUNK = 500
    }
}
