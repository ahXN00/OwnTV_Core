package tv.own.owntv.core.recording

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tv.own.owntv.core.i18n.LocaleStore

/**
 * Runs whatever is due to record, in a **`mediaPlayback`** foreground service so it keeps going once
 * the user leaves OwnTV — and so it is not subject to the six-hours-a-day cap Android 15 puts on
 * `dataSync`, which a DVR would hit (§1.4). See [RecordingNotifications].
 *
 * One of these at a time (unique work, KEEP). It lives until nothing is recording and nothing is due;
 * the scheduler wakes it again at the next start time.
 *
 * **Network constraint is `CONNECTED`, never `UNMETERED`.** Downloads may wait for Wi-Fi because a
 * film is still there tomorrow; a live programme is not, so a recording runs on whatever connection
 * exists. There is deliberately no "record over Wi-Fi only" here.
 */
class RecordingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: RecordingEngine,
    private val localeStore: LocaleStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(RecordingNotifications.foregroundInfo(applicationContext, null, localeStore))
        var lastTick = 0L
        engine.drainQueue { progress ->
            val now = System.currentTimeMillis()
            if (now - lastTick > NOTIFICATION_INTERVAL_MS) {
                lastTick = now
                runCatching {
                    setForegroundAsync(
                        RecordingNotifications.foregroundInfo(applicationContext, progress, localeStore),
                    )
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "owntv-recordings"
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        /**
         * Make sure anything due is being recorded. KEEP, not REPLACE: a worker already running will
         * pick the new row up on its next poll, and replacing it would cut off whatever it is
         * currently writing.
         */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
