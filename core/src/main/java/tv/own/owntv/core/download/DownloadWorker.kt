package tv.own.owntv.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import tv.own.owntv.core.customize.LockedNotificationTitles
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.util.LatestOnly

/**
 * Runs the download queue as a foreground service so transfers keep going once the user leaves
 * OwnTV — an app-scoped coroutine died with the process the moment Android reclaimed it.
 *
 * There is exactly one of these at a time (unique work, KEEP), and it lives until the queue is
 * empty, which is what preserves the one-at-a-time semantics downloads always had.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: DownloadEngine,
    private val localeStore: LocaleStore,
    private val lockedTitles: LockedNotificationTitles,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        setForeground(DownloadNotifications.foregroundInfo(applicationContext, null, localeStore))
        // One update at a time, only the newest waiting: the lock check can be slow, and an older one finishing
        // last would put a stale title or progress back in the shade.
        val notification = LatestOnly<DownloadProgress>(this) { progress ->
            val showTitle = !lockedTitles.hidesDownload(progress.id)
            runCatching {
                setForegroundAsync(
                    DownloadNotifications.foregroundInfo(applicationContext, progress, localeStore, showTitle),
                )
            }
        }
        var lastTick = 0L
        engine.drainQueue { progress ->
            // The transfer reports twice a second; the notification does not need that.
            val now = System.currentTimeMillis()
            if (now - lastTick > NOTIFICATION_INTERVAL_MS) {
                lastTick = now
                notification.offer(progress)
            }
        }
        notification.close()
        Result.success()
    }

    companion object {
        const val WORK_NAME = "owntv-downloads"
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        /**
         * Make sure the queue is being drained. KEEP, not REPLACE: if a worker is already running,
         * it will pick up the newly queued row itself when it finishes the current one.
         *
         * [wifiOnly] is the user's "download over Wi-Fi only" choice, enforced by WorkManager itself
         * rather than by a check here: an UNMETERED constraint also *stops* a running transfer the
         * moment the phone falls off Wi-Fi, which a check at start could never do.
         */
        fun kick(context: Context, wifiOnly: Boolean = false, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
