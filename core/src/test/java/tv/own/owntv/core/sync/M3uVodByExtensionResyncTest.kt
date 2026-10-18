package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.parser.M3uEntry
import tv.own.owntv.core.parser.M3uParser

/**
 * A playlist synced before untagged film URLs were read as VOD has those films stored as live
 * channels. On its next sync they arrive as movies, and [M3uReclassifiedLive] must take them out of
 * Live TV while the real channels stay.
 */
class M3uVodByExtensionResyncTest {

    /** [M3uSyncer] joins name and group with this. */
    private val sep = "\u0001"

    private val playlist = listOf(
        "#EXTM3U",
        """#EXTINF:-1 group-title="News",News One""",
        "http://example.test/live/u/p/1.ts",
        """#EXTINF:-1 group-title="Films",Big Buck Bunny""",
        "http://example.test/movie/u/p/101.mp4",
        """#EXTINF:-1 group-title="Films",Sintel""",
        "http://example.test/movie/u/p/102.mkv?token=abc",
    ).joinToString("\n")

    private fun parse(): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(playlist.byteInputStream()) { out += it }
        out
    }

    private fun key(e: M3uEntry) = "${e.name}$sep${e.groupTitle.orEmpty()}"

    @Test
    fun filmsStoredAsLiveLeaveLiveTvOnResync() {
        val entries = parse()
        val storedLive = entries.map(::key)
        val seenLive = entries.filterNot { it.isVod }.mapTo(HashSet(), ::key)
        val seenAsVod = entries.filter { it.isVod }.mapTo(HashSet(), ::key)

        assertEquals(setOf("News One${sep}News"), seenLive)
        assertEquals(
            listOf("Big Buck Bunny${sep}Films", "Sintel${sep}Films"),
            M3uReclassifiedLive.staleKeys(storedLive, seenLive, seenAsVod),
        )
    }

    /** A film-only playlist has no live entries at all, the case the normal live prune skips. */
    @Test
    fun filmOnlyPlaylistEmptiesLiveTv() {
        val films = parse().filter { it.isVod }
        val storedLive = films.map(::key)
        val seenAsVod = films.mapTo(HashSet(), ::key)
        assertEquals(storedLive, M3uReclassifiedLive.staleKeys(storedLive, emptySet(), seenAsVod))
    }
}
