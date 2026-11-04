package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which HLS variants "Prefer SDR" keeps: the SDR ones, and only when the stream offers both. */
class SdrVariantsTest {

    private val mixed = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=20000000,CODECS="hvc1.2.4.L153",RESOLUTION=3840x2160,VIDEO-RANGE=PQ,AUDIO="aac"
        hdr/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=18000000,CODECS="hvc1.2.4.L153",RESOLUTION=3840x2160,VIDEO-RANGE=HLG,AUDIO="aac"
        hlg/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=12000000,CODECS="hvc1.1.6.L150",RESOLUTION=3840x2160,VIDEO-RANGE=SDR,AUDIO="aac"
        sdr/2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028",RESOLUTION=1920x1080,AUDIO="aac"
        sdr/1080.m3u8
    """.trimIndent()

    @Test
    fun `a stream with both keeps its SDR variants, unlabelled ones included`() {
        assertEquals(setOf("sdr/2160.m3u8", "sdr/1080.m3u8"), SdrVariants.sdrUris(mixed))
    }

    @Test
    fun `an HDR-only stream is left alone`() {
        val hdrOnly = mixed.lines().filterNot { it.startsWith("sdr/") || it.contains("VIDEO-RANGE=SDR") || it.contains("avc1") }
            .joinToString("\n")
        assertNull(SdrVariants.sdrUris(hdrOnly))
    }

    @Test
    fun `an SDR-only stream is left alone`() {
        val sdrOnly = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,VIDEO-RANGE=SDR\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nb.m3u8\n"
        assertNull(SdrVariants.sdrUris(sdrOnly))
    }

    @Test
    fun `a media playlist is left alone`() {
        assertNull(SdrVariants.sdrUris("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"))
    }

    @Test
    fun `comments and blank lines between the tag and its address are skipped`() {
        val text = "#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=9,VIDEO-RANGE=PQ\r\n\r\n#EXT-X-SOMETHING\r\nh.m3u8\r\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3,VIDEO-RANGE=SDR\r\ns.m3u8\r\n"
        assertEquals(setOf("s.m3u8"), SdrVariants.sdrUris(text))
    }
}
