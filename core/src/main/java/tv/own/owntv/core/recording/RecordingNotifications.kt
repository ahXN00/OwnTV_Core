package tv.own.owntv.core.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import tv.own.owntv.core.R
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

/**
 * The ongoing notification that keeps [RecordingWorker] alive as a foreground service.
 *
 * **Typed `mediaPlayback`, not `dataSync`** — and that is the whole reason this exists rather than
 * reusing [tv.own.owntv.core.download.DownloadNotifications]. An app targeting Android 15 gets six
 * hours of `dataSync` foreground service per day and then `Service.onTimeout()` fires; `mediaPlayback`
 * has no such cap, and Google's own service-type table names its use case as "Android TV DVR".
 * A DVR built on the download worker's type would silently start failing for heavy users.
 */
internal object RecordingNotifications {

    private const val CHANNEL_ID = "owntv_recordings"
    private const val NOTIFICATION_ID = 4202

    @Volatile
    private var lastChannelLocaleKey: String? = null
    private val channelLock = Any()

    fun foregroundInfo(context: Context, progress: RecordingProgress?, localeStore: LocaleStore): ForegroundInfo {
        val localized = localizedContext(context, localeStore)
        ensureChannel(context, localized)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(localized.getString(R.string.recording_notification_title))
            .setContentText(progress?.title ?: localized.getString(R.string.app_name))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // A recording has no percentage worth showing: it ends on the clock, not on a byte count.
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context, localized: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val effectiveLocale = localized.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        val name = localized.getString(R.string.recording_notification_title)
        val key = "$effectiveLocale:$name"
        if (key == lastChannelLocaleKey) return
        synchronized(channelLock) {
            if (key == lastChannelLocaleKey) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            lastChannelLocaleKey = key
        }
    }

    private fun localizedContext(context: Context, localeStore: LocaleStore): Context =
        AppLocale.wrap(context, localeStore.currentTag.value)
}
