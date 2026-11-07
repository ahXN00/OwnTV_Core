package tv.own.owntv.core.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.ContentMenu

class ContentMenusTest {

    private fun keys(menu: ContentMenu) = catalogue(menu).map { it.key }

    @Test
    fun `nothing saved puts a popup built in any order into the catalogue order`() {
        ContentMenu.entries.forEach { menu ->
            val shipped = keys(menu)
            assertEquals(shipped, arrange(menu, shipped.reversed(), MenuLayout()) { it })
        }
    }

    @Test
    fun `a popup with only some actions keeps the catalogue order of those`() {
        val shown = listOf("favourite", "record", "catchup")
        assertEquals(listOf("catchup", "record", "favourite"), arrange(ContentMenu.LIVE, shown, MenuLayout()) { it })
    }

    @Test
    fun `the live catalogue starts with what the popup puts first`() {
        assertEquals(listOf("programme_details", "catchup", "record", "schedule_record"), keys(ContentMenu.LIVE).take(4))
    }

    @Test
    fun `the phone's list leaves out the TV-only actions`() {
        assertEquals(keys(ContentMenu.LIVE).drop(1), phoneCatalogue(ContentMenu.LIVE).map { it.key })
        assertEquals(catalogue(ContentMenu.MOVIE), phoneCatalogue(ContentMenu.MOVIE))
    }

    @Test
    fun `an order saved without the TV-only action still counts as shipped`() {
        val phoneOrder = phoneCatalogue(ContentMenu.LIVE).map { it.key }
        assertTrue(MenuLayout(order = phoneOrder).isShippedOrder(ContentMenu.LIVE))
    }

    @Test
    fun `a saved order wins, an unknown saved key is ignored and a new action is appended`() {
        val layout = MenuLayout(order = listOf("hide", "gone_action", "favourite"))
        assertEquals(
            listOf("hide", "favourite", "catchup", "record"),
            arrange(ContentMenu.LIVE, listOf("catchup", "favourite", "record", "hide"), layout) { it },
        )
    }

    @Test
    fun `hidden actions are left out`() {
        val layout = MenuLayout(hidden = setOf("record", "rename"))
        assertEquals(listOf("catchup", "favourite"), arrange(ContentMenu.LIVE, listOf("favourite", "rename", "record", "catchup"), layout) { it })
    }

    @Test
    fun `a profile without downloads has no Download action`() {
        val layout = MenuLayout(downloads = false)
        listOf(ContentMenu.MOVIE, ContentMenu.SERIES, ContentMenu.EPISODE).forEach { menu ->
            assertFalse(DOWNLOAD_ACTION in arrange(menu, keys(menu), layout) { it })
            assertTrue(DOWNLOAD_ACTION in arrange(menu, keys(menu), MenuLayout()) { it })
        }
    }

    @Test
    fun `an order equal to the catalogue is still the shipped order`() {
        val menu = ContentMenu.MOVIE
        assertTrue(MenuLayout().isShippedOrder(menu))
        assertTrue(MenuLayout(order = keys(menu)).isShippedOrder(menu))
        assertFalse(MenuLayout(order = keys(menu)).isCustom(menu))
        assertFalse(MenuLayout(order = listOf("favourite")).isShippedOrder(menu))
    }

    @Test
    fun `hiding an action makes the menu custom but keeps its headings`() {
        val layout = MenuLayout(hidden = setOf("hide"))
        assertTrue(layout.isCustom(ContentMenu.SERIES))
        assertTrue(layout.isShippedOrder(ContentMenu.SERIES))
    }

    @Test
    fun `every catalogue key is unique`() {
        ContentMenu.entries.forEach { menu -> assertEquals(keys(menu).size, keys(menu).toSet().size) }
    }
}
