package tv.own.owntv.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.util.Pin

/** The category lock PIN and the Settings lock PIN: per profile, separate, and an old lock kept on upgrade. */
class CustomizePinStoreTest {

    /**
     * In memory: a file-backed DataStore replaces its file by renaming over it, which Windows refuses
     * while the old file exists, so every second write would fail on a Windows build machine.
     */
    private class MemoryDataStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val memory = MemoryDataStore()
    private val store = CustomizePinStore(memory)

    /** A profile from before the Settings lock had its own PIN: "ask for PIN to open Settings" on. */
    private fun legacyLock(profileId: Long, customizePin: String?) {
        memory.state.value = memory.state.value.toMutablePreferences().apply {
            if (customizePin != null) this[stringPreferencesKey("customize_pin_$profileId")] = customizePin
            this[booleanPreferencesKey("settings_pin_$profileId")] = true
        }
    }

    @Test
    fun `the Settings lock is off until a PIN is set, and per profile`() = runBlocking {
        assertNull(store.settingsLockPin(1L).first())
        store.setSettingsLockPin(1L, "1234")
        assertTrue(Pin.verify("1234", store.settingsLockPin(1L).first()))
        assertNull(store.settingsLockPin(2L).first())
        store.setSettingsLockPin(1L, null)
        assertNull(store.settingsLockPin(1L).first())
    }

    @Test
    fun `the two PINs are separate`() = runBlocking {
        store.setCustomizePin(1L, "1111")
        store.setSettingsLockPin(1L, "2222")
        assertFalse(Pin.verifyCustomize("1111", store.settingsLockPin(1L).first()))
        assertFalse(Pin.verifyCustomize("2222", store.customizePin(1L).first()))
        store.setCustomizePin(1L, null)
        assertTrue(Pin.verify("2222", store.settingsLockPin(1L).first()))
        store.setCustomizePin(1L, "3333")
        store.setSettingsLockPin(1L, null)
        assertTrue(Pin.verify("3333", store.customizePin(1L).first()))
    }

    @Test
    fun `turning the lock off clears its long-press switch`() = runBlocking {
        store.setSettingsLockPin(1L, "1234")
        store.setSettingsLockMenus(1L, true)
        assertTrue(store.settingsLockMenus(1L).first())
        store.setSettingsLockPin(1L, null)
        assertFalse(store.settingsLockMenus(1L).first())
    }

    @Test
    fun `an old Settings lock keeps asking the PIN it knows`() = runBlocking {
        val hash = Pin.hash("1234")
        legacyLock(1L, hash)
        assertEquals(hash, store.settingsLockPin(1L).first())
    }

    @Test
    fun `changing the category PIN after an upgrade leaves the Settings PIN as it was`() = runBlocking {
        legacyLock(1L, Pin.hash("1234"))
        store.setCustomizePin(1L, "5678")
        assertTrue(Pin.verify("1234", store.settingsLockPin(1L).first()))
        assertTrue(Pin.verify("5678", store.customizePin(1L).first()))
        store.setCustomizePin(1L, null)
        assertTrue(Pin.verify("1234", store.settingsLockPin(1L).first()))
    }

    @Test
    fun `an old Settings lock with a PIN saved before hashing is carried over hashed`() = runBlocking {
        legacyLock(1L, "1234")
        store.setCustomizePin(1L, null)
        val pin = store.settingsLockPin(1L).first()!!
        assertTrue(Pin.isHashed(pin))
        assertTrue(Pin.verify("1234", pin))
    }

    @Test
    fun `an old switch with no PIN behind it locks nothing`() = runBlocking {
        legacyLock(1L, null)
        assertNull(store.settingsLockPin(1L).first())
        store.setCustomizePin(1L, "1234")
        assertNull(store.settingsLockPin(1L).first())
    }

    @Test
    fun `backups carry each PIN in its own block`() = runBlocking {
        store.setCustomizePin(1L, "1111")
        store.setSettingsLockPin(2L, "2222")
        store.setSettingsLockMenus(2L, true)
        assertEquals(listOf("1"), store.exportCustomizePins().keys().asSequence().toList())
        val settingsPins = store.exportSettingsLockPins()
        assertEquals(listOf("2"), settingsPins.keys().asSequence().toList())
        val menus = store.exportSettingsLockMenus()

        val other = CustomizePinStore(MemoryDataStore())
        other.importSettingsLockPins(settingsPins, setOf(2L))
        other.importSettingsLockMenus(menus, setOf(2L))
        assertTrue(Pin.verify("2222", other.settingsLockPin(2L).first()))
        assertTrue(other.settingsLockMenus(2L).first())
        assertNull(other.customizePin(2L).first())
    }

    @Test
    fun `an old lock is exported as its own Settings PIN`() = runBlocking {
        val hash = Pin.hash("1234")
        legacyLock(1L, hash)
        assertEquals(hash, store.exportSettingsLockPins().optString("1"))
    }

    @Test
    fun `a restore drops PINs of profiles that are not on this device`() = runBlocking {
        store.setSettingsLockPin(5L, "1234")
        val other = CustomizePinStore(MemoryDataStore())
        other.importSettingsLockPins(store.exportSettingsLockPins(), setOf(1L))
        assertNull(other.settingsLockPin(5L).first())
    }

    @Test
    fun `deleting a profile forgets both PINs`() = runBlocking {
        store.setCustomizePin(1L, "1111")
        store.setSettingsLockPin(1L, "2222")
        store.setSettingsLockMenus(1L, true)
        store.forgetProfilePins(1L)
        assertNull(store.customizePin(1L).first())
        assertNull(store.settingsLockPin(1L).first())
        assertFalse(store.settingsLockMenus(1L).first())
        assertTrue(memory.state.value.asMap().isEmpty())
    }
}
