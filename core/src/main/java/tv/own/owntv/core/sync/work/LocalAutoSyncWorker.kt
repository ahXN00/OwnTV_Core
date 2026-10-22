package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tv.own.owntv.core.sync.local.LocalAutoSyncStore
import tv.own.owntv.core.sync.local.LocalSyncManager

/**
 * The automatic pass itself.
 *
 * It runs with the app's own dependencies handed to it by [KoinWorkerFactory], so the pass uses
 * exactly the same manager the screen does — one set of rules about what a container is and how a
 * merge behaves, whichever way the data was asked for.
 */
class LocalAutoSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val store: LocalAutoSyncStore,
    private val sync: LocalSyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = store.current()
        // The job can outlive the setting that asked for it: a run queued an hour ago may start
        // after the user turned auto-sync off. Off means off, even here.
        if (!config.enabled) {
            Log.i(TAG, "skipped: auto-sync is off")
            return Result.success()
        }
        val report = sync.runAutoSync(config)
        val synced = report.synced
        store.recordRun(report.finishedAt, success = synced.isNotEmpty())
        Log.i(
            TAG,
            "run finished: ${synced.size}/${report.devices.size} devices synced" +
                if (synced.isEmpty()) "" else " (${synced.sumOf { it.items }} items)",
        )
        // A device that was not answering is not a failed job. The other one may have synced
        // perfectly, and the next tick is when the missing one gets another turn — retrying now
        // would only mean asking a phone that is switched off over and over.
        return Result.success()
    }

    private companion object {
        const val TAG = "LocalAutoSync"
    }
}
