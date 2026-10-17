package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Move up / Move down on the EPG Sources list, whose order is the guide priority. */
class EpgSourceOrderTest {

    private val list = listOf(
        EpgSource(id = -1, name = "Guide A", url = "https://a.example/epg.xml"),
        EpgSource(id = -2, name = "Guide B", url = "https://b.example/epg.xml"),
        EpgSource(id = -3, name = "Guide C", url = "https://c.example/epg.xml"),
    )

    private fun ids(sources: List<EpgSource>?) = sources?.map { it.id }

    @Test
    fun `move up swaps with the source above`() {
        assertEquals(listOf(-1L, -3L, -2L), ids(movedEpgSource(list, -3, up = true)))
    }

    @Test
    fun `move down swaps with the source below`() {
        assertEquals(listOf(-2L, -1L, -3L), ids(movedEpgSource(list, -1, up = false)))
    }

    @Test
    fun `nothing to write at either end`() {
        assertNull(movedEpgSource(list, -1, up = true))
        assertNull(movedEpgSource(list, -3, up = false))
    }

    @Test
    fun `an id that is not on the list changes nothing`() {
        assertNull(movedEpgSource(list, -9, up = true))
    }

    @Test
    fun `a move keeps every field of the moved source`() {
        val withState = list.map { it.copy(userAgent = "UA ${it.id}", lastSyncAt = 5L, lastError = null) }
        assertEquals(withState.toSet(), movedEpgSource(withState, -2, up = true)?.toSet())
    }
}
