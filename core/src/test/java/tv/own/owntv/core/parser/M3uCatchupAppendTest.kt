package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.epg.CatchupUrl
import java.util.TimeZone

/** Issue #272: `catchup="append"` with a `${(b)…}-${(e)…}` playseek template survives parse and playback. */
class M3uCatchupAppendTest {

    private fun parse(text: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(text.byteInputStream()) { out += it }
        out
    }

    @Test
    fun appendPlayseekSourceIsKeptAndBuildsTheArchiveUrl() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" catchup="append" catchup-days="7" catchup-source="?playseek=${'$'}{(b)yyyyMMddHHmmss}-${'$'}{(e)yyyyMMddHHmmss}",CCTV1
            http://h/cctv1.m3u8
            """.trimIndent(),
        )
        val entry = entries.single()
        assertEquals("append", entry.catchup)
        assertEquals(7, entry.catchupDays)
        assertEquals("?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}", entry.catchupSource)
        assertEquals(
            "http://h/cctv1.m3u8?playseek=20261017143000-20261017150000",
            CatchupUrl.forM3u(
                entry.streamUrl,
                entry.catchup,
                entry.catchupSource,
                1792247400_000L,
                1792249200_000L,
                TimeZone.getTimeZone("UTC"),
            ),
        )
    }
}
