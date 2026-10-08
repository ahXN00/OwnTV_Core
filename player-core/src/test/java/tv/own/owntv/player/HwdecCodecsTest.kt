package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which codecs mpv may hardware-decode, and what the decode check does with one it may not. */
class HwdecCodecsTest {

    @Test
    fun `MPEG-2 and MPEG-4 Part 2 are allowed on the hardware decoder`() {
        assertEquals(true, OwnTVPlayer.hwdecCovers("mpeg2video"))
        assertEquals(true, OwnTVPlayer.hwdecCovers("mpeg4 (MPEG-4 part 2)"))
    }

    @Test
    fun `mpv's own defaults are kept`() {
        listOf("h264", "hevc", "vp9", "av1", "vc1").forEach {
            assertEquals(it, true, OwnTVPlayer.hwdecCovers(it))
        }
    }

    @Test
    fun `a codec outside the list is a definite no`() {
        assertEquals(false, OwnTVPlayer.hwdecCovers("mjpeg"))
        assertEquals(false, OwnTVPlayer.hwdecCovers("msmpeg4v3"))
    }

    @Test
    fun `an unknown codec is not a no`() {
        assertNull(OwnTVPlayer.hwdecCovers(null))
        assertNull(OwnTVPlayer.hwdecCovers("  "))
    }

    /** Current mpv reports only FFmpeg's long description ("H.264 / AVC / …"), with no short name first (#229). */
    @Test
    fun `mpv's long codec description is read as its FFmpeg name`() {
        mapOf(
            "H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10" to "h264",
            "H.265 / HEVC (High Efficiency Video Coding)" to "hevc",
            "MPEG-2 video" to "mpeg2video",
            "MPEG-4 part 2" to "mpeg4",
            "SMPTE VC-1" to "vc1",
            "Google VP9" to "vp9",
            "On2 VP8" to "vp8",
            "Alliance for Open Media AV1" to "av1",
        ).forEach { (desc, name) ->
            assertEquals(desc, name, OwnTVPlayer.ffmpegCodecName(desc))
            assertEquals(desc, true, OwnTVPlayer.hwdecCovers(desc))
        }
    }

    @Test
    fun `a long description outside the list is unknown, not a no`() {
        // MPEG-4 part 2's Microsoft variant is msmpeg4v3, which is not mpeg4.
        assertNull(OwnTVPlayer.hwdecCovers("MPEG-4 part 2 Microsoft variant version 3"))
        assertNull(OwnTVPlayer.hwdecCovers("Motion JPEG"))
    }
}
