package tv.own.owntv.core.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.player.PlaybackVisibility.Change

class PlaybackVisibilityTest {

    // --- output presence -------------------------------------------------------------------------

    @Test
    fun `a television with its own speakers never loses its output`() {
        val speakerAndArc = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI_ARC)
        assertTrue(AudioOutputPresence.present(speakerAndArc, hdmiPlugged = null))
        // The soundbar on ARC drops: the speakers are still there.
        assertTrue(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), hdmiPlugged = null))
    }

    @Test
    fun `a box loses its output when the HDMI sink goes`() {
        assertTrue(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_HDMI), hdmiPlugged = null))
        assertTrue(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_HDMI), hdmiPlugged = true))
        assertFalse(AudioOutputPresence.present(emptyList(), hdmiPlugged = null))
        // A box that keeps listing its HDMI device after the sink is gone: the plug state wins.
        assertFalse(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_HDMI), hdmiPlugged = false))
        // Only outputs nobody can hear left behind.
        assertFalse(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX), hdmiPlugged = false))
    }

    @Test
    fun `headphones or Bluetooth keep the output present without HDMI`() {
        assertTrue(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), hdmiPlugged = false))
        assertTrue(AudioOutputPresence.present(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), hdmiPlugged = false))
    }

    // --- the machine -----------------------------------------------------------------------------

    private fun startedMachine() = PlaybackVisibility().also { assertEquals(Change.VISIBLE, it.activityStarted()) }

    @Test
    fun `stop and start alone behave as the lifecycle always did`() {
        val m = startedMachine()
        assertEquals(Change.HIDDEN, m.activityStopped())
        assertEquals(Change.VISIBLE, m.activityStarted())
        assertNull(m.activityStarted())
    }

    @Test
    fun `a short output loss does nothing`() {
        val m = startedMachine()
        assertNull(m.outputChanged(present = false, nowMs = 1_000))
        assertEquals(1_000 + PlaybackVisibility.CONFIRM_MS, m.deadlineMs())
        assertNull(m.outputChanged(present = true, nowMs = 4_000))
        assertNull(m.deadlineMs())
        assertNull(m.tick(10_000))
        assertTrue(m.visible)
    }

    @Test
    fun `a confirmed loss hides once and the return shows once`() {
        val m = startedMachine()
        m.outputChanged(present = false, nowMs = 0)
        assertNull(m.tick(PlaybackVisibility.CONFIRM_MS - 1))
        assertEquals(Change.HIDDEN, m.tick(PlaybackVisibility.CONFIRM_MS))
        assertNull(m.tick(PlaybackVisibility.CONFIRM_MS + 1_000))
        assertNull(m.deadlineMs())
        assertEquals(Change.VISIBLE, m.outputChanged(present = true, nowMs = 60_000))
        assertNull(m.outputChanged(present = true, nowMs = 61_000))
    }

    @Test
    fun `a loss during a refresh-rate switch is held off`() {
        val m = startedMachine()
        m.displayChanged(nowMs = 0)
        m.outputChanged(present = false, nowMs = 500)
        assertEquals(PlaybackVisibility.DISPLAY_CHANGE_GRACE_MS, m.deadlineMs())
        assertNull(m.tick(PlaybackVisibility.CONFIRM_MS + 500))
        // The sink comes back after the switch: nothing ever happened.
        assertNull(m.outputChanged(present = true, nowMs = 3_000))
        assertTrue(m.visible)
    }

    @Test
    fun `a stop during a confirmed loss does not hide twice`() {
        val m = startedMachine()
        m.outputChanged(present = false, nowMs = 0)
        assertEquals(Change.HIDDEN, m.tick(PlaybackVisibility.CONFIRM_MS))
        assertNull(m.activityStopped())
        // Started again while the television is still off: stays hidden.
        assertNull(m.activityStarted())
        assertFalse(m.visible)
        assertEquals(Change.VISIBLE, m.outputChanged(present = true, nowMs = 90_000))
    }

    @Test
    fun `the output returning while stopped waits for the start`() {
        val m = startedMachine()
        assertEquals(Change.HIDDEN, m.activityStopped())
        m.outputChanged(present = false, nowMs = 0)
        assertNull(m.tick(PlaybackVisibility.CONFIRM_MS))
        assertNull(m.outputChanged(present = true, nowMs = 20_000))
        assertEquals(Change.VISIBLE, m.activityStarted())
    }
}
