package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.model.MediaType

/**
 * Drops live rows whose entry the playlist now tags as a movie or series (for example a VOD list
 * that gained `tvg-type="movie"` after it was first imported). The normal live prune can't do it:
 * it only runs when the parse still contains live entries, and the catalog-shrink guard would stop
 * it anyway when most of the list moved. Neither concern applies here, because each removed row's
 * entry is still in the playlist, just under Movies or Series.
 */
internal object M3uReclassifiedLive {
    private const val TAG = "M3uReclassified"

    /** Stored live keys that this parse delivered as VOD instead of live. */
    fun staleKeys(storedLive: Collection<String>, seenLive: Set<String>, seenAsVod: Set<String>): List<String> =
        storedLive.filter { it !in seenLive && it in seenAsVod }

    /**
     * Deletes the reclassified live rows of [sourceId]. When the parse had no live entries and no
     * live row is left, the source's live categories are empty and go too. Returns rows removed.
     */
    suspend fun prune(
        sourceId: Long,
        seenLive: Set<String>,
        seenAsVod: Set<String>,
        channelDao: ChannelDao,
        categoryDao: CategoryDao,
    ): Int {
        if (seenAsVod.isEmpty()) return 0
        val start = SystemClock.elapsedRealtime()
        val stale = staleKeys(channelDao.remoteIdsForSource(sourceId), seenLive, seenAsVod)
        if (stale.isEmpty()) return 0
        stale.chunked(SyncSupport.QUERY_CHUNK).forEach { channelDao.deleteByRemoteIds(sourceId, it) }
        if (seenLive.isEmpty() && channelDao.remoteIdsForSource(sourceId).isEmpty()) {
            categoryDao.remoteIdsForSource(sourceId, MediaType.LIVE)
                .chunked(SyncSupport.QUERY_CHUNK)
                .forEach { categoryDao.deleteByRemoteIds(sourceId, MediaType.LIVE, it) }
        }
        Log.i(TAG, "M3U reclassified live prune sourceId=$sourceId removed=${stale.size} ms=${SystemClock.elapsedRealtime() - start}")
        return stale.size
    }
}
