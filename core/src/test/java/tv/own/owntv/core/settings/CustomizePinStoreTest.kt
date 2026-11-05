package tv.own.owntv.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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

/** The Settings lock rides on the Customize PIN: per profile, off by default, gone with the PIN. */
class CustomizePinStoreTest {

    /**
     * In memory: a file-backed DataStore replaces its file by renaming over it, which Windows refuses
     * while the old file exists, so every second write would fail on a Windows build machine.
     */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val store = CustomizePinStore(MemoryDataStore())

    @Test
    fun `the Settings lock is off until switched on`() = runBlocking {
        assertFalse(store.settingsPinEnabled(1L).first())
        store.setSettingsPinEnabled(1L, true)
        assertTrue(store.settingsPinEnabled(1L).first())
        store.setSettingsPinEnabled(1L, false)
        assertFalse(store.settingsPinEnabled(1L).first())
    }

    @Test
    fun `the Settings lock is per profile`() = runBlocking {
        store.setSettingsPinEnabled(1L, true)
        assertFalse(store.settingsPinEnabled(2L).first())
    }

    @Test
    fun `removing the PIN switches the Settings lock off`() = runBlocking {
        store.setCustomizePin(1L, "1234")
        store.setSettingsPinEnabled(1L, true)
        store.setCustomizePin(1L, null)
        assertNull(store.customizePin(1L).first())
        assertFalse(store.settingsPinEnabled(1L).first())
    }

    @Test
    fun `changing the PIN keeps the Settings lock`() = runBlocking {
        store.setCustomizePin(1L, "1234")
        store.setSettingsPinEnabled(1L, true)
        store.setCustomizePin(1L, "5678")
        assertTrue(store.settingsPinEnabled(1L).first())
        assertTrue(Pin.verify("5678", store.customizePin(1L).first()))
    }

    @Test
    fun `the Settings lock never leaks into the exported Customize PINs`() = runBlocking {
        store.setCustomizePin(1L, "1234")
        store.setSettingsPinEnabled(1L, true)
        val exported = store.exportCustomizePins()
        assertEquals(listOf("1"), exported.keys().asSequence().toList())
    }
}
