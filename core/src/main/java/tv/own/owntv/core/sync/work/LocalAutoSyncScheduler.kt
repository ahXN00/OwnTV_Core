package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import tv.own.owntv.core.sync.local.LocalAutoSyncSettings

/**
 * Puts the auto-sync schedule in front of WorkManager.
 *
 * WorkManager rather than an alarm because a television is not a phone: it is asleep more than it
 * is awake, and the only thing that may wake it is the system's own scheduler.
 *
 * The schedule is derived from [LocalAutoSyncSettings] as a whole rather than from one device's
 * timing, because the timer is shared: it wakes at the shortest interval any device asked for, and
 * the worker skips the devices whose own interval has not elapsed. That keeps one timer to cancel
 * instead of one per device, and it means a device switched from weekly to daily cannot leave a
 * second, forgotten timer behind.
 *
 * The two kinds of work are kept under different names so that changing a timing cannot cancel a
 * run that is already on its way — a cancellation and an enqueue racing each other used to be how
 * "the sync never happens" happened.
 */
class LocalAutoSyncScheduler(private val context: Context) {

    /**
     * Applies the schedule the settings ask for: nothing at all when every device is off, a timer
     * at the shortest interval any device wanted, and a single run that waits for the network when
     * some device asked for "immediately".
     */
    fun schedule(settings: LocalAutoSyncSettings) {
        val interval = settings.periodicIntervalMs
        if (interval == null) cancelPeriodic() else enqueuePeriodic(interval)
        // "Immediately" is not a period: it is a request that runs at the first opportunity.
        // Whoever triggers it — the app on start, and again when the network comes back — is what
        // decides when that opportunity is. When no device asks for it any more, nothing is left
        // waiting; when one does, a run already on its way is not cancelled by this call.
        if (settings.hasStartup) trigger() else cancelNow()
    }

    /**
     * A single run of the devices that asked for "immediately", as soon as the network allows.
     *
     * The network constraint is what makes "immediately" mean what it says: the work waits for a
     * connected network rather than failing, so a television that was woken up before the router
     * syncs the moment the router is up.
     */
    fun trigger() {
        val request = OneTimeWorkRequestBuilder<LocalAutoSyncWorker>()
            .setConstraints(networkConstraint())
            .setInputData(workDataOf(KEY_MODE to MODE_STARTUP))
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
            .setInputData(workDataOf(KEY_MODE to MODE_PERIODIC))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    private fun cancelNow() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_NOW)
    }

    /** Stops both kinds of work. Turning auto-sync off has to mean it stops, not that it is left. */
    fun cancel() {
        cancelNow()
        cancelPeriodic()
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

        /** Which of the two kinds of run this is, so the worker knows who is due. */
        const val KEY_MODE = "mode"
        const val MODE_STARTUP = "startup"
        const val MODE_PERIODIC = "periodic"
    }
}
