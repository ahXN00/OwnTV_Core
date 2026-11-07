package tv.own.owntv.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPinLockTest {

    @Test
    fun `asks only while the lock is on and the visit is still locked`() {
        assertTrue(requiresSettingsPin(hasPin = true, unlocked = false))
        assertFalse(requiresSettingsPin(hasPin = true, unlocked = true))
        assertFalse(requiresSettingsPin(hasPin = false, unlocked = false))
    }

    @Test
    fun `a stored Settings PIN locks Settings until unlocked`() {
        val lock = SettingsPinLock(loaded = true, profileId = 1L, pin = "salt:hash")
        assertTrue(lock.requiresPin(unlocked = false))
        assertFalse(lock.requiresPin(unlocked = true))
    }

    @Test
    fun `pages that restore or copy settings stay shut while the lock is still loading`() {
        assertTrue(SettingsPinLock().guardsPage(unlocked = false))
        assertFalse(SettingsPinLock(loaded = true, profileId = 1L).guardsPage(unlocked = false))
    }

    @Test
    fun `pages that restore or copy settings follow the Settings lock once it is known`() {
        val lock = SettingsPinLock(loaded = true, profileId = 1L, pin = "salt:hash")
        assertTrue(lock.guardsPage(unlocked = false))
        assertFalse(lock.guardsPage(unlocked = true))
        assertFalse(lock.copy(pin = null).guardsPage(unlocked = false))
    }

    @Test
    fun `long-press changes ask only with the lock on, its switch on and no unlock yet`() {
        val lock = SettingsPinLock(loaded = true, profileId = 1L, pin = "salt:hash", lockMenus = true)
        assertTrue(lock.guardsMenuChange(unlocked = false))
        assertFalse(lock.guardsMenuChange(unlocked = true))
        assertFalse(lock.copy(lockMenus = false).guardsMenuChange(unlocked = false))
        // The switch means nothing while the lock itself is off.
        assertFalse(lock.copy(pin = null).guardsMenuChange(unlocked = false))
    }
}
