package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The phone's hourly automatic-sync check. One periodic work, on an unmetered network with the battery
 * not low; the worker does nothing unless an agreement is due, so most runs cost a DataStore read.
 */
class LocalAutoSyncScheduler(private val context: Context) {

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<LocalAutoSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val TAG = "local-auto-sync"
        const val WORK_NAME = "owntv-local-auto-sync-hourly"
    }
}
