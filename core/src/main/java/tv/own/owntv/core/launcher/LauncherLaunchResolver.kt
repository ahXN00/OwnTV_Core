package tv.own.owntv.core.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

class LauncherLaunchResolver(
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val progressDao: ProgressDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) {
    suspend fun resolveLaunch(profileId: Long, deepLink: LauncherDeepLink): LauncherLaunch? = withContext(Dispatchers.IO) {
        val sources = sourceDao.observeForProfile(profileId).first()
        if (sources.isEmpty()) return@withContext null
        val liveIds = sources.filter { it.syncLive }.map { it.id }.toSet()
        val movieIds = sources.filter { it.syncMovies }.map { it.id }.toSet()
        val seriesIds = sources.filter { it.syncSeries }.map { it.id }.toSet()

        when (deepLink) {
            is LauncherDeepLink.Movie -> resolveMovie(profileId, deepLink, movieIds)
            is LauncherDeepLink.Live -> resolveLiveChannel(profileId, deepLink, liveIds)
            LauncherDeepLink.OpenLiveSection -> null
            is LauncherDeepLink.Episode -> resolveEpisode(profileId, deepLink, seriesIds)
        }
    }

    private suspend fun resolveMovie(profileId: Long, deepLink: LauncherDeepLink.Movie, sourceIds: Set<Long>): LauncherLaunch.Movie? {
        val sourceId = deepLink.sourceId
        val remoteId = deepLink.remoteId
        val name = deepLink.name
        val movie = when {
            sourceId != null && !remoteId.isNullOrBlank() -> movieDao.findByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> movieDao.findByName(sourceId, name)
            deepLink.itemId != null -> movieDao.getById(deepLink.itemId)
            else -> null
        } ?: return null
        if (sourceId != null && movie.sourceId != sourceId) return null
        if (movie.sourceId !in sourceIds) return null
        if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(profileId, movie.categoryId, profileDao, categoryDao) || customize.sealsCategory(profileId, movie.categoryId)) return null
        return LauncherLaunch.Movie(movie, progressDao.get(profileId, MediaType.MOVIE, movie.id)?.positionMs ?: 0L)
    }

    private suspend fun resolveLiveChannel(profileId: Long, deepLink: LauncherDeepLink.Live, sourceIds: Set<Long>): LauncherLaunch.Live? {
        val sourceId = deepLink.sourceId
        val remoteId = deepLink.remoteId
        val name = deepLink.name
        val channel = when {
            sourceId != null && !remoteId.isNullOrBlank() -> channelDao.findByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> channelDao.findByName(sourceId, name)
            deepLink.itemId != null -> channelDao.getById(deepLink.itemId)
            else -> null
        } ?: return null
        if (sourceId != null && channel.sourceId != sourceId) return null
        if (channel.sourceId !in sourceIds) return null
        if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(profileId, channel.categoryId, profileDao, categoryDao) || customize.sealsCategory(profileId, channel.categoryId)) return null
        return LauncherLaunch.Live(channel)
    }

    private suspend fun resolveEpisode(
        profileId: Long,
        deepLink: LauncherDeepLink.Episode,
        sourceIds: Set<Long>,
    ): LauncherLaunch? {
        val show = resolveSeries(deepLink, sourceIds) ?: return null
        if (!tv.own.owntv.core.content.AdultCategoryClassifier.allows(profileId, show.categoryId, profileDao, categoryDao) || customize.sealsCategory(profileId, show.categoryId)) return null
        val episodes = orderedEpisodes(show.id)
        if (episodes.isEmpty()) return LauncherLaunch.Series(show)

        val target = resolveEpisodeTarget(profileId, show.id, deepLink, episodes) ?: return LauncherLaunch.Series(show)
        val queue = episodes.filter { it.seasonNumber == target.seasonNumber }.sortedBy { it.episodeNumber }
        val startPosition = progressDao.get(profileId, MediaType.EPISODE, target.id)?.positionMs ?: 0L
        return LauncherLaunch.Episode(show, target, queue.ifEmpty { listOf(target) }, startPosition)
    }

    private suspend fun resolveSeries(deepLink: LauncherDeepLink.Episode, sourceIds: Set<Long>): SeriesEntity? {
        val sourceId = deepLink.seriesSourceId
        val remoteId = deepLink.seriesRemoteId
        val name = deepLink.seriesName
        val show = when {
            sourceId != null && !remoteId.isNullOrBlank() -> seriesDao.findSeriesByRemote(sourceId, remoteId)
            sourceId != null && !name.isNullOrBlank() -> seriesDao.findSeriesByName(sourceId, name)
            deepLink.seriesItemId != null -> seriesDao.getSeriesById(deepLink.seriesItemId)
            else -> null
        } ?: return null
        if (sourceId != null && show.sourceId != sourceId) return null
        if (show.sourceId !in sourceIds) return null
        return show
    }

    private suspend fun resolveEpisodeTarget(
        profileId: Long,
        seriesId: Long,
        deepLink: LauncherDeepLink.Episode,
        episodes: List<EpisodeEntity>,
    ): EpisodeEntity? {
        val season = deepLink.season
        val episodeNo = deepLink.episode
        val exact = when {
            !deepLink.episodeRemoteId.isNullOrBlank() -> seriesDao.findEpisodeByRemote(seriesId, deepLink.episodeRemoteId)
            season != null && episodeNo != null -> seriesDao.findEpisodeByNumber(seriesId, season, episodeNo)
            deepLink.episodeItemId != null -> seriesDao.getEpisodeById(deepLink.episodeItemId)
            else -> null
        }
        if (exact != null) return exact

        val fallbackStartIndex = when {
            season != null && episodeNo != null ->
                episodes.indexOfFirst { candidate ->
                    candidate.seasonNumber > season ||
                        (candidate.seasonNumber == season && candidate.episodeNumber > episodeNo)
                }.takeIf { it >= 0 } ?: 0
            else -> 0
        }

        val candidates = if (fallbackStartIndex == 0) episodes else episodes.drop(fallbackStartIndex)
        return candidates.firstOrNull { episode ->
            val progress = progressDao.get(profileId, MediaType.EPISODE, episode.id)
            progress == null || !isCompleted(progress.positionMs, progress.durationMs)
        } ?: if (fallbackStartIndex == 0) null else episodes.firstOrNull { episode ->
            val progress = progressDao.get(profileId, MediaType.EPISODE, episode.id)
            progress == null || !isCompleted(progress.positionMs, progress.durationMs)
        }
    }

    private suspend fun orderedEpisodes(seriesId: Long): List<EpisodeEntity> =
        seriesDao.episodesBySeries(seriesId).first().sortedWith(tv.own.owntv.core.content.EpisodeOrder.episodes)

    private fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * 0.95f).toLong()
}
