package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/** Pre-roll and post-roll arithmetic, and which recordings get in each other's way (D10). */
class RecordingScheduleTest {

    private val minute = 60_000L
    /** 2026-09-12 21:00 and 22:00 as plain numbers — the arithmetic has no opinion about calendars. */
    private val nine = 1_757_710_800_000L
    private val ten = nine + 60 * minute

    // --- The recorded window ---

    @Test
    fun `the window is the programme plus its paddings`() {
        val window = RecordingSchedule.windowFor(nine, ten, preRollMinutes = 2, postRollMinutes = 5)
        assertEquals(nine - 2 * minute, window.first)
        assertEquals(ten + 5 * minute, window.last)
    }

    @Test
    fun `no padding records exactly the programme`() {
        val window = RecordingSchedule.windowFor(nine, ten, preRollMinutes = 0, postRollMinutes = 0)
        assertEquals(nine, window.first)
        assertEquals(ten, window.last)
    }

    @Test
    fun `an inexact alarm is compensated for by starting earlier`() {
        // Late is the one direction a recording cannot afford: the missed minutes are the beginning
        // of the programme. Starting early costs disk space, which is the cheaper mistake.
        val exact = RecordingSchedule.windowFor(nine, ten, 1, 3, exactAlarms = true)
        val inexact = RecordingSchedule.windowFor(nine, ten, 1, 3, exactAlarms = false)
        assertTrue(inexact.first < exact.first)
        assertEquals(
            RecordingSchedule.INEXACT_HEAD_START_MINUTES * minute,
            exact.first - inexact.first,
        )
        // The end is unaffected — a late alarm does not make a programme finish later.
        assertEquals(exact.last, inexact.last)
    }

    @Test
    fun `an absurd padding is clamped rather than obeyed`() {
        val window = RecordingSchedule.windowFor(nine, ten, preRollMinutes = 9_999, postRollMinutes = -5)
        assertEquals(nine - RecordingSchedule.MAX_ROLL_MINUTES * minute, window.first)
        assertEquals(ten, window.last)
    }

    @Test
    fun `a guide that ends a programme before it starts still produces a usable window`() {
        // Not hypothetical: providers publish these. A negative window would simply never run.
        val window = RecordingSchedule.windowFor(nine, nine - 10 * minute, 0, 0)
        assertTrue(window.last > window.first)
    }

    // --- Overlap ---

    @Test
    fun `two windows that share time overlap`() {
        assertTrue(RecordingSchedule.overlaps(0, 100, 50, 150))
        assertTrue(RecordingSchedule.overlaps(50, 150, 0, 100))
        // One wholly inside the other.
        assertTrue(RecordingSchedule.overlaps(0, 100, 10, 20))
    }

    @Test
    fun `windows that merely touch do not clash`() {
        // The 21:00 recording starting exactly as the 20:00 one stops is the normal case, not a
        // conflict. Treating it as one would refuse half of an evening's viewing.
        assertFalse(RecordingSchedule.overlaps(0, 100, 100, 200))
        assertFalse(RecordingSchedule.overlaps(100, 200, 0, 100))
    }

    // --- Clashes against real rows ---

    private fun row(
        id: Long,
        sourceId: Long = 1,
        start: Long = nine,
        stop: Long = ten,
        status: RecordingStatus = RecordingStatus.SCHEDULED,
    ) = RecordingEntity(
        id = id,
        profileId = 1,
        sourceId = sourceId,
        channelId = id,
        channelName = "Channel $id",
        streamUrl = "http://example.invalid/$id",
        title = "Programme $id",
        programmeStartMs = start,
        programmeStopMs = stop,
        startMs = start,
        stopMs = stop,
        status = status,
    )

    @Test
    fun `a clash is reported before the user commits to anything`() {
        val clashes = RecordingSchedule.clashesAmong(listOf(row(1)), sourceId = 1, startMs = nine + minute, stopMs = ten)
        assertEquals(listOf(1L), clashes.map { it.id })
    }

    @Test
    fun `a different playlist is not a clash — each has its own connections`() {
        // Two playlists with one connection each can record two programmes at once (D10).
        val clashes = RecordingSchedule.clashesAmong(listOf(row(1, sourceId = 2)), sourceId = 1, startMs = nine, stopMs = ten)
        assertTrue(clashes.isEmpty())
    }

    @Test
    fun `a finished or missed recording cannot clash with anything`() {
        val done = listOf(
            row(1, status = RecordingStatus.COMPLETED),
            row(2, status = RecordingStatus.FAILED),
            row(3, status = RecordingStatus.MISSED),
            row(4, status = RecordingStatus.CANCELLED),
        )
        assertTrue(RecordingSchedule.clashesAmong(done, sourceId = 1, startMs = nine, stopMs = ten).isEmpty())
    }

    @Test
    fun `something already recording clashes just as a scheduled one does`() {
        val running = listOf(row(1, status = RecordingStatus.RECORDING))
        assertEquals(1, RecordingSchedule.clashesAmong(running, sourceId = 1, startMs = nine, stopMs = ten).size)
    }

    @Test
    fun `a recording being edited does not clash with itself`() {
        val clashes = RecordingSchedule.clashesAmong(
            listOf(row(7)), sourceId = 1, startMs = nine, stopMs = ten, excludeId = 7,
        )
        assertTrue(clashes.isEmpty())
    }

    // --- Missed, and when to wake up ---

    @Test
    fun `a scheduled recording whose window has closed has been missed`() {
        assertTrue(RecordingSchedule.hasBeenMissed(row(1), now = ten + 1))
        assertFalse(RecordingSchedule.hasBeenMissed(row(1), now = ten - 1))
    }

    @Test
    fun `a recording that already ran is not missed, whatever the clock says`() {
        assertFalse(RecordingSchedule.hasBeenMissed(row(1, status = RecordingStatus.COMPLETED), now = ten + 1))
        assertFalse(RecordingSchedule.hasBeenMissed(row(1, status = RecordingStatus.FAILED), now = ten + 1))
    }

    @Test
    fun `the wake-up time is the start, or now when the window is already open`() {
        assertEquals(nine, RecordingSchedule.wakeAtFor(row(1), now = nine - 10 * minute))
        // Already begun: wake immediately and record what is left rather than waiting for a moment
        // that has passed.
        val now = nine + 5 * minute
        assertEquals(now, RecordingSchedule.wakeAtFor(row(1), now))
    }

    @Test
    fun `there is nothing to wake up for once the window has closed`() {
        assertNull(RecordingSchedule.wakeAtFor(row(1), now = ten + 1))
        assertNull(RecordingSchedule.wakeAtFor(row(1, status = RecordingStatus.COMPLETED), now = nine - minute))
    }
}
