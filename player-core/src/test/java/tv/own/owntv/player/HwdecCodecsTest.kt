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
}
