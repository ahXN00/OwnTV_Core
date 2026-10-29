package tv.own.owntv.core.sports

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tv.own.owntv.core.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState

/**
 * The host app's part in goal alerts — assigned in its `onCreate` **before Koin starts**, like
 * [AppIconSwitcher.mainActivityClass]. The phone sets [backgroundAllowed]; the television never does,
 * so it schedules nothing in the background even when a profile synced from the phone has "alerts
 * when OwnTV is closed" on (a TV that is off has nobody to tell).
 */
object MatchAlertHost {
    @Volatile var backgroundAllowed: Boolean = false

    /**
     * Asks Android for notification permission. The app assigns it from its activity (only an activity
     * can ask); core calls it once, when goal alerts are turned on ([SportsAlertsSetup.enable]).
     */
    @Volatile var requestNotificationPermission: (() -> Unit)? = null
}

/** The three notification channels and every sports notification. */
class MatchNotifications(private val context: Context, private val localeStore: LocaleStore) {

    private fun localized(): Context = AppLocale.wrap(context, localeStore.currentTag.value)

    fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** (Re)creates the channels in the app's language; Android keeps the user's per-channel choices. */
    fun ensureChannels(l: Context = localized()) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Notification channels exist only from Android 8 (API 26); on 7.x notifications work without them.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_GOALS, l.getString(R.string.sports_channel_goal_alerts), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_LIVE, l.getString(R.string.sports_channel_live_match), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_REMINDERS, l.getString(R.string.sports_channel_match_reminders), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun post(alerts: List<MatchAlert>) {
        if (alerts.isEmpty() || !canPost()) return
        val l = localized()
        ensureChannels(l)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (a in alerts) {
            val m = a.match
            val score = scoreLine(l, m)
            val pair = pair(l, m)
            val title = when (a.kind) {
                MatchAlert.Kind.GOAL -> l.getString(R.string.sports_alert_goal, score)
                MatchAlert.Kind.WICKET -> l.getString(R.string.sports_alert_wicket, score)
                MatchAlert.Kind.RED_CARD -> l.getString(R.string.sports_alert_red_card, score)
                MatchAlert.Kind.HALF_TIME -> l.getString(R.string.sports_alert_half_time, score)
                MatchAlert.Kind.FULL_TIME -> l.getString(R.string.sports_alert_full_time, score)
                MatchAlert.Kind.KICKOFF -> l.getString(R.string.sports_alert_kickoff, pair)
                MatchAlert.Kind.POSTPONED -> l.getString(
                    if (m.state == MatchState.CANCELLED) R.string.sports_alert_cancelled else R.string.sports_alert_postponed, pair,
                )
                MatchAlert.Kind.CORRECTED -> l.getString(R.string.sports_alert_corrected, score)
            }
            val who = listOfNotNull(a.player, m.clock?.display?.takeIf { it.isNotBlank() }).joinToString(" ")
            val text = if (who.isEmpty()) m.leagueName else l.getString(R.string.sports_alert_detail, who, m.leagueName)
            val n = NotificationCompat.Builder(context, CHANNEL_GOALS)
                .setSmallIcon(R.drawable.owntv_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(openMatch(m))
                .setGroup(m.key)
                .build()
            notify(manager, "${m.key}#${a.kind}#$score".hashCode(), n)
        }
    }

    /** The match reminder: "Kick-off in 10 min: Frankfurt – Köln" / "On Sky Sport Bundesliga 1 and 6 more". */
    fun postReminder(match: Match, leadMinutes: Int, channelNames: List<String>) {
        if (!canPost()) return
        val l = localized()
        ensureChannels(l)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val text = when (channelNames.size) {
            0 -> match.leagueName
            1 -> l.getString(R.string.sports_reminder_on, channelNames[0])
            else -> l.resources.getQuantityString(R.plurals.sports_reminder_on_more, channelNames.size - 1, channelNames[0], channelNames.size - 1)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(l.resources.getQuantityString(R.plurals.sports_reminder_title, leadMinutes, leadMinutes, pair(l, match)))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openMatch(match))
            .build()
        notify(manager, "${match.key}#reminder".hashCode(), n)
    }

    /** The live score card: the score, the minute, Watch and Stop updates. Ongoing, silent. */
    fun liveCard(match: Match, stop: PendingIntent): Notification {
        val l = localized()
        ensureChannels(l)
        return NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(scoreLine(l, match))
            .setContentText(listOfNotNull(match.clock?.display?.takeIf { it.isNotBlank() } ?: match.statusText, match.leagueName).joinToString(" · "))
            .setOngoing(match.state == MatchState.IN || match.state == MatchState.PRE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openMatch(match))
            .addAction(0, l.getString(R.string.content_key_watch), openMatch(match))
            .addAction(0, l.getString(R.string.sports_action_stop_updates), stop)
            .build()
    }

    private fun notify(manager: NotificationManager, id: Int, n: Notification) {
        runCatching { manager.notify(TAG_ALERT, id, n) }
    }

    /** Into the app at this match — through the enabled launcher alias, never MainActivity itself. */
    private fun openMatch(match: Match): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(AppIconSwitcher.launchComponent(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MATCH_KEY, match.key)
        return PendingIntent.getActivity(context, match.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        /** The extra an alert's tap carries into the app: the match's [Match.key]. */
        const val EXTRA_MATCH_KEY = "tv.own.owntv.extra.SPORTS_MATCH"

        const val CHANNEL_GOALS = "owntv_sports_goals"
        const val CHANNEL_LIVE = "owntv_sports_live"
        const val CHANNEL_REMINDERS = "owntv_sports_reminders"
        private const val TAG_ALERT = "owntv-sports"

        private fun side(m: Match, home: Boolean) = (if (home) m.home else m.away)?.team?.shortName.orEmpty()

        fun pair(l: Context, m: Match): String =
            if (m.competitors.size < 2) m.title else l.getString(R.string.sports_pair, side(m, true), side(m, false))

        fun scoreLine(l: Context, m: Match): String =
            if (m.competitors.size < 2) m.title
            else l.getString(R.string.sports_score_line, side(m, true), m.home?.score?.text ?: "0", m.away?.score?.text ?: "0", side(m, false))
    }
}
