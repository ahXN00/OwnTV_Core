package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display name is what follows the first comma outside a quoted attribute value — never the
 * last one. A title with a comma of its own (`Movie, The (1999)`) is routine in VOD playlists, and
 * the M3U stable key is derived from the name, so truncating it orphaned favourites on every resync.
 */
class M3uDisplayNameTest {

    private fun parse(text: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(text.byteInputStream()) { out += it }
        out
    }

    private fun playlist(vararg lines: String): String = (listOf("#EXTM3U") + lines).joinToString("\n")

    @Test
    fun nameKeepsItsOwnCommas() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-id="m1" group-title="Movies",Movie, The (1999)""", "http://host/m1.mp4"))
        assertEquals("Movie, The (1999)", entries.single().name)
        assertEquals("Movies", entries.single().groupTitle)
    }

    @Test
    fun commaInsideAQuotedAttributeIsNotTheSeparator() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-id="a" group-title="News, Politics",Channel A""", "http://host/a.ts"))
        assertEquals("Channel A", entries.single().name)
        assertEquals("News, Politics", entries.single().groupTitle)
    }

    @Test
    fun quotedCommaAndNameCommaTogether() {
        val entries = parse(playlist("""#EXTINF:-1 group-title="Kids, Family" tvg-logo="http://h/l.png",Cat, The""", "http://host/c.mp4"))
        assertEquals("Cat, The", entries.single().name)
        assertEquals("Kids, Family", entries.single().groupTitle)
        assertEquals("http://h/l.png", entries.single().logo)
    }

    @Test
    fun noAttributesAtAll() {
        val entries = parse(playlist("#EXTINF:-1,Plain, Name", "http://host/p.ts"))
        assertEquals("Plain, Name", entries.single().name)
    }

    @Test
    fun whitespaceAroundTheSeparatorIsTrimmed() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-id="s" ,   Spaced Out   """, "http://host/s.ts"))
        assertEquals("Spaced Out", entries.single().name)
    }

    @Test
    fun missingSeparatorFallsBackToTvgName() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-id="n" tvg-name="From Attribute" group-title="G"""", "http://host/n.ts"))
        assertEquals("From Attribute", entries.single().name)
    }

    /** No separator and no `tvg-name`: there is nothing to call the entry, so it is dropped rather
     *  than listed under the raw `#EXTINF…` line, which is what the last-comma rule produced. */
    @Test
    fun missingSeparatorAndNoTvgNameDropsTheEntry() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-id="x"""", "http://host/x.ts", "#EXTINF:-1,Next", "http://host/next.ts"))
        assertEquals(listOf("Next"), entries.map { it.name })
        assertTrue(entries.single().streamUrl.endsWith("next.ts"))
    }

    /** An unbalanced quote makes every later comma look quoted. The line is broken either way, so it
     *  keeps the last-comma reading it always had instead of disappearing from the catalog. */
    @Test
    fun unbalancedQuoteKeepsTheOldLastCommaReading() {
        val entries = parse(playlist("""#EXTINF:-1 tvg-name="Broken,Still There""", "http://host/b.ts"))
        assertEquals("Still There", entries.single().name)
    }

    /** The separator is there, but nothing follows it — the same as no separator at all: fall back
     *  to `tvg-name` rather than listing the entry under an empty title. */
    @Test
    fun blankTailFallsBackToTvgName() {
        val entries = parse(playlist("#EXTINF:-1 tvg-id=\"t\" tvg-name=\"Real Name\",", "http://host/r.ts"))
        assertEquals("Real Name", entries.single().name)
    }

    /** A blank tail and no `tvg-name` leaves nothing to call the entry, so it is dropped rather than
     *  listed under an empty title. */
    @Test
    fun blankTailWithoutTvgNameDropsTheEntry() {
        val entries = parse(
            playlist(
                "#EXTINF:-1 tvg-id=\"t\",",
                "http://host/t.ts",
                "#EXTINF:-1,Next",
                "http://host/next.ts",
            ),
        )
        assertEquals(listOf("Next"), entries.map { it.name })
        assertTrue(entries.single().streamUrl.endsWith("next.ts"))
    }
}
