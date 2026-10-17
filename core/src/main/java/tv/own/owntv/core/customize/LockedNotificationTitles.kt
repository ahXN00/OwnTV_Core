package tv.own.owntv.core.customize

import android.util.Log
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.RecordingDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.model.MediaType

/**
 * Whether a running download's or recording's notification may name it. The notification sits outside
 * the app, past the PIN, so a title from a locked category is left out even while the PIN has the
 * category open in the app; the notification then just says it is working. The app's own status pill
 * asks with `anySession = false`: it names the title once the PIN has opened its category.
 *
 * A row whose film, episode or channel no longer exists (a re-sync gave it a new id) cannot be tied to
 * a category, so its title stays hidden while the profile has a lock that is not open (see
 * [ItemCategory]). Any failure to look the lock up hides the title.
 */
class LockedNotificationTitles(
    private val customize: CustomizationStore,
    private val downloadDao: DownloadDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val recordingDao: RecordingDao,
    private val channelDao: ChannelDao,
) {
    suspend fun hidesDownload(id: Long, anySession: Boolean = true): Boolean = hides("download $id", anySession) {
        val row = downloadDao.getById(id) ?: return@hides null
        val category = when (row.mediaType) {
            MediaType.MOVIE -> movieDao.getById(row.itemId)?.let { ItemCategory.Found(it.categoryId) }
            MediaType.EPISODE -> seriesDao.getEpisodeById(row.itemId)
                ?.let { seriesDao.getSeriesById(it.seriesId) }?.let { ItemCategory.Found(it.categoryId) }
            else -> ItemCategory.Found(null)
        }
        row.profileId to (category ?: ItemCategory.Unresolved)
    }

    suspend fun hidesRecording(id: Long, anySession: Boolean = true): Boolean = hides("recording $id", anySession) {
        val row = recordingDao.getById(id) ?: return@hides null
        row.profileId to (channelDao.getById(row.channelId)?.let { ItemCategory.Found(it.categoryId) } ?: ItemCategory.Unresolved)
    }

    /** [lookup] gives the row's profile and its item's category, or null when the row is gone. */
    private suspend fun hides(what: String, anySession: Boolean, lookup: suspend () -> Pair<Long, ItemCategory>?): Boolean = try {
        lookup()?.let { (profileId, item) -> customize.sealsItem(profileId, item, anySession) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what: unable to check its category lock, so its title stays hidden", e)
        true
    }

    private companion object {
        const val TAG = "LockedNotificationTitles"
    }
}
