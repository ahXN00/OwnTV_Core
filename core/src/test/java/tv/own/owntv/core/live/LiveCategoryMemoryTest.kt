package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCategoryMemoryTest {

    private data class Item(val key: LiveKey, val sealed: Boolean = false)

    private fun restore(stored: String, items: List<Item>) = liveRestoreKey(stored, items, { it.key }, { it.sealed })

    private val rail = listOf(
        Item(LiveKey.Favorites), Item(LiveKey.History), Item(LiveKey.Catchup), Item(LiveKey.All),
        Item(LiveKey.Folder(7)), Item(LiveKey.Custom("custom:abc")),
    )

    @Test
    fun `a folder in the list comes back`() {
        assertEquals(LiveKey.Folder(7), restore("FOLDER:7", rail))
        assertEquals(LiveKey.Custom("custom:abc"), restore("CUSTOM:custom:abc", rail))
    }

    @Test
    fun `a folder missing from the list or hidden stays away`() {
        assertNull(restore("FOLDER:8", rail))
        assertNull(restore("CUSTOM:custom:gone", rail))
    }

    @Test
    fun `a locked folder comes back only once opened this session`() {
        val locked = rail.map { if (it.key == LiveKey.Folder(7)) it.copy(sealed = true) else it }
        assertNull(restore("FOLDER:7", locked))
        // Unlocked again: the same stored value works, it was never erased.
        assertEquals(LiveKey.Folder(7), restore("FOLDER:7", rail))
    }

    @Test
    fun `a hidden All or Catch-up is not brought back`() {
        assertNull(restore("ALL", rail.filterNot { it.key == LiveKey.All }))
        assertNull(restore("CATCHUP", rail.filterNot { it.key == LiveKey.Catchup }))
        assertEquals(LiveKey.All, restore("ALL", rail))
        assertEquals(LiveKey.Catchup, restore("CATCHUP", rail))
    }

    @Test
    fun `Favorites and History always come back`() {
        assertEquals(LiveKey.Favorites, restore("FAV", emptyList()))
        assertEquals(LiveKey.History, restore("HIST", emptyList()))
    }

    @Test
    fun `nothing or garbage stored restores nothing`() {
        assertNull(restore("", rail))
        assertNull(restore("FOLDER:x", rail))
        assertNull(restore("SOMETHING", rail))
    }
}
