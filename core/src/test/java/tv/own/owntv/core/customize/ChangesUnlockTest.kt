package tv.own.owntv.core.customize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesUnlockTest {

    @Test
    fun `the long-press changes stay open for the profile that entered the PIN`() {
        val session = CategoryUnlockSession()
        assertFalse(session.changesUnlocked(1L))
        assertTrue(session.unlockChanges(1L))
        assertTrue(session.changesUnlocked(1L))
        assertFalse(session.changesUnlocked(2L))
    }

    @Test
    fun `leaving the app or switching profile closes them again`() {
        val session = CategoryUnlockSession()
        session.unlockChanges(1L)
        session.relockAll()
        assertFalse(session.changesUnlocked(1L))
    }

    @Test
    fun `a PIN checked across a relock opens nothing`() {
        val session = CategoryUnlockSession()
        val before = session.epoch()
        session.relockAll()
        assertFalse(session.unlockChanges(1L, since = before))
        assertFalse(session.changesUnlocked(1L))
    }
}
