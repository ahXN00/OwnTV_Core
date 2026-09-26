package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.WatchSession
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncActivityTracker
import tv.own.owntv.core.sync.SyncManager
import tv.own.owntv.core.timeshift.TimeshiftManager

/**
 * Finishes a Stalker catalogue that setup deliberately left incomplete (plan N1d).
 *
 * The portals this exists for cap a page at ~14 items, so a first import that walked every page
 * would hold the user at a progress bar for four and a half minutes. N1b stops after the first page
 * of every category — every list is immediately browsable — and writes down what it skipped. This
 * worker drains that plan afterwards, invisibly.
 *
 * Three properties it has to have, and where each comes from:
 *
 * - **Resumable.** The drain persists its page cursor after every window, so a reboot, a killed
 *   process or a cancelled worker resumes at the next unfetched page. Re-running a window is free:
 *   `sourceId + remoteId` is unique and the insert ignores conflicts.
 * - **It yields.** Background paging competes with playback for the same portal, and several of
 *   these accounts allow exactly one connection. Between windows the drain checks [WatchSession]
 *   (fullscreen playback) and [OpenStreamRegistry] (Multiview tiles and recordings) and steps aside
 *   the moment either says this source is in use, coming back later.
 * - **It reconciles.** The finish line is the sum of the portal's own *per-category* totals, which
 *   the drain reads back from the plan rows. (Not the synthetic "*" total — that counts items in no
 *   category at all, and a drain chasing it would never finish. See the plan's §4C.)
 */
class CatalogBackfillWorker(
    context: Context,
    params: WorkerParameters,
    private val syncManager: SyncManager,
    private val openStreams: OpenStreamRegistry,
    private val syncActivity: SyncActivityTracker,
    private val scheduler: CatalogSyncScheduler,
    private val importFinalizer: ImportFinalizer,
    private val watchSession: WatchSession,
    private val timeshift: TimeshiftManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId < 0) return Result.success()

        val outcome = runCatching {
            syncManager.backfillCatalog(sourceId) { shouldYield(sourceId) }
        }.getOrElse {
            // Never Result.failure(): an unfinished catalogue is not an error the user can act on,
            // and the plan rows survive, so a retry is always the right answer.
            Log.w(TAG, "backfill failed sourceId=$sourceId — retrying later", it)
            return Result.retry()
        }

        Log.i(
            TAG,
            "backfill pass sourceId=$sourceId pages=${outcome.pagesFetched} inserted=${outcome.inserted} " +
                "failedCategories=${outcome.failedCategories} paused=${outcome.paused} " +
                "pending=${outcome.pendingCategories}",
        )
        if (!outcome.complete) {
            // Paused for playback, or a category hit a page the portal would not serve. Either way
            // the plan still holds the position, so come back after a pause long enough that a
            // yielding drain is not re-woken into the same stream it just stepped away from.
            scheduler.enqueueCatalogBackfill(sourceId, initialDelayMinutes = RETRY_DELAY_MINUTES)
            return Result.success()
        }
        // `pagesFetched > 0` is what distinguishes "this pass finished the catalogue" from "there
        // was nothing to do" — a worker woken against a source with no plan also reports complete,
        // and must not re-run either of the two jobs below.
        if (outcome.pagesFetched > 0) onCatalogueComplete(sourceId)
        return Result.success()
    }

    /**
     * The two things that were measured against a 3%-full catalogue and have to happen again now
     * that it is whole (plan N1f-1 and N1f-2).
     *
     * Both are deliberately best-effort: the catalogue is already correct and browsable at this
     * point, so neither failing is worth a retry that would re-walk nothing.
     */
    private suspend fun onCatalogueComplete(sourceId: Long) {
        // N1f-1. `ensureContentIndexes` runs ANALYZE, and on a lazy add it last ran when the source
        // held a few per cent of its final rows. Stale statistics make SQLite ignore the very
        // indexes this function creates and full-sort instead — its own comment says so.
        runCatching { importFinalizer.ensureContentIndexes() }
            .onFailure { Log.w(TAG, "post-backfill index refresh failed sourceId=$sourceId", it) }

        // N1f-2. Trending is a snapshot, and a snapshot of a part-filled catalogue sits behind a
        // multi-day timer. CatalogSyncWorker now declines to take one while a drain is outstanding,
        // which makes this the only place it can be taken for a lazily-added source.
        runCatching { scheduler.enqueueTrendingRefresh(sourceId) }
            .onFailure { Log.w(TAG, "post-backfill trending enqueue failed sourceId=$sourceId", it) }

        Log.i(TAG, "backfill complete sourceId=$sourceId — indexes analyzed, trending enqueued")
    }

    /**
     * True while the user (or a recording) is using this playlist's connections, or while an
     * ordinary sync of it is running — a drain must never be the reason a stream fails to open.
     *
     * All four clauses are needed and none is redundant. [WatchSession] is fullscreen playback,
     * which never claims a connection; [OpenStreamRegistry] is Multiview and recordings, which do;
     * [TimeshiftManager] is a live channel's saved copy, whose download can outlive the player screen;
     * [SyncActivityTracker] is an ordinary sync of the same source. The watch clause exists because
     * the registry alone silently covered none of the common case — see N1f-3.
     */
    private fun shouldYield(sourceId: Long): Boolean =
        watchSession.isWatching(sourceId) ||
            openStreams.openOn(sourceId).total > 0 ||
            timeshift.isSaving(sourceId) ||
            syncActivity.active.value.containsKey(sourceId)

    companion object {
        private const val TAG = "CatalogBackfillWorker"
        const val KEY_SOURCE_ID = "sourceId"
        const val WORK_TAG = "catalog-backfill"

        /** Long enough that a drain yielding to a stream is not woken into the middle of it. */
        private const val RETRY_DELAY_MINUTES = 15L

        fun workName(sourceId: Long) = "catalog-backfill-source-$sourceId"
    }
}
