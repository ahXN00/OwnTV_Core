package tv.own.owntv.core.content

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Which query backs a Movies or Series list selection — the VOD twin of `livePagingSource`.
 *
 * Movies and Series select from the same rail model Live TV uses ([LiveKey]), so one selection type
 * covers all three sections. Catch-up is the exception and degrades to All: channels keep archives,
 * films do not.
 *
 * [contextKey] is the folder's stable customization key and [hasManualOrder] says whether that folder
 * actually carries manual-order rows — without one, the plain indexed query has the identical
 * (sortOrder, name) order and skips the join-sort.
 */
fun moviePagingSource(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    query: String,
    sort: SettingsRepository.SortMode,
    movieDao: MovieDao,
    customCategoryDao: CustomCategoryDao,
    contextKey: (Long) -> String?,
    hasManualOrder: (String) -> Boolean,
): PagingSource<Int, MovieEntity> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    val rating = sort == SettingsRepository.SortMode.RATING
    val dateAdded = sort == SettingsRepository.SortMode.DATE_ADDED
    val playlist = sort == SettingsRepository.SortMode.PLAYLIST
    return if (query.isBlank()) {
        when (key) {
            LiveKey.All, LiveKey.Catchup -> when {
                rating -> movieDao.pagingAllRating(ids)
                dateAdded -> movieDao.pagingAllDateAdded(ids)
                playlist -> movieDao.pagingAllOriginal(ids)
                else -> movieDao.pagingAll(ids)
            }
            LiveKey.Favorites -> movieDao.pagingFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, ids)
            LiveKey.History -> movieDao.pagingHistory(profileId, ids)
            is LiveKey.Custom ->
                if (playlist) customCategoryDao.pagingMovies(profileId, key.id, ids)
                else customCategoryDao.pagingMoviesAlpha(profileId, key.id, ids)
            is LiveKey.Folder -> {
                val ctxKey = contextKey(key.id).orEmpty()
                when {
                    rating -> movieDao.pagingByCategoryRating(key.id)
                    dateAdded -> movieDao.pagingByCategoryDateAdded(key.id)
                    !hasManualOrder(ctxKey) -> if (playlist) movieDao.pagingByCategory(key.id) else movieDao.pagingByCategoryAlpha(key.id)
                    playlist -> movieDao.pagingByCategoryManual(key.id, profileId, ctxKey)
                    else -> movieDao.pagingByCategoryManualAlpha(key.id, profileId, ctxKey)
                }
            }
        }
    } else {
        when (key) {
            LiveKey.All, LiveKey.Catchup ->
                if (dateAdded) movieDao.searchAllDateAdded(query, ids) else movieDao.searchAll(query, ids)
            LiveKey.Favorites -> movieDao.searchFavorites(query, profileId, ids)
            LiveKey.History -> movieDao.searchHistory(query, profileId, ids)
            is LiveKey.Custom -> customCategoryDao.searchMovies(query, profileId, key.id, ids)
            is LiveKey.Folder ->
                if (dateAdded) movieDao.searchInCategoryDateAdded(query, key.id)
                else movieDao.searchInCategory(query, key.id)
        }
    }
}

/** [moviePagingSource] for shows. */
fun seriesPagingSource(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    query: String,
    sort: SettingsRepository.SortMode,
    seriesDao: SeriesDao,
    customCategoryDao: CustomCategoryDao,
    contextKey: (Long) -> String?,
    hasManualOrder: (String) -> Boolean,
): PagingSource<Int, SeriesEntity> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    val rating = sort == SettingsRepository.SortMode.RATING
    val dateAdded = sort == SettingsRepository.SortMode.DATE_ADDED
    val playlist = sort == SettingsRepository.SortMode.PLAYLIST
    return if (query.isBlank()) {
        when (key) {
            LiveKey.All, LiveKey.Catchup -> when {
                rating -> seriesDao.pagingAllRating(ids)
                dateAdded -> seriesDao.pagingAllDateAdded(ids)
                playlist -> seriesDao.pagingAllOriginal(ids)
                else -> seriesDao.pagingAll(ids)
            }
            LiveKey.Favorites -> seriesDao.pagingFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, ids)
            LiveKey.History -> seriesDao.pagingHistory(profileId, ids)
            is LiveKey.Custom ->
                if (playlist) customCategoryDao.pagingSeries(profileId, key.id, ids)
                else customCategoryDao.pagingSeriesAlpha(profileId, key.id, ids)
            is LiveKey.Folder -> {
                val ctxKey = contextKey(key.id).orEmpty()
                when {
                    rating -> seriesDao.pagingByCategoryRating(key.id)
                    dateAdded -> seriesDao.pagingByCategoryDateAdded(key.id)
                    !hasManualOrder(ctxKey) -> if (playlist) seriesDao.pagingByCategory(key.id) else seriesDao.pagingByCategoryAlpha(key.id)
                    playlist -> seriesDao.pagingByCategoryManual(key.id, profileId, ctxKey)
                    else -> seriesDao.pagingByCategoryManualAlpha(key.id, profileId, ctxKey)
                }
            }
        }
    } else {
        when (key) {
            LiveKey.All, LiveKey.Catchup ->
                if (dateAdded) seriesDao.searchAllDateAdded(query, ids) else seriesDao.searchAll(query, ids)
            LiveKey.Favorites -> seriesDao.searchFavorites(query, profileId, ids)
            LiveKey.History -> seriesDao.searchHistory(query, profileId, ids)
            is LiveKey.Custom -> customCategoryDao.searchSeries(query, profileId, key.id, ids)
            is LiveKey.Folder ->
                if (dateAdded) seriesDao.searchInCategoryDateAdded(query, key.id)
                else seriesDao.searchInCategory(query, key.id)
        }
    }
}

/** The film count for one selection. */
fun movieCountFlow(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    hiddenCats: Set<Long>,
    movieDao: MovieDao,
    customCategoryDao: CustomCategoryDao,
): Flow<Int> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    return when (key) {
        LiveKey.All, LiveKey.Catchup ->
            if (hiddenCats.isEmpty()) movieDao.countAll(ids)
            else movieDao.countAllExcluding(ids, hiddenCats.toList())
        LiveKey.Favorites -> movieDao.countFavorites(profileId, ids)
        LiveKey.History -> movieDao.countHistory(profileId, ids)
        is LiveKey.Custom -> customCategoryDao.countMembers(profileId, MediaType.MOVIE, key.id, ids)
        is LiveKey.Folder -> movieDao.countByCategory(key.id)
    }
}

/** [movieCountFlow] for shows. */
fun seriesCountFlow(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    hiddenCats: Set<Long>,
    seriesDao: SeriesDao,
    customCategoryDao: CustomCategoryDao,
): Flow<Int> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    return when (key) {
        LiveKey.All, LiveKey.Catchup ->
            if (hiddenCats.isEmpty()) seriesDao.countAll(ids)
            else seriesDao.countAllExcluding(ids, hiddenCats.toList())
        LiveKey.Favorites -> seriesDao.countFavorites(profileId, ids)
        LiveKey.History -> seriesDao.countHistory(profileId, ids)
        is LiveKey.Custom -> customCategoryDao.countMembers(profileId, MediaType.SERIES, key.id, ids)
        is LiveKey.Folder -> seriesDao.countByCategory(key.id)
    }
}
