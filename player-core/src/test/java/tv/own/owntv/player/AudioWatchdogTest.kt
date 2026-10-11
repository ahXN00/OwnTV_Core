package tv.own.owntv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AudioWatchdogTest {

    private val t = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)

    private fun audio(mime: String, channels: Int) =
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48_000).build()

    @Test
    fun `a decoder reported before its format is still a decoded stream`() {
        val w = AudioWatchdog()
        w.onAudioDecoderInitialized(t, "c2.android.aac.decoder", 0, 42)
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_AAC, 2), null)
        w.onAudioPositionAdvancing(t, 0)
        assertFalse("decoded in-app, not passthrough", w.passthrough)
        assertTrue(w.outputWasStereoPcm)
    }

    @Test
    fun `stereo AAC is stereo PCM even without a decoder event, and never read as passthrough`() {
        // Measured: a live AAC-LATM channel whose output never started latched the session to stereo.
        val w = AudioWatchdog()
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_AAC, 2), null)
        assertTrue(w.outputWasStereoPcm)
        w.onAudioPositionAdvancing(t, 0)
        assertFalse(w.passthrough)
    }

    @Test
    fun `no decoder at all is passthrough and not stereo PCM`() {
        val w = AudioWatchdog()
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_E_AC3, 6), null)
        w.onAudioPositionAdvancing(t, 0)
        assertTrue(w.passthrough)
        assertFalse(w.outputWasStereoPcm)
    }

    @Test
    fun `decoded multichannel is not stereo PCM, so the latch still applies`() {
        val w = AudioWatchdog()
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_AAC, 6), null)
        w.onAudioDecoderInitialized(t, "c2.android.aac.decoder", 0, 42)
        assertFalse(w.outputWasStereoPcm)
    }

    @Test
    fun `a released decoder no longer counts, so a following passthrough track is seen`() {
        val w = AudioWatchdog()
        w.onAudioDecoderInitialized(t, "c2.android.aac.decoder", 0, 42)
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_AAC, 2), null)
        w.onAudioDecoderReleased(t, "c2.android.aac.decoder")
        w.onAudioInputFormatChanged(t, audio(MimeTypes.AUDIO_AC3, 6), null)
        w.onAudioPositionAdvancing(t, 0)
        assertTrue(w.passthrough)
        assertFalse(w.outputWasStereoPcm)
    }
}
