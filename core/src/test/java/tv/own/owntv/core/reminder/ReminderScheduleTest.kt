package tv.own.owntv.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ReminderEntity

class ReminderScheduleTest {

    private val min = 60_000L
    private fun reminder(id: Long, start: Long, stop: Long, lead: Int) = ReminderEntity(
        id = id, profileId = 1, channelId = 259, channelName = "Sky Cinema Family", title = "Toy Story 4",
        startMs = start, stopMs = stop, leadMinutes = lead, createdAt = 0,
    )

    @Test fun `wakes lead minutes before the start`() {
        val r = reminder(1, start = 100 * min, stop = 200 * min, lead = 5)
        assertEquals(95 * min, ReminderSchedule.wakeAtFor(r, now = 10 * min))
    }

    @Test fun `a reminder already past its moment fires now`() {
        val r = reminder(1, start = 100 * min, stop = 200 * min, lead = 5)
        assertEquals(97 * min, ReminderSchedule.wakeAtFor(r, now = 97 * min))
        assertEquals(150 * min, ReminderSchedule.wakeAtFor(r, now = 150 * min))
    }

    @Test fun `no alarm once the programme has ended`() {
        val r = reminder(1, start = 100 * min, stop = 200 * min, lead = 0)
        assertNull(ReminderSchedule.wakeAtFor(r, now = 200 * min))
    }

    @Test fun `due keeps only reminders whose moment has come and whose programme is on`() {
        val early = reminder(1, start = 100 * min, stop = 200 * min, lead = 10) // due at 90
        val later = reminder(2, start = 120 * min, stop = 180 * min, lead = 0) // due at 120
        val ended = reminder(3, start = 10 * min, stop = 60 * min, lead = 0)
        assertEquals(listOf(early), ReminderSchedule.due(listOf(later, ended, early), now = 95 * min))
        assertEquals(listOf(early, later), ReminderSchedule.due(listOf(later, early), now = 120 * min))
        assertTrue(ReminderSchedule.due(listOf(early, later), now = 89 * min).isEmpty())
    }

    @Test fun `no reminder for a programme that has started`() {
        assertTrue(ReminderSchedule.canRemind(startMs = 101 * min, now = 100 * min))
        assertFalse(ReminderSchedule.canRemind(startMs = 100 * min, now = 100 * min))
    }
}
