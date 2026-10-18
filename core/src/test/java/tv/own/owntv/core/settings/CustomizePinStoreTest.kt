package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import tv.own.owntv.core.util.Pin

/** The Settings lock rides on the Customize PIN: per profile, off by default, gone with the PIN. */
class CustomizePinStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val store by lazy {
        CustomizePinStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "test.preferences_pb") },
        )
    }

    @After
    fun tearDown() = scope.cancel()

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
