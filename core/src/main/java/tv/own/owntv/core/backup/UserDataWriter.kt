package tv.own.owntv.core.backup

import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/**
 * The one place a user deletes their own data — an unfavorite, "Remove from history", clearing a
 * resume position, taking an item out of a custom category.
 *
 * It exists because a deletion has to be *recorded*, not merely performed (see
 * [tv.own.owntv.core.database.entity.UserDataTombstoneEntity]): local sync merges, so a row that is
 * simply gone here looks to the other device like a row this one has not heard about yet, and comes
 * straight back on the next sync. Every function below writes the marker and does the delete in one
 * transaction, so the two can never come apart.
 *
 * **Only user actions belong here.** The orphan purges after a re-sync and the profile cascade go on
 * calling the DAOs directly and deliberately: those rows are being tidied up, not thrown away, and
 * turning "the playlist was refreshed" into "delete this everywhere" would lose real data.
 */
class UserDataWriter(
    private val db: OwnTVDatabase,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val customCategoryDao: CustomCategoryDao,
    private val userData: UserDataResolver,
) {

    /** Unfavorite one item. */
    suspend fun removeFavorite(profileId: Long, type: MediaType, itemId: Long) = db.transaction {
        userData.recordDeletion(profileId, "fav", type, itemId)
        favoriteDao.remove(profileId, type, itemId)
    }

    /** "Remove from history" for one item. */
    suspend fun removeHistory(profileId: Long, type: MediaType, itemId: Long) = db.transaction {
        userData.recordDeletion(profileId, "his", type, itemId)
        historyDao.remove(profileId, type, itemId)
    }

    /** Forget one item's resume position. */
    suspend fun clearProgress(profileId: Long, type: MediaType, itemId: Long) = db.transaction {
        userData.recordDeletion(profileId, "prog", type, itemId)
        progressDao.clear(profileId, type, itemId)
    }

    /**
     * "Remove from history" on a show: its own row, its episodes' rows, and their resume positions —
     * which is what the caller already did by hand, now with each deletion recorded.
     */
    suspend fun removeSeriesHistory(profileId: Long, seriesId: Long) = db.transaction {
        userData.recordDeletion(profileId, "his", MediaType.SERIES, seriesId)
        historyDao.episodeIdsInHistory(profileId, seriesId).forEach {
            userData.recordDeletion(profileId, "his", MediaType.EPISODE, it)
        }
        progressDao.episodeIdsWithProgress(profileId, seriesId).forEach {
            userData.recordDeletion(profileId, "prog", MediaType.EPISODE, it)
        }
        historyDao.remove(profileId, MediaType.SERIES, seriesId)
        historyDao.removeSeriesEpisodes(profileId, seriesId)
        progressDao.clearSeriesEpisodes(profileId, seriesId)
        userData.pruneTombstones()
    }

    /** Take one item out of one custom category. */
    suspend fun removeCustomCategoryMember(
        profileId: Long,
        type: MediaType,
        contextKey: String,
        itemId: Long,
    ) = db.transaction {
        userData.recordDeletion(profileId, "member", type, itemId, contextKey = contextKey)
        customCategoryDao.deleteItem(profileId, type, contextKey, itemId)
    }

    /**
     * "Clear watch history" — everything, or one media type, plus the resume positions that feed
     * Home's Continue watching.
     *
     * Every row is described before it goes, which on a large library is thousands of small reads.
     * That is the price of the deletion actually sticking on the user's other device, and it is a
     * once-in-a-while action taken from a settings screen, not something on a hot path.
     */
    suspend fun clearHistory(profileId: Long, type: MediaType? = null) = db.transaction {
        val history = if (type == null) historyDao.getForProfile(profileId) else historyDao.getForProfileType(profileId, type)
        history.forEach { userData.recordDeletion(profileId, "his", it.mediaType, it.itemId) }
        // Which resume positions go with it: everything, or the type's own — where a series means its
        // episodes, because a show has no progress row of its own and Live has none at all.
        val progressType = when (type) {
            MediaType.SERIES -> MediaType.EPISODE
            MediaType.MOVIE -> MediaType.MOVIE
            else -> null
        }
        val progress = when {
            type == null -> progressDao.getForProfile(profileId)
            progressType != null -> progressDao.getForProfileType(profileId, progressType)
            else -> emptyList()
        }
        progress.forEach { userData.recordDeletion(profileId, "prog", it.mediaType, it.itemId) }
        if (type == null) {
            historyDao.clear(profileId)
            progressDao.clearProfile(profileId)
        } else {
            historyDao.clearType(profileId, type)
            progressType?.let { progressDao.clearProfileType(profileId, it) }
        }
        userData.pruneTombstones()
    }
}
