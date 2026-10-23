package tv.own.owntv.core.sync.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.BackupManager

/**
 * Each paired device's auto-sync settings survive a trip through the disk, on their own.
 *
 * A television is restarted more often than a phone is, and the schedule is the one thing the user
 * set up and then walked away from — a setting that quietly forgets itself is the whole feature
 * quietly forgetting itself. It is now one answer per device, so the test also has to show that one
 * device's save does not touch the one beside it.
 *
 * The test names are camelCase and not sentences in backticks, unlike the unit tests beside them:
 * a name with spaces becomes part of the name of the classes the compiler generates inside it, and
 * dex has no room for spaces in a class name before API 30.
 */
@RunWith(AndroidJUnit4::class)
class LocalAutoSyncStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val paired = PairedDeviceStore(context)
    private val store = LocalAutoSyncStore(context, paired)

    // Both stores are process-wide DataStores, so a test that reused a device id would read what
    // the test before it wrote. Every test pairs its own device instead.
    private lateinit var device: PairedDevice

    @Before
    fun setUp(): Unit = runBlocking {
        device = pair("Living room")
    }

    /** A device nobody has configured does nothing: pairing it is not the same as turning it on. */
    @Test
    fun nothingSavedMeansOff() = runBlocking {
        val config = store.current()[device.id]
        assertEquals(LocalAutoSyncTiming.OFF, config.timing)
        assertFalse("auto-sync must not be on by default", config.enabled)
    }

    /** The timing, the sections, the profiles and the direction all come back as they were left. */
    @Test
    fun whatWasSavedIsWhatComesBack() = runBlocking {
        store.save(
            device.id,
            LocalAutoSyncConfig(
                timing = LocalAutoSyncTiming.HOURS_24,
                sections = setOf(BackupManager.Section.SOURCES, BackupManager.Section.FAVORITES),
                profileIds = setOf(2L, 5L),
                direction = SyncDirection.RECEIVE,
            ),
        )
        val config = store.current()[device.id]
        assertEquals(LocalAutoSyncTiming.HOURS_24, config.timing)
        assertEquals(setOf(BackupManager.Section.SOURCES, BackupManager.Section.FAVORITES), config.sections)
        assertEquals(setOf(2L, 5L), config.profileIds)
        assertEquals(SyncDirection.RECEIVE, config.direction)
    }

    /** Two devices, two answers: "the television nightly, the tablet weekly" is a thing to ask for. */
    @Test
    fun eachDeviceKeepsItsOwnAnswer() = runBlocking {
        val tablet = pair("Tablet")
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24))
        store.save(tablet.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7))
        val settings = store.current()
        assertEquals(LocalAutoSyncTiming.HOURS_24, settings[device.id].timing)
        assertEquals(LocalAutoSyncTiming.DAYS_7, settings[tablet.id].timing)
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
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24, sections = emptySet()))
        assertEquals(emptySet<BackupManager.Section>(), store.current()[device.id].sections)
    }

    /** Saving the settings does not roll back the record of the last run. */
    @Test
    fun savingTheSettingsLeavesTheLastRunAlone() = runBlocking {
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24))
        store.recordRun(device.id, at = 1_000L, success = true)
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7))
        assertEquals(1_000L, store.current()[device.id].lastRunAt)
        assertEquals(1_000L, store.current()[device.id].lastSuccessAt)
    }

    /** A run that synced nothing still happened, so the timestamp moves — but nothing is claimed. */
    @Test
    fun aRunThatSyncedNothingIsStillARun() = runBlocking {
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24))
        store.recordRun(device.id, at = 2_000L, success = true)
        store.recordRun(device.id, at = 3_000L, success = false)
        val config = store.current()[device.id]
        assertEquals(3_000L, config.lastRunAt)
        assertEquals("a failed run must not overwrite the last good one", 2_000L, config.lastSuccessAt)
    }

    /**
     * One device's run is not another device's run.
     *
     * Each device has its own interval, so a run that reaches one of them says nothing about
     * whether the other is due. A shared timestamp would make a nightly television postpone a
     * weekly tablet, or the other way round.
     */
    @Test
    fun aRunWithOneDeviceLeavesTheOtherAlone() = runBlocking {
        val tablet = pair("Tablet")
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24))
        store.save(tablet.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.DAYS_7))
        store.recordRun(device.id, at = 5_000L, success = true)
        assertEquals(5_000L, store.current()[device.id].lastRunAt)
        assertEquals(0L, store.current()[tablet.id].lastRunAt)
    }

    /**
     * A device that is no longer paired does not count, and still remembers.
     *
     * The settings for it are kept on disk so that pairing the same device again brings the
     * schedule back, but until then neither the timer nor a pass should see it.
     */
    @Test
    fun anUnpairedDeviceKeepsItsAnswerButIsNotCounted() = runBlocking {
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_6))
        paired.remove(device.id)
        assertEquals(LocalAutoSyncTiming.OFF, store.current()[device.id].timing)
        assertFalse(store.current().byDevice.containsKey(device.id))

        paired.put(device)
        assertEquals(LocalAutoSyncTiming.HOURS_6, store.current()[device.id].timing)
    }

    /** A config for a device that was never paired is not read back as if it were. */
    @Test
    fun settingsOnlyHoldPairedDevices() = runBlocking {
        store.save(device.id, LocalAutoSyncConfig(timing = LocalAutoSyncTiming.HOURS_24))
        val settings = store.current()
        assertTrue(settings.byDevice.keys.all { it == device.id })
    }

    private suspend fun pair(name: String): PairedDevice {
        val device = PairedDevice(
            id = UUID.randomUUID().toString(),
            name = name,
            address = "192.0.2.1",
            port = 8787,
            secret = "secret",
            pairedAt = 1L,
        )
        paired.put(device)
        return device
    }
}
