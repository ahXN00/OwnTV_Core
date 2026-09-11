package tv.own.owntv.core.live

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Which query backs a Live TV rail selection, and which channels survive the profile's customizations.
 *
 * Split out of the view models: these are pure functions of their arguments — every piece of state they
 * once read off the view model (the profile's folder context keys, the contexts that carry a manual
 * order) is now passed in, so there is nothing here to get out of step with a tune.
 */

/** The paging query for one rail selection. [contextKey] is the folder's stable customization key and
 *  [hasManualOrder] says whether that folder actually carries manual-order rows — see the C3 fast path. */
fun livePagingSource(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    query: String,
    sort: SettingsRepository.SortMode,
    channelDao: ChannelDao,
    customCategoryDao: CustomCategoryDao,
    contextKey: (Long) -> String?,
    hasManualOrder: (String) -> Boolean,
): PagingSource<Int, ChannelEntity> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    val playlist = sort == SettingsRepository.SortMode.PLAYLIST
    return if (query.isBlank()) {
        when (key) {
            LiveKey.All -> if (playlist) channelDao.pagingAllOriginal(ids) else channelDao.pagingAll(ids)
            LiveKey.Favorites -> channelDao.pagingFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, ids)
            LiveKey.History -> channelDao.pagingHistory(profileId, ids)
            LiveKey.Catchup -> if (playlist) channelDao.pagingCatchupOriginal(ids) else channelDao.pagingCatchup(ids)
            is LiveKey.Custom -> if (playlist) customCategoryDao.pagingChannels(profileId, key.id, ids) else customCategoryDao.pagingChannelsAlpha(profileId, key.id, ids)
            is LiveKey.Folder -> {
                val ctxKey = contextKey(key.id) ?: ""
                // C3 fast path: no manual order in this folder → the plain indexed query has
                // the identical (sortOrder, name) order without the join-sort.
                if (!hasManualOrder(ctxKey)) {
                    if (playlist) channelDao.pagingByCategory(key.id) else channelDao.pagingByCategoryAlpha(key.id)
                } else {
                    if (playlist) channelDao.pagingByCategoryManual(key.id, profileId, ctxKey) else channelDao.pagingByCategoryManualAlpha(key.id, profileId, ctxKey)
                }
            }
        }
    } else {
        when (key) {
            LiveKey.All -> channelDao.searchAll(query, ids)
            LiveKey.Favorites -> channelDao.searchFavorites(query, profileId, ids)
            LiveKey.History -> channelDao.searchHistory(query, profileId, ids)
            LiveKey.Catchup -> channelDao.searchCatchup(query, ids)
            is LiveKey.Custom -> customCategoryDao.searchChannels(query, profileId, key.id, ids)
            is LiveKey.Folder -> channelDao.searchInCategory(query, key.id)
        }
    }
}

/** The live row count for one rail selection. */
fun liveCountFlow(
    key: LiveKey,
    profileId: Long,
    sourceIds: List<Long>,
    hiddenCats: Set<Long>,
    channelDao: ChannelDao,
    customCategoryDao: CustomCategoryDao,
): Flow<Int> {
    val ids = sourceIds.ifEmpty { listOf(-1L) }
    return when (key) {
        LiveKey.All -> if (hiddenCats.isEmpty()) channelDao.countAll(ids) else channelDao.countAllExcluding(ids, hiddenCats.toList())
        LiveKey.Favorites -> channelDao.countFavorites(profileId, ids)
        LiveKey.History -> channelDao.countHistory(profileId, ids)
        LiveKey.Catchup -> channelDao.observeCatchupCount(ids)
        is LiveKey.Custom -> customCategoryDao.countMembers(profileId, MediaType.LIVE, key.id, ids)
        is LiveKey.Folder -> channelDao.countByCategory(key.id)
    }
}

/** A channel the user has not hidden, in a category they have not hidden. */
fun isChannelVisible(ch: ChannelEntity, cust: SectionCustomizations, hiddenCats: Set<Long>): Boolean =
    CustomizeKeys.channel(ch) !in cust.hiddenItems &&
        (ch.categoryId == null || ch.categoryId !in hiddenCats)
