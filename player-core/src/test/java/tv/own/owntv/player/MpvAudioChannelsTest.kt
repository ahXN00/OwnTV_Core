package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** mpv gets the layouts the output names, never more; an output that reports none keeps auto-safe. */
class MpvAudioChannelsTest {

    @Test
    fun `a 5_1 output stops at 5_1`() {
        assertEquals("stereo,5.1", MpvAudioChannels.listFor(setOf(2, 6)))
    }

    @Test
    fun `a 7_1 receiver gets both`() {
        assertEquals("stereo,5.1,7.1", MpvAudioChannels.listFor(setOf(1, 2, 6, 8)))
    }

    @Test
    fun `a stereo output gets stereo`() {
        assertEquals("stereo", MpvAudioChannels.listFor(setOf(1, 2)))
    }

    @Test
    fun `an output that reports nothing keeps auto-safe`() {
        assertEquals(MpvAudioChannels.UNKNOWN, MpvAudioChannels.listFor(emptySet()))
    }

    @Test
    fun `before Android 13 a TV with no HDMI output plays on its own speakers - stereo`() {
        assertEquals(setOf(2), MpvAudioChannels.withoutRouting(hdmiCounts = null))
        assertEquals("stereo", MpvAudioChannels.listFor(MpvAudioChannels.withoutRouting(null)))
    }

    @Test
    fun `before Android 13 an HDMI output says what it takes`() {
        assertEquals(setOf(2, 6), MpvAudioChannels.withoutRouting(setOf(2, 6)))
        assertEquals(emptySet<Int>(), MpvAudioChannels.withoutRouting(emptySet())) // reports nothing → auto-safe
    }
}
