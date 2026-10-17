package tv.own.owntv.core.customize

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.MediaType

/** What the PIN opened lasts for the session only: leaving the app or switching profile locks it again. */
class CategoryUnlockSessionTest {

    @Test
    fun `an opened category stays open for that profile and section only`() = runBlocking {
        val s = CategoryUnlockSession()
        s.unlock(1, MediaType.LIVE, "1:adult")
        assertTrue(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
        assertFalse(s.isUnlocked(1, MediaType.MOVIE, "1:adult"))
        assertFalse(s.isUnlocked(2, MediaType.LIVE, "1:adult"))
        assertEquals(setOf("1:adult"), s.unlocked(1, MediaType.LIVE).first())
    }

    @Test
    fun `leaving the app locks everything again`() = runBlocking {
        val s = CategoryUnlockSession()
        s.unlock(1, MediaType.LIVE, "1:adult")
        s.unlock(1, MediaType.MOVIE, "1:films")
        s.relockAll()
        assertFalse(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
        assertTrue(s.unlocked(1, MediaType.MOVIE).first().isEmpty())
    }

    @Test
    fun `opening a category for another profile drops the first profile's`() {
        val s = CategoryUnlockSession()
        s.unlock(1, MediaType.LIVE, "1:adult")
        s.unlock(2, MediaType.LIVE, "1:news")
        assertFalse(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
        assertTrue(s.isUnlocked(2, MediaType.LIVE, "1:news"))
    }

    @Test
    fun `locking an opened category closes just that one`() {
        val s = CategoryUnlockSession()
        s.unlock(1, MediaType.LIVE, "1:adult")
        s.unlock(1, MediaType.LIVE, "1:late")
        s.relock(1, MediaType.LIVE, "1:adult")
        assertFalse(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
        assertTrue(s.isUnlocked(1, MediaType.LIVE, "1:late"))
    }

    @Test
    fun `a PIN checked before a relock opens nothing after it`() {
        val s = CategoryUnlockSession()
        val before = s.epoch()
        s.relockAll()
        assertFalse(s.unlock(1, MediaType.LIVE, "1:adult", since = before))
        assertFalse(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
    }

    @Test
    fun `a PIN checked with no relock in between opens the category`() {
        val s = CategoryUnlockSession()
        s.relockAll()
        val before = s.epoch()
        assertTrue(s.unlock(1, MediaType.LIVE, "1:adult", since = before))
        assertTrue(s.isUnlocked(1, MediaType.LIVE, "1:adult"))
    }
}
