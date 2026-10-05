package tv.own.owntv.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import tv.own.owntv.core.database.dao.ReminderDao

/**
 * One alarm per reminder, re-armed after a reboot — the same clock as `RecordingScheduler`, for the
 * same reasons: WorkManager is inexact, `SCHEDULE_EXACT_ALARM` is user-revocable so it is consulted on
 * every arm, and an inexact alarm is the fallback.
 */
class ReminderScheduler(
    private val context: Context,
    private val reminderDao: ReminderDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    private fun canBeExact(): Boolean = when {
        alarms == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> alarms.canScheduleExactAlarms()
        else -> true
    }

    /** Drop the reminders whose programme has ended and arm the rest. Idempotent: same request code. */
    suspend fun rearmAll() {
        val now = clock()
        reminderDao.all().forEach { reminder ->
            val at = ReminderSchedule.wakeAtFor(reminder, now)
            if (at == null) cancel(reminder.id) else arm(reminder.id, at)
        }
        reminderDao.deleteEnded(now)
    }

    fun arm(id: Long, atMs: Long) {
        val manager = alarms ?: return
        val intent = pendingIntent(id) ?: return
        runCatching {
            if (canBeExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
            }
        }.onFailure {
            // Permission revoked between the check and the call; the next rearmAll arms it inexactly.
            android.util.Log.w(TAG, "could not arm reminder alarm id=$id: ${it.message}")
        }
    }

    fun cancel(id: Long) {
        val manager = alarms ?: return
        pendingIntent(id)?.let { manager.cancel(it) }
    }

    private fun pendingIntent(id: Long): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_REMINDER_DUE
            // In the data, not an extra: PendingIntent equality ignores extras.
            data = "owntv://reminder/$id".toUri()
        }
        return runCatching {
            PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "ReminderScheduler"
    }
}
