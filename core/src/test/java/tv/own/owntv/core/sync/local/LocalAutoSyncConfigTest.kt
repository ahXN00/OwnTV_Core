package tv.own.owntv.core.sync.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.backup.BackupManager

class LocalAutoSyncConfigTest {

    /** Off is a timing, not a separate flag: one answer, one place to keep it. */
    @Test
    fun `auto-sync is off until a timing says otherwise`() {
        assertFalse(LocalAutoSyncConfig().enabled)
        assertFalse(LocalAutoSyncConfig(timing = LocalAutoSyncTiming.OFF).enabled)
        assertTrue(LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24).enabled)
    }

    /** Only the timed modes can be handed to WorkManager as a period. */
    @Test
    fun `only the timed modes are periodic`() {
        assertFalse(LocalAutoSyncTiming.OFF.isPeriodic)
        assertFalse(LocalAutoSyncTiming.STARTUP.isPeriodic)
        assertTrue(LocalAutoSyncTiming.HOURS_6.isPeriodic)
        assertEquals(6 * 3600_000L, LocalAutoSyncTiming.HOURS_6.intervalMs)
        assertEquals(24 * 3600_000L, LocalAutoSyncTiming.HOURS_24.intervalMs)
        assertEquals(7 * 24 * 3600_000L, LocalAutoSyncTiming.DAYS_7.intervalMs)
    }

    /** Everything travels unless the user says otherwise. */
    @Test
    fun `the default is every section`() {
        assertEquals(BackupManager.Section.entries.toSet(), LocalAutoSyncConfig().sections)
    }
}
