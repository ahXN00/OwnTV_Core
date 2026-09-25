package tv.own.owntv.core.timeshift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P16a — which HLS variant a timeshift buffer saves. */
class HlsMasterTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        low.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
        mid.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
        high.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,AUDIO="aud"
        separate.m3u8
    """.trimIndent()

    @Test
    fun `the best variant with its own sound, within the quality limit`() {
        assertEquals("high.m3u8", HlsMaster.choose(master, maxHeight = null)?.uri)
        assertEquals("mid.m3u8", HlsMaster.choose(master, maxHeight = 720)?.uri)
        // Nothing within the limit: the smallest.
        assertEquals("low.m3u8", HlsMaster.choose(master, maxHeight = 240)?.uri)
    }

    @Test
    fun `only separate sound is refused`() {
        val onlySeparate = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="x",URI="a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="a"
            v.m3u8
        """.trimIndent()
        assertNull(HlsMaster.choose(onlySeparate, null))
    }

    @Test
    fun `init segment and quoted attributes`() {
        assertEquals("init.mp4?x=1,2", HlsMaster.mapUri("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4?x=1,2\"\n#EXTINF:2,\na.m4s"))
        assertNull(HlsMaster.mapUri("#EXTM3U\n#EXTINF:2,\na.ts"))
    }
}
