package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An M3U entry first imported as a live channel and later tagged `tvg-type="movie"` must leave
 * Live TV, while channels that simply vanished from the parse stay with the normal guarded prune.
 * See `M3uReclassifiedLive`.
 */
class M3uReclassifiedLiveTest {

    /** [M3uSyncer] joins name and group with this. */
    private val sep = "\u0001"

    @Test
    fun liveRowsNowDeliveredAsVodAreStale() {
        val stored = listOf("Clip 1${sep}Videos", "Clip 2${sep}Videos")
        val vod = setOf("Clip 1${sep}Videos", "Clip 2${sep}Videos")
        assertEquals(stored, M3uReclassifiedLive.staleKeys(stored, seenLive = emptySet(), seenAsVod = vod))
    }

    /** A channel missing from this parse is not reclassified; the guarded normal prune decides. */
    @Test
    fun vanishedChannelsAreLeftToTheNormalPrune() {
        val stored = listOf("BBC One${sep}News", "Clip 1${sep}Videos")
        val out = M3uReclassifiedLive.staleKeys(stored, seenLive = emptySet(), seenAsVod = setOf("Clip 1${sep}Videos"))
        assertEquals(listOf("Clip 1${sep}Videos"), out)
    }

    /** The same name+group still listed as live (alongside a VOD copy) keeps its live row. */
    @Test
    fun keyStillSeenAsLiveIsKept() {
        val key = "Clip 1${sep}Videos"
        assertEquals(emptyList<String>(), M3uReclassifiedLive.staleKeys(listOf(key), setOf(key), setOf(key)))
    }

    /** Duplicates carry the `#n` suffix in both live and VOD keys, so each copy is matched. */
    @Test
    fun duplicateKeysMatchBySuffix() {
        val stored = listOf("Clip${sep}Videos", "Clip${sep}Videos${sep}#2")
        val out = M3uReclassifiedLive.staleKeys(stored, emptySet(), setOf("Clip${sep}Videos", "Clip${sep}Videos${sep}#2"))
        assertEquals(stored, out)
    }
}
