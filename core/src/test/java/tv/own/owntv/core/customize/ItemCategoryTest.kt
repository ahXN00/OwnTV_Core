package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row whose item a re-sync left unresolved stays sealed until every lock of the profile is open. */
class ItemCategoryTest {

    private val lockedKey = "1:adult"
    private val sports = 1L
    private val adult = 3L

    /** The browse view of a section after [withLocks], with the sealed folder resolved to its id. */
    private fun section(locked: Set<String>, unlocked: Set<String> = emptySet()): SectionCustomizations {
        val c = SectionCustomizations(lockedCategories = locked).withLocks(hasPin = true, unlocked = unlocked)
        return if (lockedKey in c.sealedCategories) c.copy(sealedCategoryIds = setOf(adult)) else c
    }

    private fun SectionCustomizations.anySealed() = sealedCategories.isNotEmpty()

    private fun SectionCustomizations.decide(item: ItemCategory) = sealsItem(item, anySealed())

    @Test
    fun `with no locks nothing is sealed, found or not`() {
        val c = section(locked = emptySet())
        assertFalse(c.decide(ItemCategory.Found(adult)))
        assertFalse(c.decide(ItemCategory.Found(null)))
        assertFalse(c.decide(ItemCategory.Unresolved))
    }

    @Test
    fun `while a lock is closed a found item follows its category and an unresolved one is sealed`() {
        val c = section(locked = setOf(lockedKey))
        assertTrue(c.decide(ItemCategory.Found(adult)))
        assertFalse(c.decide(ItemCategory.Found(sports)))
        assertFalse(c.decide(ItemCategory.Found(null)))
        assertTrue(c.decide(ItemCategory.Unresolved))
    }

    @Test
    fun `once every lock is open everything shows as before`() {
        val c = section(locked = setOf(lockedKey), unlocked = setOf(lockedKey))
        assertFalse(c.decide(ItemCategory.Found(adult)))
        assertFalse(c.decide(ItemCategory.Found(sports)))
        assertFalse(c.decide(ItemCategory.Unresolved))
    }

    @Test
    fun `an unresolved item counts a closed lock of any section`() {
        val noLocksHere = section(locked = emptySet())
        assertTrue(noLocksHere.sealsItem(ItemCategory.Unresolved, anySealed = true))
        assertFalse(noLocksHere.sealsItem(ItemCategory.Found(sports), anySealed = true))
    }

    @Test
    fun `a found item's category is only asked about when it has one`() {
        val asked = mutableListOf<Long>()
        val decide = { item: ItemCategory -> item.isSealed({ error("only for unresolved items") }) { asked += it; true } }
        assertTrue(decide(ItemCategory.Found(adult)))
        assertFalse(decide(ItemCategory.Found(null)))
        assertEquals(listOf(adult), asked)
    }
}
