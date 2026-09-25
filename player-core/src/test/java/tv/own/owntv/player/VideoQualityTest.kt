package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N11 — the Settings limit, the mobile-data limit and the Quality-menu pick. */
class VideoQualityTest {

    @Test
    fun `the mobile-data limit counts only on a metered connection, and the lower limit wins`() {
        assertNull(VideoQuality.cap(maxHeight = 0, mobileDataMaxHeight = 720, metered = false))
        assertEquals(720, VideoQuality.cap(maxHeight = 0, mobileDataMaxHeight = 720, metered = true))
        assertEquals(480, VideoQuality.cap(maxHeight = 480, mobileDataMaxHeight = 720, metered = true))
        assertEquals(1080, VideoQuality.cap(maxHeight = 1080, mobileDataMaxHeight = 0, metered = true))
    }

    @Test
    fun `an engine's own limit can only lower the result`() {
        assertEquals(360, VideoQuality.lower(360, 1080))
        assertEquals(720, VideoQuality.lower(null, 720))
        assertNull(VideoQuality.lower(null, null))
    }

    @Test
    fun `the menu lists distinct heights, highest first, and only when there is a choice`() {
        assertEquals(listOf(1080, 720, 360), VideoQuality.heights(listOf(720, 1080, 360, 720, 0)))
        assertEquals(emptyList<Int>(), VideoQuality.heights(listOf(1080, 1080)))
    }

    @Test
    fun `mpv takes the pick, else the tallest within the limit, else the smallest`() {
        val tracks = listOf(1 to 1080, 2 to 720, 3 to 480)
        assertEquals(3, VideoQuality.mpvTrack(tracks, cap = 1080, pick = 480))
        assertEquals(2, VideoQuality.mpvTrack(tracks, cap = 720, pick = null))
        assertEquals(3, VideoQuality.mpvTrack(tracks, cap = 360, pick = null))
        assertNull(VideoQuality.mpvTrack(tracks, cap = null, pick = null))
        assertNull(VideoQuality.mpvTrack(listOf(1 to 1080, 2 to 0), cap = 720, pick = null))
    }
}
