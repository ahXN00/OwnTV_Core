package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBuiltInsTest {

    private data class Row(val key: LiveKey, val sealed: Boolean = false)

    private fun land(vararg rows: Row) = liveLandingKey(rows.toList(), { it.key }, { it.sealed })

    @Test
    fun `All is the landing while it is listed`() {
        assertEquals(LiveKey.All, land(Row(LiveKey.Favorites), Row(LiveKey.History), Row(LiveKey.All), Row(LiveKey.Folder(4))))
    }

    @Test
    fun `without All the first category that opens without a PIN is the landing`() {
        assertEquals(
            LiveKey.Custom("custom:a"),
            land(Row(LiveKey.Favorites), Row(LiveKey.Catchup), Row(LiveKey.Folder(1), sealed = true), Row(LiveKey.Custom("custom:a")), Row(LiveKey.Folder(2))),
        )
    }

    @Test
    fun `with no open category Favorites is the landing`() {
        assertEquals(LiveKey.Favorites, land(Row(LiveKey.Favorites), Row(LiveKey.History), Row(LiveKey.Folder(1), sealed = true)))
    }

    @Test
    fun `only the two built-ins can be hidden`() {
        val none = LiveBuiltIns(showAll = false, showCatchup = false)
        assertFalse(none.shows(LiveKey.All))
        assertFalse(none.shows(LiveKey.Catchup))
        listOf(LiveKey.Favorites, LiveKey.History, LiveKey.Folder(3), LiveKey.Custom("x")).forEach { assertTrue(none.shows(it)) }
        assertTrue(LiveBuiltIns().shows(LiveKey.All))
    }
}
