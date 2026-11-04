package tv.own.owntv.core.launcher

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import java.nio.ByteBuffer
import java.security.MessageDigest

class LauncherRecommendationPlanner(
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val progressDao: ProgressDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) {
    companion object {
        private const val WATCH_NEXT_MIN_POSITION_MS = 10_000L
        private const val WATCH_NEXT_COMPLETE_FRACTION = 0.95f
        private const val CONTINUATION_MAX_ITEMS = 10
    }

    suspend fun buildSnapshot(profileId: Long): LauncherSnapshot = withContext(Dispatchers.IO) {
        LauncherSnapshot(profileId = profileId, continuationItems = buildContinuationItems(profileId))
    }

    suspend fun buildContinuationItems(profileId: Long): List<LauncherContinuationItem> = withContext(Dispatchers.IO) {
        val sources = sourcesForProfile(profileId)
        if (sources.isEmpty()) return@withContext emptyList()
        val movieSourceIds = sources.filter { it.syncMovies }.map { it.id }.toSet()
        val seriesSourceIds = sources.filter { it.syncSeries }.map { it.id }.toSet()
        if (movieSourceIds.isEmpty() && seriesSourceIds.isEmpty()) return@withContext emptyList()

        val allProgress = progressDao.getAllOnce().filter { it.profileId == profileId }
        val out = ArrayList<LauncherContinuationItem>()

        // Everything below used to re-read the same rows once per progress entry. The visibility check
        // re-loads the profile on EVERY call, and the episode/series pair for a show is fetched twice —
        // once to pick the latest progress, then again to build the item. On a long watch history that
        // repetition, not the work itself, was most of this function's cost (150-590ms on a TCL, the
        // slowest single step of the Home load). These three caches are scoped to one build, so a
        // profile edit or a category change still takes effect on the next refresh.
        val visibleByCategory = HashMap<Long?, Boolean>()
        suspend fun categoryVisible(categoryId: Long?): Boolean =
            visibleByCategory.getOrElse(categoryId) {
                isCategoryVisibleToProfile(profileId, categoryId).also { visibleByCategory[categoryId] = it }
            }

        val episodeById = HashMap<Long, EpisodeEntity?>()
        suspend fun episode(id: Long): EpisodeEntity? =
            if (episodeById.containsKey(id)) episodeById[id]
            else seriesDao.getEpisodeById(id).also { episodeById[id] = it }

        val showById = HashMap<Long, tv.own.owntv.core.database.entity.SeriesEntity?>()
        suspend fun show(id: Long): tv.own.owntv.core.database.entity.SeriesEntity? =
            if (showById.containsKey(id)) showById[id]
            else seriesDao.getSeriesById(id).also { showById[id] = it }

        for (progress in allProgress) {
            when (progress.mediaType) {
                MediaType.MOVIE -> {
                    val movie = movieDao.getById(progress.itemId) ?: continue
                    if (movie.sourceId !in movieSourceIds) continue
                    if (!categoryVisible(movie.categoryId)) continue
                    if (!eligibleForWatchNext(progress.positionMs, progress.durationMs)) continue
                    out += movieItem(movie, progress)
                }

                MediaType.EPISODE -> Unit
                MediaType.LIVE, MediaType.SERIES -> Unit
            }
        }

        val latestBySeries = LinkedHashMap<Long, PlaybackProgressEntity>()
        for (progress in allProgress) {
            if (progress.mediaType != MediaType.EPISODE) continue
            val episode = episode(progress.itemId) ?: continue
            val show = show(episode.seriesId) ?: continue
            if (show.sourceId !in seriesSourceIds) continue
            if (!categoryVisible(show.categoryId)) continue
            val current = latestBySeries[episode.seriesId]
            if (current == null || progress.updatedAt >= current.updatedAt) {
                latestBySeries[episode.seriesId] = progress
            }
        }
        // One full episode list per show, and a show with saved progress usually has hundreds of rows.
        // Fetched in sequence these stacked up into the slowest part of the Home load; fetched together
        // they overlap, which WAL allows. Order is preserved because the results are re-read in the
        // original iteration order below.
        val episodeLists = latestBySeries.map { (seriesId, progress) ->
            progress to async { orderedEpisodes(seriesId) }
        }
        for ((progress, listAsync) in episodeLists) {
            val episode = episode(progress.itemId) ?: continue
            val show = show(episode.seriesId) ?: continue
            val episodes = listAsync.await()
            val currentIndex = episodes.indexOfFirst { it.id == episode.id }
            val complete = isCompleted(progress)
            if (!complete && !eligibleForWatchNext(progress.positionMs, progress.durationMs)) continue
            if (complete && currentIndex == episodes.lastIndex) continue
            out += episodeItem(show, episode, progress, episodes, currentIndex, complete)
        }
        out.sortedByDescending { it.lastEngagementAt }.take(CONTINUATION_MAX_ITEMS)
    }

    suspend fun sourcesForProfile(profileId: Long): List<tv.own.owntv.core.database.entity.SourceEntity> =
        sourceDao.observeForProfile(profileId).first()

    suspend fun sourceIdsForProfile(profileId: Long): Set<Long> = sourcesForProfile(profileId).map { it.id }.toSet()

    suspend fun isVisibleToProfile(profileId: Long, sourceId: Long): Boolean =
        sourceIdsForProfile(profileId).contains(sourceId)

    /** Section-aware visibility: Off sections stay out of Watch Next / launcher surfaces. */
    suspend fun isVisibleToProfile(profileId: Long, sourceId: Long, section: MediaType): Boolean {
        val source = sourcesForProfile(profileId).firstOrNull { it.id == sourceId } ?: return false
        return when (section) {
            MediaType.LIVE -> source.syncLive
            MediaType.MOVIE -> source.syncMovies
            MediaType.SERIES, MediaType.EPISODE -> source.syncSeries
        }
    }

    suspend fun isCategoryVisibleToProfile(profileId: Long, categoryId: Long?): Boolean =
        tv.own.owntv.core.content.AdultCategoryClassifier.allows(profileId, categoryId, profileDao, categoryDao) &&
            !customize.sealsCategory(profileId, categoryId, anySession = true)

    /**
     * The one-shot query, not `episodesBySeries(...).first()`. Both run the same SQL with the same
     * ORDER BY, but the Flow form registers an invalidation observer, waits for the first emission and
     * tears the observer down again — per series, every time. Home builds this for every show with
     * saved progress, so that setup cost was paid repeatedly on the launch path for a value nobody
     * observes afterwards.
     */
    suspend fun orderedEpisodes(seriesId: Long): List<EpisodeEntity> =
        seriesDao.episodesBySeriesOnce(seriesId).sortedWith(tv.own.owntv.core.content.EpisodeOrder.episodes)

    fun movieStableKey(movie: MovieEntity): String =
        "movie:${movie.sourceId}:${movie.remoteId ?: movie.name}"

    fun episodeStableKey(show: SeriesEntity, episode: EpisodeEntity): String =
        "episode:${show.sourceId}:${show.remoteId ?: show.name}:${episode.remoteId ?: "${episode.seasonNumber}-${episode.episodeNumber}"}"

    fun liveStableKeyString(channel: ChannelEntity): String =
        "live:${channel.sourceId}:${channel.remoteId ?: channel.name}"

    fun movieStableKeyHash(movie: MovieEntity): Long = stableHash64(movieStableKey(movie))

    fun episodeStableKeyHash(show: SeriesEntity, episode: EpisodeEntity): Long =
        stableHash64(episodeStableKey(show, episode))

    fun liveStableKey(channel: ChannelEntity): Long = stableHash64(liveStableKeyString(channel))

    fun eligibleForWatchNext(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= WATCH_NEXT_MIN_POSITION_MS && durationMs > 0 && !isCompleted(positionMs, durationMs)

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * WATCH_NEXT_COMPLETE_FRACTION).toLong()

    fun isCompleted(progress: PlaybackProgressEntity): Boolean =
        isCompleted(progress.positionMs, progress.durationMs)

    private fun movieItem(movie: MovieEntity, progress: PlaybackProgressEntity): LauncherContinuationItem {
        val durationMs = progress.durationMs.takeIf { it > 0 } ?: movie.durationSecs?.times(1000L) ?: 0L
        return LauncherContinuationItem(
            kind = LauncherContinuationKind.MOVIE,
            sourceItemId = movie.id,
            targetItemId = movie.id,
            stableKey = movieStableKey(movie),
            title = movie.name,
            subtitle = movie.year?.toString(),
            posterUrl = movie.posterUrl,
            playbackUri = LauncherDeepLink.Movie(
                sourceId = movie.sourceId,
                remoteId = movie.remoteId,
                name = movie.name,
                itemId = movie.id,
            ).toUri(),
            lastEngagementAt = progress.updatedAt,
            durationMs = durationMs,
            positionMs = progress.positionMs,
            watchNextType = LauncherWatchNextType.CONTINUE,
            year = movie.year,
        )
    }

    private fun episodeItem(
        show: SeriesEntity,
        episode: EpisodeEntity,
        progress: PlaybackProgressEntity,
        episodes: List<EpisodeEntity>,
        currentIndex: Int,
        complete: Boolean,
    ): LauncherContinuationItem {
        val target = if (complete && currentIndex >= 0) episodes.getOrNull(currentIndex + 1) else episode
        val watchNextType = if (complete && target != null && target.id != episode.id) LauncherWatchNextType.NEXT else LauncherWatchNextType.CONTINUE
        val sourceEpisode = episode
        val targetEpisode = target ?: episode
        val durationMs = if (complete && target != null && target.id != episode.id) {
            target.durationSecs?.times(1000L) ?: progress.durationMs
        } else {
            progress.durationMs.takeIf { it > 0 } ?: episode.durationSecs?.times(1000L) ?: 0L
        }
        val positionMs = if (watchNextType == LauncherWatchNextType.NEXT) 0L else progress.positionMs
        return LauncherContinuationItem(
            kind = LauncherContinuationKind.EPISODE,
            sourceItemId = sourceEpisode.id,
            targetItemId = targetEpisode.id,
            stableKey = episodeStableKey(show, sourceEpisode),
            title = targetEpisode.name.ifBlank { show.name },
            // Subtitle is rendered by TvHomeRepository with the active Android locale. Keep the
            // planner's result semantic so this data layer never bakes English UI copy into a row.
            subtitle = null,
            posterUrl = show.posterUrl,
            playbackUri = LauncherDeepLink.Episode(
                seriesSourceId = show.sourceId,
                seriesRemoteId = show.remoteId,
                seriesName = show.name,
                episodeRemoteId = targetEpisode.remoteId,
                season = targetEpisode.seasonNumber,
                episode = targetEpisode.episodeNumber,
                seriesItemId = show.id,
                episodeItemId = targetEpisode.id,
            ).toUri(),
            lastEngagementAt = progress.updatedAt,
            durationMs = durationMs,
            positionMs = positionMs,
            watchNextType = watchNextType,
            containerTitle = show.name,
            seasonNumber = targetEpisode.seasonNumber,
            episodeNumber = targetEpisode.episodeNumber,
        )
    }

    private fun stableHash64(value: String): Long = ByteBuffer.wrap(sha256Bytes(value)).long

    private fun sha256Bytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
