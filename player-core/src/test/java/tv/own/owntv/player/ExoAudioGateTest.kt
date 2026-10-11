package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.ExoAudioGate.Verdict

/** P2 — which audio ExoPlayer can play: the film hand-off gate, the "no playable audio" check, the sinks. */
class ExoAudioGateTest {

    private fun verdict(
        codec: String,
        device: Set<String> = emptySet(),
        ffmpeg: Set<String> = emptySet(),
        passthrough: Set<String> = emptySet(),
    ) = ExoAudioGate.verdict(codec, { it in device }, { it in ffmpeg }, { it in passthrough })

    @Test
    fun `codecs every device decodes pass at once`() {
        for (codec in listOf("aac", "ac3", "eac3", "mp3float", "opus", "flac", "pcm_s16le")) {
            assertEquals(codec, Verdict.ALLOWLIST, verdict(codec))
        }
    }

    @Test
    fun `DTS passes on a device decoder, then FFmpeg, then a receiver - in that order`() {
        val dts = MimeTypes.AUDIO_DTS
        assertEquals(Verdict.DEVICE_DECODER, verdict("dts", device = setOf(dts), ffmpeg = setOf(dts)))
        assertEquals(Verdict.FFMPEG, verdict("dts", ffmpeg = setOf(dts), passthrough = setOf(dts)))
        assertEquals(Verdict.PASSTHROUGH, verdict("dts", passthrough = setOf(dts)))
        assertEquals(Verdict.UNSUPPORTED, verdict("dts"))
        assertFalse(verdict("dts").safe)
    }

    @Test
    fun `MP2 is no longer assumed - it needs a decoder like DTS`() {
        assertEquals(Verdict.UNSUPPORTED, verdict("mp2"))
        assertEquals(Verdict.FFMPEG, verdict("mp2", ffmpeg = setOf(MimeTypes.AUDIO_MPEG_L2)))
    }

    @Test
    fun `TrueHD maps to its MIME type, MLP has none and is refused`() {
        assertEquals(MimeTypes.AUDIO_TRUEHD, ExoAudioGate.mimeFor("truehd"))
        assertEquals(MimeTypes.AUDIO_E_AC3, ExoAudioGate.mimeFor("eac3"))
        assertEquals(MimeTypes.AUDIO_DTS, ExoAudioGate.mimeFor("dca"))
        assertNull(ExoAudioGate.mimeFor("mlp"))
        assertEquals(Verdict.UNSUPPORTED, verdict("mlp", device = setOf(MimeTypes.AUDIO_TRUEHD)))
    }

    private fun audioGroup(vararg support: Int): Tracks.Group {
        val formats = support.indices.map {
            Format.Builder().setId(it.toString()).setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        }
        return Tracks.Group(TrackGroup(*formats.toTypedArray()), false, support, BooleanArray(support.size))
    }

    @Test
    fun `audio beyond the decoder's advertised limits still counts as playable`() {
        assertTrue(ExoAudioGate.anyPlayableAudio(Tracks(listOf(audioGroup(C.FORMAT_EXCEEDS_CAPABILITIES)))))
        assertTrue(ExoAudioGate.anyPlayableAudio(Tracks(listOf(audioGroup(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED)))))
        assertFalse(ExoAudioGate.anyPlayableAudio(Tracks(listOf(audioGroup(C.FORMAT_UNSUPPORTED_SUBTYPE)))))
        assertFalse(ExoAudioGate.anyPlayableAudio(Tracks.EMPTY))
    }

    @Test
    fun `passthrough off - whatever the app can decode is decoded, PCM never`() {
        fun mustDecode(mime: String, device: Boolean = false, ffmpeg: Boolean = false) =
            DecodedOnlyAudioSink.mustDecode(mime, { device }, { ffmpeg })
        assertTrue(mustDecode(MimeTypes.AUDIO_AC3, device = true))
        assertTrue(mustDecode(MimeTypes.AUDIO_DTS, ffmpeg = true))
        assertFalse(mustDecode(MimeTypes.AUDIO_DTS))
        assertFalse(mustDecode(MimeTypes.AUDIO_RAW, device = true, ffmpeg = true))
    }

    @Test
    fun `stereo only has a two-channel mix for every layout up to 7_1`() {
        val matrices = StereoDownmix.matrices()
        assertEquals((1..8).toList(), matrices.map { it.inputChannelCount })
        assertTrue(matrices.all { it.outputChannelCount == 2 })
    }

    /** (left, right) share of input channel [ch] — read the way the mixer reads it. */
    private fun share(channels: Int, ch: Int): Pair<Float, Float> {
        val m = StereoDownmix.matrices()[channels - 1]
        return m.getMixingCoefficient(ch, 0) to m.getMixingCoefficient(ch, 1)
    }

    @Test
    fun `every speaker lands on its own side`() {
        // 5.1: FL FR FC LFE BL BR
        assertEquals(1f to 0f, share(6, 0))
        assertEquals(0f to 1f, share(6, 1))
        assertEquals(share(6, 2).first, share(6, 2).second) // centre: both sides alike
        assertEquals(share(6, 3).first, share(6, 3).second) // LFE: both sides alike
        assertEquals(0f, share(6, 4).second) // back left: left only
        assertEquals(0f, share(6, 5).first) // back right: right only
        // 7.1 adds SL SR; 4.0 is FL FR BL BR.
        assertEquals(0f, share(8, 6).second)
        assertEquals(0f, share(8, 7).first)
        assertEquals(0f, share(4, 2).second)
        assertEquals(0f, share(4, 3).first)
        // Mono reaches both sides.
        assertTrue(share(1, 0).first > 0f && share(1, 0).second > 0f)
    }
}
