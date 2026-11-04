package tv.own.owntv.core.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputDelayTest {

    @Test
    fun `bluetooth wins over everything else that is connected`() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertEquals(AudioOutputKind.BLUETOOTH, AudioOutputKind.of(types))
        assertEquals(AudioOutputKind.BLUETOOTH, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
    }

    @Test
    fun `a soundbar on ARC or eARC wins over plain HDMI`() {
        assertEquals(AudioOutputKind.ARC, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC)))
        assertEquals(AudioOutputKind.ARC, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_HDMI_EARC)))
        assertEquals(AudioOutputKind.HDMI, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI)))
    }

    @Test
    fun `wired and USB are their own kind, built-in speakers are the fallback`() {
        assertEquals(AudioOutputKind.OTHER, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertEquals(AudioOutputKind.OTHER, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertEquals(AudioOutputKind.SPEAKER, AudioOutputKind.of(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertEquals(AudioOutputKind.SPEAKER, AudioOutputKind.of(emptyList()))
    }

    @Test
    fun `offsets round-trip and an unset output has none`() {
        val d = OutputAudioDelays.NONE.with(AudioOutputKind.BLUETOOTH, 200).with(AudioOutputKind.HDMI, -25)
        val back = OutputAudioDelays.parse(d.encode())
        assertEquals(200, back.offsetFor(AudioOutputKind.BLUETOOTH))
        assertEquals(-25, back.offsetFor(AudioOutputKind.HDMI))
        assertEquals(0, back.offsetFor(AudioOutputKind.SPEAKER))
        assertEquals(d, back)
    }

    @Test
    fun `setting zero removes the entry and junk is ignored`() {
        val d = OutputAudioDelays.NONE.with(AudioOutputKind.ARC, 50).with(AudioOutputKind.ARC, 0)
        assertEquals(OutputAudioDelays.NONE, d)
        assertEquals("", d.encode())
        val parsed = OutputAudioDelays.parse("ARC=75,NOPE=10,HDMI=abc,,SPEAKER=99999")
        assertEquals(75, parsed.offsetFor(AudioOutputKind.ARC))
        assertEquals(0, parsed.offsetFor(AudioOutputKind.HDMI))
        assertEquals(OutputAudioDelays.MAX_MS, parsed.offsetFor(AudioOutputKind.SPEAKER))
        assertEquals(OutputAudioDelays.NONE, OutputAudioDelays.parse(null))
    }
}
