package tv.own.owntv.core.sync.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.BackupManager.Section

/** The agreement store on a real DataStore: round-trip, paired-only, attempts, turn-offs and notices. */
@RunWith(AndroidJUnit4::class)
class LocalAutoSyncStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val paired = PairedDeviceStore(context)
    private val store = LocalAutoSyncStore(context, paired)
    private val device = PairedDevice("peer", "Phone", "192.168.1.9", 8089, "s".repeat(64), pairedAt = 1)

    @Before
    fun setUp() = runBlocking {
        paired.put(device)
        store.forget(device.id)
    }

    @After
    fun tearDown() = runBlocking {
        store.forget(device.id)
        paired.remove(device.id)
        store.setListenAlways(false)
    }

    @Test
    fun termsRoundTripAndKeepTheirRecord() = runBlocking {
        store.saveTerms(device.id, setOf(Section.HISTORY), 2L, LocalAutoSyncTiming.HOURS_24, setOf(Section.FAVORITES), "Phone")
        store.recordAttempt(device.id, at = 50, success = true)
        store.saveTerms(device.id, setOf(Section.RESUME), 1L, LocalAutoSyncTiming.IMMEDIATELY)
        val a = store.get(device.id)!!
        assertEquals(setOf(Section.RESUME), a.sections)
        assertEquals(setOf(Section.FAVORITES), a.theirSections)
        assertEquals(50L, a.lastSuccessAt)
    }

    @Test
    fun aFailureMovesOnlyTheAttempt() = runBlocking {
        store.saveTerms(device.id, AutoSyncAgreement.DEFAULT_SECTIONS, 1L, LocalAutoSyncTiming.HOURS_6)
        store.recordAttempt(device.id, at = 10, success = true)
        store.recordAttempt(device.id, at = 20, success = false)
        val a = store.get(device.id)!!
        assertEquals(10L, a.lastSuccessAt)
        assertEquals(20L, a.lastAttemptAt)
        assertEquals(1, a.failures)
    }

    @Test
    fun turningOffQueuesANoticeAndTheirTurnOffIsShown() = runBlocking {
        store.saveTerms(device.id, AutoSyncAgreement.DEFAULT_SECTIONS, 1L, LocalAutoSyncTiming.HOURS_6)
        store.remove(device.id, tellThem = true)
        assertNull(store.get(device.id))
        assertTrue(device.id in store.pendingNotices())
        store.noticeDelivered(device.id)
        assertFalse(device.id in store.pendingNotices())
        store.saveTerms(device.id, AutoSyncAgreement.DEFAULT_SECTIONS, 1L, LocalAutoSyncTiming.HOURS_6)
        store.turnedOffByThem(device.id, at = 99)
        assertNull(store.get(device.id))
        assertEquals(99L, store.turnedOffByThem.first()[device.id])
    }

    @Test
    fun anUnpairedDeviceHasNoAgreement() = runBlocking {
        store.saveTerms(device.id, AutoSyncAgreement.DEFAULT_SECTIONS, 1L, LocalAutoSyncTiming.HOURS_6)
        paired.remove(device.id)
        assertNull(store.get(device.id))
    }

    @Test
    fun listenAlwaysIsOffByDefault() = runBlocking {
        assertFalse(store.listenAlways.first())
        store.setListenAlways(true)
        assertTrue(store.listenAlways.first())
    }
}
