package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.player.PlayerBudget

/** C-H1 — a Live latency choice must reach mpv's cache too, or the device's larger cache wins. */
class MpvLiveBufferTest {

    private val budget = PlayerBudget("128MiB", "32MiB", readaheadSecs = "30", cacheSecs = "60", lowSpec = false)

    @Test
    fun `Balanced keeps the device tier`() {
        assertEquals(MpvLiveBuffer.Secs("30", "60"), MpvLiveBuffer.resolve(null, prerollSecs = 0, budget))
        assertEquals(MpvLiveBuffer.Secs("30", "60"), MpvLiveBuffer.resolve(null, prerollSecs = 10, budget))
    }

    @Test
    fun `a choice sets readahead and cache alike`() {
        assertEquals(MpvLiveBuffer.Secs("2", "2"), MpvLiveBuffer.resolve(2, prerollSecs = 0, budget))
        assertEquals(MpvLiveBuffer.Secs("15", "15"), MpvLiveBuffer.resolve(15, prerollSecs = 0, budget))
    }

    @Test
    fun `the pre-roll is a floor under the choice`() {
        assertEquals(MpvLiveBuffer.Secs("10", "10"), MpvLiveBuffer.resolve(2, prerollSecs = 10, budget))
    }
}
