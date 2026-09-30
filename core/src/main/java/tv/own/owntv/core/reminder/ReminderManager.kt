package tv.own.owntv.core.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.entity.ReminderEntity

/**
 * Programme reminders (Stage G2): what the guide's "Remind me" writes and what the start-time prompt
 * reads. Core keeps the time; each app draws the prompt from [due] and calls [dismiss] when the user
 * answers it.
 */
class ReminderManager(
    private val reminderDao: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Bumped by the alarm, so [due] re-reads the clock at the moment a reminder comes up. */
    private val tick = MutableStateFlow(0L)

    /** Reminders whose moment has come and whose programme is still on, soonest first. */
    val due: StateFlow<List<ReminderEntity>> = combine(reminderDao.observeAll(), tick) { all, _ ->
        ReminderSchedule.due(all, clock())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Every launch re-arms: some TVs never deliver BOOT_COMPLETED (see RecordingManager in DataModule).
        scope.launch { scheduler.rearmAll() }
    }

    fun observe(profileId: Long): Flow<List<ReminderEntity>> = reminderDao.observeForProfile(profileId)

    /**
     * Remind [profileId] of a programme; setting it again for the same programme replaces it.
     * Returns false for a programme that has already started.
     */
    suspend fun add(
        profileId: Long,
        channelId: Long,
        channelName: String,
        epgChannelId: String?,
        title: String,
        startMs: Long,
        stopMs: Long,
        leadMinutes: Int = ReminderSchedule.DEFAULT_LEAD_MINUTES,
    ): Boolean {
        val now = clock()
        if (!ReminderSchedule.canRemind(startMs, now)) return false
        val reminder = ReminderEntity(
            profileId = profileId, channelId = channelId, channelName = channelName, epgChannelId = epgChannelId,
            title = title, startMs = startMs, stopMs = stopMs, leadMinutes = leadMinutes, createdAt = now,
        )
        val id = reminderDao.upsert(reminder)
        ReminderSchedule.wakeAtFor(reminder, now)?.let { scheduler.arm(id, it) }
        return true
    }

    /** Cancel a reminder, or answer its prompt (Watch or Dismiss both end it). */
    suspend fun dismiss(id: Long) {
        scheduler.cancel(id)
        reminderDao.delete(id)
    }

    /** Called by [ReminderAlarmReceiver]. */
    internal suspend fun onAlarm() {
        tick.value = clock()
        scheduler.rearmAll()
    }
}
