package tv.own.owntv.core.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two playlists never share a name: an unnamed second "My IPTV" becomes "My IPTV 2". */
class PlaylistNameNumberingTest {

    private val numbered = { base: String, n: Int -> "$base $n" }

    private fun name(base: String, vararg taken: String) =
        SourceImporter.numberedName(base, taken.toList(), numbered)

    @Test
    fun `a free name is kept`() {
        assertEquals("My IPTV", name("My IPTV"))
        assertEquals("My IPTV", name("My IPTV", "Portal", "Playlist"))
    }

    @Test
    fun `a taken name gets the first free number`() {
        assertEquals("My IPTV 2", name("My IPTV", "My IPTV"))
        assertEquals("My IPTV 3", name("My IPTV", "My IPTV", "My IPTV 2"))
        assertEquals("My IPTV 2", name("My IPTV", "My IPTV", "My IPTV 3"))
    }

    @Test
    fun `matching ignores case, also for a typed duplicate`() {
        assertEquals("my iptv 2", name("my iptv", "My IPTV"))
        assertEquals("Sky 3", name("Sky", "SKY", "sky 2"))
    }
}
