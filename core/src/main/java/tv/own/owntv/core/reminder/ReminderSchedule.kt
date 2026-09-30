package tv.own.owntv.core.reminder

import tv.own.owntv.core.database.entity.ReminderEntity

/**
 * Arithmetic only — no `AlarmManager`, no database, no clock of its own — so every rule is unit-tested
 * (shaped after `RecordingSchedule`).
 */
object ReminderSchedule {

    /** The mockup's "Remind me (5 min before)". */
    const val DEFAULT_LEAD_MINUTES = 5

    /** The *Reminder time* setting's choices: at the start, 1 or 5 minutes before. */
    val LEAD_CHOICES = listOf(0, 1, 5)

    /** When the alarm for [reminder] should fire, or null once its programme has ended. */
    fun wakeAtFor(reminder: ReminderEntity, now: Long): Long? = when {
        reminder.stopMs <= now -> null
        // Already due (armed late, or re-armed after a reboot mid-lead): fire straight away.
        else -> maxOf(reminder.remindAtMs, now)
    }

    /** The prompt's queue: the reminder moment has come and the programme is still on. Soonest first. */
    fun due(reminders: List<ReminderEntity>, now: Long): List<ReminderEntity> =
        reminders.filter { it.remindAtMs <= now && it.stopMs > now }.sortedBy { it.startMs }

    /** Whether a reminder can still be set for a programme — not once it has started. */
    fun canRemind(startMs: Long, now: Long): Boolean = startMs > now
}
