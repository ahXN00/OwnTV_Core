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
