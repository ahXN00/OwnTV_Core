package tv.own.owntv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.util.Pin

class ProfileProtectionTest {

    private fun link(profile: Long, source: Long) = ProfileSourceCrossRef(profileId = profile, sourceId = source)

    @Test
    fun `a profile lock or a Settings lock protects a profile`() {
        assertFalse(isProtectedProfile(hasProfilePin = false, hasSettingsPin = false))
        assertTrue(isProtectedProfile(hasProfilePin = true, hasSettingsPin = false))
        assertTrue(isProtectedProfile(hasProfilePin = false, hasSettingsPin = true))
        assertTrue(isProtectedProfile(hasProfilePin = true, hasSettingsPin = true))
    }

    @Test
    fun `with no locks anywhere a new profile inherits every playlist`() {
        val links = listOf(link(1, 10), link(2, 20))
        assertEquals(listOf(10L, 20L, 30L), inheritableSourceIds(listOf(10, 20, 30), links, protectedIds = emptySet()))
    }

    @Test
    fun `a protected profile's playlists are not inherited`() {
        val links = listOf(link(1, 10), link(2, 20))
        assertEquals(listOf(20L), inheritableSourceIds(listOf(10, 20), links, protectedIds = setOf(1)))
    }

    @Test
    fun `a playlist shared by a protected and an open profile stays out`() {
        val links = listOf(link(1, 10), link(2, 10), link(2, 20))
        assertEquals(listOf(20L), inheritableSourceIds(listOf(10, 20), links, protectedIds = setOf(1)))
    }

    @Test
    fun `when every playlist belongs to a protected profile the new one starts empty`() {
        val links = listOf(link(1, 10), link(2, 20))
        assertEquals(emptyList<Long>(), inheritableSourceIds(listOf(10, 20), links, protectedIds = setOf(1, 2)))
    }

    @Test
    fun `a backup includes the active profile, open ones and unlocked protected ones`() {
        val all = listOf(1L, 2L, 3L, 4L)
        assertEquals(setOf(1L, 2L), includableProfileIds(all, protectedIds = setOf(3, 4), activeId = 1, unlocked = emptySet()))
        assertEquals(setOf(1L, 2L, 3L), includableProfileIds(all, protectedIds = setOf(1, 3, 4), activeId = 1, unlocked = setOf(3)))
    }

    @Test
    fun `a restore asks only for protected profiles it would change, not the active one`() {
        assertEquals(listOf(3L), profilesNeedingPin(listOf(1, 2, 3), protectedIds = setOf(1, 3, 4), activeId = 1, unlocked = emptySet()))
        assertEquals(emptyList<Long>(), profilesNeedingPin(listOf(1, 2, 3), protectedIds = setOf(1, 3), activeId = 1, unlocked = setOf(3)))
        assertEquals(emptyList<Long>(), profilesNeedingPin(listOf(2), protectedIds = setOf(3), activeId = 1, unlocked = emptySet()))
    }

    @Test
    fun `either of a protected profile's PINs opens it, a missing PIN never does`() {
        val profilePin = Pin.hash("1111")
        val settingsPin = Pin.hash("2222")
        assertTrue(ProfileProtection.matches("1111", profilePin, settingsPin))
        assertTrue(ProfileProtection.matches("2222", profilePin, settingsPin))
        assertFalse(ProfileProtection.matches("3333", profilePin, settingsPin))
        assertFalse(ProfileProtection.matches("1111", null, null))
        assertTrue(ProfileProtection.matches("2222", null, settingsPin))
        assertFalse(ProfileProtection.matches("1111", null, settingsPin))
    }
}
