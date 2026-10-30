package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

/**
 * The rail and a category move must build the same list — the reason this file exists. Both apps
 * used to order categories twice, once for the rail and once for the move, and a move that walks a
 * different list reorders something the user is not looking at.
 */
class CategoryOrderingTest {

    private fun cat(id: Long, name: String) =
        CategoryEntity(id = id, sourceId = 1, mediaType = MediaType.LIVE, name = name, remoteId = "r$id")

    private val cats = listOf(cat(1, "Sports"), cat(2, "News"), cat(3, "Adult"), cat(4, "Movies"))

    private fun keyOf(id: Long) = CustomizeKeys.category(cats.first { it.id == id })

    @Test
    fun `rail keeps playlist order and applies hides and renames`() {
        val c = SectionCustomizations(
            hiddenCategories = setOf(keyOf(2)),
            categoryNames = mapOf(keyOf(1) to "Sport"),
        )
        val rows = cats.railCategories(c, kids = false)
        assertEquals(listOf("Sport", "Adult", "Movies"), rows.map { it.displayName })
    }

    @Test
    fun `a kids profile never sees an adult category`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = true)
        assertEquals(listOf("Sports", "News", "Movies"), rows.map { it.displayName })
    }

    @Test
    fun `a moved category keeps its position, the rest follow provider order`() {
        val c = SectionCustomizations(
            customCategories = listOf(CustomCategory(id = "custom:x", name = "Mine")),
            categoryPositions = mapOf(keyOf(4) to 0),
        )
        val rows = cats.railCategories(c, kids = false)
        assertEquals(listOf("Movies", "Mine", "Sports", "News", "Adult"), rows.map { it.displayName })
    }

    @Test
    fun `A-Z sorts only the categories never moved, by shown name`() {
        val c = SectionCustomizations(
            customCategories = listOf(CustomCategory(id = "custom:x", name = "Mine")),
            categoryNames = mapOf(keyOf(1) to "Football"),
            categoryPositions = mapOf(keyOf(2) to 1),
        )
        val rows = cats.railCategories(c, kids = false, alphabetical = true)
        assertEquals(listOf("Adult", "News", "Football", "Mine", "Movies"), rows.map { it.displayName })
    }

    @Test
    fun `a move pins only the carried category, so the rest still follow the sort`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = false)
        val moved = CategoryMove.begin(rows, keyOf(1))!!.moved(MoveKind.DOWN)!!.moved(MoveKind.DOWN)!!
        assertEquals(setOf(keyOf(1)), moved.carried)
        val c = SectionCustomizations(categoryPositions = pinnedAfterMove(moved.keys, emptySet(), moved.carried))

        assertEquals(mapOf(keyOf(1) to 2), c.categoryPositions)
        assertEquals(listOf("News", "Adult", "Sports", "Movies"), cats.railCategories(c, kids = false).map { it.displayName })
        assertEquals(listOf("Adult", "Movies", "Sports", "News"), cats.railCategories(c, kids = false, alphabetical = true).map { it.displayName })
    }

    @Test
    fun `an older saved category list is dropped, positions round-trip`() {
        val old = """{"catOrder":["${keyOf(4)}","${keyOf(1)}"]}"""
        assertEquals(emptyMap<String, Int>(), CustomizationJson.parse(old).categoryPositions)
        val c = SectionCustomizations(categoryPositions = mapOf(keyOf(4) to 2))
        assertEquals(c.categoryPositions, CustomizationJson.parse(CustomizationJson.serialize(c)).categoryPositions)
    }

    @Test
    fun `placePinned puts each pinned item at its position, past the end last`() {
        assertEquals(listOf("x", "a", "y", "b", "z"), placePinned(listOf("a", "b"), listOf("y" to 2, "x" to 0, "z" to 9)))
        assertEquals(listOf("a", "b"), placePinned(listOf("a", "b"), emptyList<Pair<String, Int>>()))
    }

    @Test
    fun `a move walks the rail's own list, and committing it reproduces that order`() {
        val c = SectionCustomizations()
        val rows = cats.railCategories(c, kids = false)
        val move = CategoryMove.begin(rows, keyOf(4))!!

        assertEquals(rows.map { it.key }, move.keys)
        assertEquals(3, move.activeIndex)

        val up = move.moved(MoveKind.UP)!!.moved(MoveKind.UP)!!
        assertEquals(listOf("Sports", "Movies", "News", "Adult"), up.items)
        assertEquals(1, up.activeIndex)

        // What the store is given must be what the rail then shows.
        val committed = c.copy(categoryPositions = pinnedAfterMove(up.keys, emptySet(), up.carried))
        assertEquals(up.items, cats.railCategories(committed, kids = false).map { it.displayName })
    }

    @Test
    fun `a move off either end is a no-op, and a category that is not on the rail cannot move`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = false)
        assertNull(CategoryMove.begin(rows, keyOf(1))!!.moved(MoveKind.UP))
        assertNull(CategoryMove.begin(rows, keyOf(4))!!.moved(MoveKind.DOWN))
        assertNull(CategoryMove.begin(rows, "custom:gone"))
    }

    @Test
    fun `move to top and bottom reach the ends`() {
        val rows = cats.railCategories(SectionCustomizations(), kids = false)
        val top = CategoryMove.begin(rows, keyOf(3))!!.moved(MoveKind.TOP)!!
        assertEquals(listOf("Adult", "Sports", "News", "Movies"), top.items)
        assertEquals(0, top.activeIndex)

        val bottom = CategoryMove.begin(rows, keyOf(1))!!.moved(MoveKind.BOTTOM)!!
        assertEquals(listOf("News", "Adult", "Movies", "Sports"), bottom.items)
        assertEquals(3, bottom.activeIndex)
    }
}
