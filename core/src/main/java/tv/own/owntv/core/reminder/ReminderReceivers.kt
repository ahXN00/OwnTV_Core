package tv.own.owntv.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** A reminder's moment has come: refresh the due list the apps' prompt watches. */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER_DUE) return
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { GlobalContext.get().get<ReminderManager>().onAlarm() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMINDER_DUE = "tv.own.owntv.core.action.REMINDER_DUE"
    }
}

/**
 * After a reboot, after the app is replaced, and when the exact-alarm permission changes (revoking it
 * cancels every exact alarm): re-arm. Same reasons as `RecordingBootReceiver`.
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { GlobalContext.get().get<ReminderScheduler>().rearmAll() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
