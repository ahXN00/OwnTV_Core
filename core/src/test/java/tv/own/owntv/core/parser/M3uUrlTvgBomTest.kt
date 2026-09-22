package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A UTF-8 byte-order mark before #EXTM3U must not hide the playlist header. */
class M3uUrlTvgBomTest {

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun parseHeader(bytes: ByteArray): M3uHeader = runBlocking {
        M3uParser().parse(bytes.inputStream()) { }
    }

    private fun parseEntries(bytes: ByteArray): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(bytes.inputStream()) { out += it }
        out
    }

    @Test
    fun urlTvgIsReadWithoutABom() {
        val text = "#EXTM3U url-tvg=\"http://x/epg.xml.gz\"\n#EXTINF:-1,Ch\nhttp://x/1\n"
        val header = parseHeader(text.toByteArray())
        assertEquals("http://x/epg.xml.gz", header.urlTvg)
    }

    @Test
    fun urlTvgSurvivesALeadingUtf8Bom() {
        val text = "#EXTM3U url-tvg=\"http://x/epg.xml.gz\"\n#EXTINF:-1,Ch\nhttp://x/1\n"
        val header = parseHeader(bom + text.toByteArray())
        assertEquals("http://x/epg.xml.gz", header.urlTvg)
    }

    @Test
    fun urlTvgSurvivesALeadingUtf8BomWithCrlfLineEndings() {
        val text = "#EXTM3U url-tvg=\"http://x/epg.xml.gz\"\r\n#EXTINF:-1,Ch\r\nhttp://x/1\r\n"
        val header = parseHeader(bom + text.toByteArray())
        assertEquals("http://x/epg.xml.gz", header.urlTvg)
    }

    @Test
    fun firstEntrySurvivesALeadingUtf8BomWithNoExtm3uLine() {
        val text = "#EXTINF:-1,Ch\nhttp://x/1\n"
        val entries = parseEntries(bom + text.toByteArray())
        assertEquals(1, entries.size)
        assertEquals("Ch", entries.single().name)
        assertEquals("http://x/1", entries.single().streamUrl)
    }
}
