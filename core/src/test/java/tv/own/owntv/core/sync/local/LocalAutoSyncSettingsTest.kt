package tv.own.owntv.core.sync.local

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The household's answers read as one schedule.
 *
 * Which devices take part in a pass is decided here and nowhere else: the worker asks for the ones
 * that are due, and the scheduler asks for the shortest timer anyone wanted. Both are pure answers,
 * which is why they are tested without a disk, a worker or a WorkManager.
 */
class LocalAutoSyncSettingsTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val week = TimeUnit.DAYS.toMillis(7)

    /** A pairing nobody has configured is off, not "everything, nightly". */
    @Test
    fun `a device that was never asked is off`() {
        val settings = LocalAutoSyncSettings()
        assertEquals(LocalAutoSyncTiming.OFF, settings["television"].timing)
        assertFalse(settings["television"].enabled)
        assertNull("nothing asked for a timer", settings.periodicIntervalMs)
        assertFalse("nothing asked to run immediately", settings.hasStartup)
    }

    /** One timer, at the shortest interval any device asked for; the worker skips the rest. */
    @Test
    fun `the timer is the shortest one any device asked for`() {
        val settings = LocalAutoSyncSettings(
            byDevice = mapOf(
                "television" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24),
                "tablet" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7),
            ),
        )
        assertEquals(day, settings.periodicIntervalMs)
    }

    /** Off and "immediately" are not timers. Off alone means no timer at all. */
    @Test
    fun `off and immediately are not timers`() {
        assertEquals(
            null,
            LocalAutoSyncSettings(
                byDevice = mapOf(
                    "a" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.OFF),
                    "b" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.STARTUP),
                ),
            ).periodicIntervalMs,
        )
    }

    @Test
    fun `immediately is a startup device`() {
        val settings = LocalAutoSyncSettings(
            byDevice = mapOf(
                "television" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.STARTUP),
                "tablet" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24),
            ),
        )
        assertTrue(settings.hasStartup)
        assertEquals(setOf("television"), settings.startups().keys)
    }

    /** A device that has never run is due as soon as its own interval has passed. */
    @Test
    fun `a device that has never run is due after its interval`() {
        val settings = LocalAutoSyncSettings(
            byDevice = mapOf("television" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24)),
        )
        assertTrue(settings.dueAt(now = day - 1).isEmpty())
        assertEquals(setOf("television"), settings.dueAt(now = day).keys)
    }

    /**
     * One device's last run does not postpone another's.
     *
     * The daily television and the weekly tablet share a timer that wakes at the shortest of the
     * two; the tablet is only due when its own week has passed, whatever the television did this
     * morning — and the television is due again the next day, whatever the tablet is waiting for.
     */
    @Test
    fun `each device is due on its own schedule`() {
        val settings = LocalAutoSyncSettings(
            byDevice = mapOf(
                "television" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24, lastRunAt = week - 1),
                "tablet" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7),
            ),
        )
        assertEquals(setOf("tablet"), settings.dueAt(now = week).keys)
        assertEquals(setOf("television", "tablet"), settings.dueAt(now = week + day).keys)
    }

    /** A device that is no longer paired keeps its answer on disk but takes no part in a pass. */
    @Test
    fun `only paired devices count`() {
        val settings = LocalAutoSyncSettings(
            byDevice = mapOf(
                "television" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_6),
                "tablet" to LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24),
            ),
        ).only(setOf("tablet"))
        assertEquals(setOf("tablet"), settings.byDevice.keys)
        assertEquals(LocalAutoSyncTiming.OFF, settings["television"].timing)
        assertEquals(day, settings.periodicIntervalMs)
    }
}
