package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** A playlist header may advertise several XMLTV feeds in one comma-separated `url-tvg` (issue #171). */
class SplitGuideUrlsTest {

    @Test
    fun singleUrlIsUnchanged() {
        assertEquals(listOf("https://example.com/guide.xml.gz"), splitGuideUrls(" https://example.com/guide.xml.gz "))
    }

    @Test
    fun commaSeparatedFeedsBecomeSeparateUrls() {
        assertEquals(
            listOf("https://example.com/guide1.xml.gz", "https://example.com/guide2.xml.gz"),
            splitGuideUrls("https://example.com/guide1.xml.gz, https://example.com/guide2.xml.gz"),
        )
    }

    @Test
    fun duplicatesAreDropped() {
        assertEquals(
            listOf("http://example.com/g.xml"),
            splitGuideUrls("http://example.com/g.xml,http://example.com/g.xml"),
        )
    }

    @Test
    fun commaInsideOneUrlIsNotASeparator() {
        val url = "https://example.com/guide.php?ids=1,2,3"
        assertEquals(listOf(url), splitGuideUrls(url))
    }

    @Test
    fun nonHttpMarkerUrlIsNeverSplit() {
        val marker = "owntv-stalker://7,portal"
        assertEquals(listOf(marker), splitGuideUrls(marker))
    }

    @Test
    fun blankIsNoGuide() {
        assertEquals(emptyList<String>(), splitGuideUrls(null))
        assertEquals(emptyList<String>(), splitGuideUrls("   "))
    }
}
