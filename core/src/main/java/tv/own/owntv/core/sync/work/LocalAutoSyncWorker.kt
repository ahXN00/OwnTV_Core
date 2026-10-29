package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tv.own.owntv.core.sync.local.LocalAutoSyncOutcome
import tv.own.owntv.core.sync.local.LocalAutoSyncStore
import tv.own.owntv.core.sync.local.LocalSyncManager

/**
 * The automatic pass itself.
 *
 * It runs with the app's own dependencies handed to it by [KoinWorkerFactory], so the pass uses
 * exactly the same manager the screen does — one set of rules about what a container is and how a
 * merge behaves, whichever way the data was asked for.
 *
 * Which devices take part is decided here, from the settings as they are when the job actually
 * starts, not from what was true when it was queued: a job can outlive the setting that asked for
 * it, and a device turned off in the meantime must not sync because a timer remembered it.
 */
class LocalAutoSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val store: LocalAutoSyncStore,
    private val sync: LocalSyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = store.current()
        val due = when (inputData.getString(LocalAutoSyncScheduler.KEY_MODE)) {
            // The one-off run is the "immediately" devices' turn, whoever they are now.
            LocalAutoSyncScheduler.MODE_STARTUP -> settings.startups()
            // The timer wakes for the shortest interval any device wanted; each device still only
            // runs when its own interval has elapsed.
            else -> settings.dueAt(System.currentTimeMillis())
        }
        if (due.isEmpty()) {
            Log.i(TAG, "skipped: no device is due")
            return Result.success()
        }
        val report = sync.runAutoSync(due)
        // Each device keeps its own history, because each device has its own schedule: one phone
        // that was switched off must not make the next tick think the television is not due yet.
        report.devices.forEach { device ->
            store.recordRun(
                deviceId = device.deviceId,
                at = report.finishedAt,
                success = device.outcome == LocalAutoSyncOutcome.SYNCED,
            )
        }
        val synced = report.synced
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
