package tv.own.owntv.player

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.player.PlaybackEngines.Pressure

/** Which `onTrimMemory` levels count as pressure. `UI_HIDDEN` on every Home press used to shrink
 *  mpv's cache for the rest of the session. */
@Suppress("DEPRECATION")
class PlaybackEnginesTest {

    @Test
    fun `hiding the UI is not memory pressure`() {
        assertEquals(Pressure.NONE, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(Pressure.NONE, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(Pressure.NONE, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(Pressure.NONE, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `running low trims, critical and complete go further`() {
        assertEquals(Pressure.LOW, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(Pressure.CRITICAL, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(Pressure.CRITICAL, PlaybackEngines.pressureOf(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
