package tv.own.owntv.core.sync.work

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
 * The ongoing notification that keeps [EpgSyncWorker] alive as a foreground service.
 *
 * Without it a large guide can never finish. A plain background worker is frozen by the OEM battery
 * managers the moment the screen goes off — on a ColorOS phone, 29 seconds after the display dims,
 * with the process suspended *and* its network blacklisted — so the download dies mid-parse and the
 * next run starts again from the first byte. The rows already written survive, but `setSynced` never
 * runs, so the source stays stale and the whole thing loops forever. The sibling workers
 * ([tv.own.owntv.core.download.DownloadNotifications],
 * [tv.own.owntv.core.recording.RecordingNotifications]) have always done this; the guide was the one
 * long job that did not.
 */
internal object EpgSyncNotifications {

    private const val CHANNEL_ID = "owntv_epg_sync"

    /**
     * One notification per source, because two feeds can sync at once — the work is unique per
     * source, not per app, unlike the download queue. Sharing one id would let the second sync's
     * notification replace the first's and take the first service's justification with it.
     *
     * The id is folded into a small range rather than used raw: a source id is a database key, is
     * routinely negative for portal-derived guides, and has no business deciding a notification id's
     * magnitude. A collision needs two sources whose ids are 1024 apart syncing simultaneously, and
     * costs one notification, not a crash.
     */
    private const val NOTIFICATION_ID_BASE = 4203
    private const val ID_SPREAD = 0x3FF

    @Volatile
    private var lastChannelLocaleKey: String? = null
    private val channelLock = Any()

    fun notificationId(sourceId: Long): Int = NOTIFICATION_ID_BASE + (sourceId.hashCode() and ID_SPREAD)

    /**
     * [programmes] is what the parse has seen so far. There is no total — an XMLTV feed does not say
     * how long it is — so the bar is indeterminate and the count is the honest measure of progress.
     */
    fun foregroundInfo(
        context: Context,
        sourceId: Long,
        sourceName: String?,
        programmes: Int,
        localeStore: LocaleStore,
    ): ForegroundInfo {
        val localized = localizedContext(context, localeStore)
        ensureChannel(context, localized)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(localized.getString(R.string.settings_syncing_guide))
            // The source's own name, which is the user's own words and never translated. Blank only
            // for a source that vanished between enqueue and run, and then the title stands alone.
            .setContentText(sourceName.orEmpty().ifBlank { localized.getString(R.string.app_name) })
            // The same sentence the EPG source row shows, so the two agree and nothing new needed
            // translating. Left off entirely until the parse has counted something.
            .setSubText(
                programmes.takeIf { it > 0 }?.let {
                    localized.resources.getQuantityString(
                        R.plurals.settings_epg_sources_status_count_programmes,
                        it,
                        it,
                    )
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()

        val id = notificationId(sourceId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun ensureChannel(context: Context, localized: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val effectiveLocale = localized.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        val key = "$effectiveLocale:${localized.getString(R.string.common_nav_guide)}"
        if (key == lastChannelLocaleKey) return
        synchronized(channelLock) {
            if (key == lastChannelLocaleKey) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localized.getString(R.string.common_nav_guide),
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
