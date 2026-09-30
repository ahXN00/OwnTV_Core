package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvDetailRulesTest {

    @Test fun `year from the common date forms`() {
        assertEquals(2023, XmltvDetailRules.year("2023"))
        assertEquals(2026, XmltvDetailRules.year("20260930"))
        assertEquals(1999, XmltvDetailRules.year(" 19991231120000 +0100"))
    }

    @Test fun `no year from short or implausible dates`() {
        assertNull(XmltvDetailRules.year("99"))
        assertNull(XmltvDetailRules.year("0000"))
        assertNull(XmltvDetailRules.year(""))
        assertNull(XmltvDetailRules.year(null))
    }

    @Test fun `numeric rating takes its system`() {
        assertEquals("FSK 16", XmltvDetailRules.rating("FSK", "16"))
        assertEquals("FSK 0", XmltvDetailRules.rating(" FSK ", " 0 "))
        assertEquals("PEGI 12+", XmltvDetailRules.rating("PEGI", "12+"))
    }

    @Test fun `named rating is kept as written`() {
        assertEquals("TV-MA", XmltvDetailRules.rating("VCHIP", "TV-MA"))
        assertEquals("FSK 16", XmltvDetailRules.rating(null, "FSK 16"))
        assertEquals("16", XmltvDetailRules.rating(null, "16"))
        assertNull(XmltvDetailRules.rating("FSK", " "))
    }

    @Test fun `length converts to minutes`() {
        assertEquals(95, XmltvDetailRules.lengthMinutes("95", "minutes"))
        assertEquals(95, XmltvDetailRules.lengthMinutes("95", null))
        assertEquals(90, XmltvDetailRules.lengthMinutes("5400", "seconds"))
        assertEquals(120, XmltvDetailRules.lengthMinutes("2", "hours"))
        assertNull(XmltvDetailRules.lengthMinutes("30", "seconds"))
        assertNull(XmltvDetailRules.lengthMinutes("abc", "minutes"))
        assertNull(XmltvDetailRules.lengthMinutes("0", "minutes"))
    }

    @Test fun `xmltv_ns episode is one-based`() {
        assertEquals("S1 E3", XmltvDetailRules.episode("xmltv_ns", "0.2.0/1"))
        assertEquals("S5 E12", XmltvDetailRules.episode("xmltv_ns", "4/10 . 11/24 ."))
        assertEquals("E7", XmltvDetailRules.episode("xmltv_ns", ".6."))
        assertEquals("S2", XmltvDetailRules.episode("xmltv_ns", "1.."))
        assertNull(XmltvDetailRules.episode("xmltv_ns", ".."))
    }

    @Test fun `onscreen episode kept, other systems ignored`() {
        assertEquals("S01E03", XmltvDetailRules.episode("onscreen", " S01E03 "))
        assertNull(XmltvDetailRules.episode("dd_progid", "EP012345"))
        assertNull(XmltvDetailRules.episode(null, "S01E03"))
    }

    @Test fun `categories are distinct, trimmed and capped`() {
        val joined = XmltvDetailRules.categories(listOf("Animation", " animation ", "", "Family", "Kids, Teens", "Comedy"))
        assertEquals(listOf("Animation", "Family", "Kids, Teens"), EpgDetails(categories = joined).categoryList)
        assertNull(XmltvDetailRules.categories(listOf(" ", "")))
    }
}
