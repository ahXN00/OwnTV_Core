package tv.own.owntv.core.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import tv.own.owntv.core.database.dao.CatalogBackfillDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * "The user is looking at *this* category — fill it first." (Plan N1c.)
 *
 * A lazily-added Stalker catalogue arrives over about sixteen minutes, top-down in the portal's own
 * category order. That is the right default, and for almost every category it is invisible: the
 * setup sweep already stored page 1, so every list opens with something in it.
 *
 * What it does not handle is the user walking straight into a category near the *bottom* of that
 * order. Its first page is there, but the rest is behind fifteen other categories and, if the drain
 * happens to be resting between passes, behind a fifteen-minute timer as well.
 *
 * N1c was originally scoped as a read-through paging layer — fetch the missing pages synchronously
 * while the user scrolls. That was abandoned once N1d landed, because it would have meant a
 * RemoteMediator-shaped hook across every query shape (All / Favourites / History / Custom / Folder
 * × rating / dateAdded / playlist / manual) when only `LiveKey.Folder` maps onto a portal category
 * at all. This is the small version of the same idea: do not fetch differently, just **re-order what
 * is already going to be fetched**, and wake the drain if it is asleep.
 *
 * Deliberately a hint, never a guarantee. If the category is already complete this does nothing; the
 * drain still yields to playback, still resumes from its cursor, and still reconciles against the
 * portal's own totals. Nothing here can make the catalogue wrong — only sooner.
 */
class CatalogPriority(
    private val catalogBackfillDao: CatalogBackfillDao,
    private val scheduler: CatalogSyncScheduler,
) {

    /** The category the drain should serve next, or null when it should follow provider order. */
    data class Request(val sourceId: Long, val mediaType: MediaType, val categoryRemoteId: String)

    private val _requested = MutableStateFlow<Request?>(null)

    /** Read by the drain between category batches. */
    fun current(): Request? = _requested.value

    /**
     * The user opened a category. Put it at the front of the drain's queue, and — if it still owes
     * pages — wake the drain now rather than letting it wait out its retry delay.
     *
     * Safe to call on every category open: a complete category costs one indexed lookup and does
     * nothing else.
     */
    suspend fun requestFirst(categoryDbId: Long) {
        val row = runCatching { catalogBackfillDao.forCategoryDbId(categoryDbId) }.getOrNull()
        if (row == null || row.done) {
            // Nothing outstanding here: either this playlist was never lazily added, or this
            // category is already whole. Leave any existing request alone.
            return
        }
        _requested.value = Request(row.sourceId, row.mediaType, row.categoryRemoteId)
        Log.i(
            TAG,
            "priority category sourceId=${row.sourceId} type=${row.mediaType} cat=${row.categoryRemoteId} " +
                "pagesLeft=${row.lastPage - row.nextPage + 1}",
        )
        // KEEP inside the scheduler, so this cannot restart a pass that is already running or
        // already waiting for playback to stop.
        scheduler.enqueueCatalogBackfill(row.sourceId)
    }

    /** The user left the category. The drain goes back to provider order. */
    fun clear() {
        _requested.value = null
    }

    private companion object {
        const val TAG = "CatalogPriority"
    }
}
