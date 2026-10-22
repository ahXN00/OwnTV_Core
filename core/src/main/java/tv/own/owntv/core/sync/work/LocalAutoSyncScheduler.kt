package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import tv.own.owntv.core.sync.local.LocalAutoSyncConfig
import tv.own.owntv.core.sync.local.LocalAutoSyncTiming

/**
 * Puts the auto-sync schedule in front of WorkManager.
 *
 * WorkManager rather than an alarm because a television is not a phone: it is asleep more than it
 * is awake, and the only thing that may wake it is the system's own scheduler. The two kinds of
 * work are kept under different names so that changing the timing cannot cancel a run that is
 * already on its way — a cancellation and an enqueue racing each other used to be how "the sync
 * never happens" happened.
 */
class LocalAutoSyncScheduler(private val context: Context) {

    /**
     * Applies [config]'s schedule: nothing at all when it is off, a periodic run on a timer, or a
     * single run that waits for the network when the user asked for "immediately".
     */
    fun schedule(config: LocalAutoSyncConfig) {
        cancel()
        if (!config.enabled) return
        when (val timing = config.timing) {
            LocalAutoSyncTiming.OFF -> Unit
            // "Immediately" is not a period: it is a request that runs at the first opportunity.
            // Whoever triggers it — the app on start, and again when the network comes back — is
            // what decides when that opportunity is.
            LocalAutoSyncTiming.STARTUP -> trigger()
            else -> {
                val interval = timing.intervalMs ?: return
                enqueuePeriodic(interval)
            }
        }
    }

    /**
     * A single run, as soon as the network allows.
     *
     * The network constraint is what makes "immediately" mean what it says: the work waits for a
     * connected network rather than failing, so a television that was woken up before the router
     * syncs the moment the router is up.
     */
    fun trigger() {
        val request = OneTimeWorkRequestBuilder<LocalAutoSyncWorker>()
            .setConstraints(networkConstraint())
            .addTag(TAG)
            .build()
        // KEEP rather than REPLACE: a run that is already on its way is not cancelled half way
        // through a merge, and a run that is already waiting is not joined by a second one. A
        // television that opens the app twice does not sync twice.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** The timer. UPDATE rather than KEEP, so a changed interval takes effect at once. */
    private fun enqueuePeriodic(intervalMs: Long) {
        val request = PeriodicWorkRequestBuilder<LocalAutoSyncWorker>(intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Stops both kinds of work. Turning auto-sync off has to mean it stops, not that it is left. */
    fun cancel() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(WORK_NAME_NOW)
        manager.cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        /** Tag every auto-sync job carries, so the jobs can be found in WorkManager's dumps. */
        const val TAG = "local-auto-sync"

        /** The one-off run ("immediately", and the button on the settings screen). */
        const val WORK_NAME_NOW = "owntv-local-auto-sync-now"

        /** The timer (every six hours, daily, weekly). */
        const val WORK_NAME_PERIODIC = "owntv-local-auto-sync-periodic"
    }
}
