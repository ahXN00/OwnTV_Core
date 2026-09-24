package tv.own.owntv.core.download

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

/** The ongoing notification that keeps [DownloadWorker] alive as a foreground service. */
internal object DownloadNotifications {

    private const val CHANNEL_ID = "owntv_downloads"
    private const val NOTIFICATION_ID = 4201

    @Volatile
    private var lastChannelLocaleKey: String? = null
    private val channelLock = Any()

    fun foregroundInfo(context: Context, progress: DownloadProgress?, localeStore: LocaleStore): ForegroundInfo {
        val localized = localizedContext(context, localeStore)
        ensureChannel(context, localized)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(progress?.title ?: localized.getString(R.string.app_name))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null && progress.totalBytes > 0) {
            val percent = ((progress.downloadedBytes * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context, localized: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val effectiveLocale = localized.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        val key = "$effectiveLocale:${localized.getString(R.string.common_nav_downloads)}"
        if (key == lastChannelLocaleKey) return
        synchronized(channelLock) {
            if (key == lastChannelLocaleKey) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localized.getString(R.string.common_nav_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                },
            )
            lastChannelLocaleKey = key
        }
    }

    private fun localizedContext(context: Context, localeStore: LocaleStore): Context =
        AppLocale.wrap(context, localeStore.currentTag.value)
}
