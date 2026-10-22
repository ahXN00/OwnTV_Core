package tv.own.owntv.core.sync.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.BackupManager

/**
 * The auto-sync settings survive a trip through the disk.
 *
 * A television is restarted more often than a phone is, and the schedule is the one thing the user
 * set up and then walked away from — a setting that quietly forgets itself is the whole feature
 * quietly forgetting itself.
 *
 * The test names are camelCase and not sentences in backticks, unlike the unit tests beside them:
 * a name with spaces becomes part of the name of the classes the compiler generates inside it, and
 * dex has no room for spaces in a class name before API 30.
 */
@RunWith(AndroidJUnit4::class)
class LocalAutoSyncStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = LocalAutoSyncStore(context)

    @Before
    fun setUp(): Unit = runBlocking {
        // The store is a process-wide DataStore, so a test that left a timing behind would decide
        // what the next one sees. Writing the defaults back is the reset.
        store.save(LocalAutoSyncConfig())
    }

    /** Nothing saved yet means nothing happens: the feature is off until it is asked for. */
    @Test
    fun nothingSavedMeansOff() = runBlocking {
        val config = store.current()
        assertEquals(LocalAutoSyncTiming.OFF, config.timing)
        assertFalse("auto-sync must not be on by default", config.enabled)
    }

    /** The timing, the sections, the profiles and the devices all come back as they were left. */
    @Test
    fun whatWasSavedIsWhatComesBack() = runBlocking {
        store.save(
            LocalAutoSyncConfig(
                timing = LocalAutoSyncTiming.HOURS_24,
                sections = setOf(BackupManager.Section.SOURCES, BackupManager.Section.FAVORITES),
                profileIds = setOf(2L, 5L),
                direction = SyncDirection.RECEIVE,
                deviceIds = setOf("phone-1"),
            ),
        )
        val config = store.current()
        assertEquals(LocalAutoSyncTiming.HOURS_24, config.timing)
        assertEquals(setOf(BackupManager.Section.SOURCES, BackupManager.Section.FAVORITES), config.sections)
        assertEquals(setOf(2L, 5L), config.profileIds)
        assertEquals(SyncDirection.RECEIVE, config.direction)
        assertEquals(setOf("phone-1"), config.deviceIds)
    }

    /**
     * Nothing ticked in a list is a real answer, and it is not "everything".
     *
     * The config reads an empty set as "all", which is the right default for a device that was
     * never asked. A user who ticks nothing has answered, and the answer is "none" — writing that
     * down as "all" would sync the one thing they asked not to.
     */
    @Test
    fun nothingTickedSurvivesAsNothingTicked() = runBlocking {
        store.save(LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24, sections = emptySet()))
        assertEquals(emptySet<BackupManager.Section>(), store.current().sections)
    }

    /** Saving the settings does not roll back the record of the last run. */
    @Test
    fun savingTheSettingsLeavesTheLastRunAlone() = runBlocking {
        store.recordRun(at = 1_000L, success = true)
        store.save(LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7))
        assertEquals(1_000L, store.current().lastRunAt)
        assertEquals(1_000L, store.current().lastSuccessAt)
    }

    /** A run that synced nothing still happened, so the timestamp moves — but nothing is claimed. */
    @Test
    fun aRunThatSyncedNothingIsStillARun() = runBlocking {
        store.recordRun(at = 2_000L, success = true)
        store.recordRun(at = 3_000L, success = false)
        val config = store.current()
        assertEquals(3_000L, config.lastRunAt)
        assertEquals("a failed run must not overwrite the last good one", 2_000L, config.lastSuccessAt)
    }
}
