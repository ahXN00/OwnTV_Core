package tv.own.owntv.core.customize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored form of a section's customizations: category locks survive a save, and older saves still load. */
class CustomizationJsonTest {

    @Test
    fun `locked categories survive a save and load`() {
        val c = SectionCustomizations(
            hiddenCategories = setOf("1:news"),
            lockedCategories = setOf("1:adult", "custom:abc"),
        )
        val back = CustomizationJson.parse(CustomizationJson.serialize(c))
        assertEquals(setOf("1:adult", "custom:abc"), back.lockedCategories)
        assertEquals(setOf("1:news"), back.hiddenCategories)
    }

    @Test
    fun `a save from before locks loads with no locks`() {
        val old = JSONObject()
            .put("hiddenCats", org.json.JSONArray(listOf("1:news")))
            .put("catNames", JSONObject().put("1:sport", "Sport"))
            .toString()
        val c = CustomizationJson.parse(old)
        assertTrue(c.lockedCategories.isEmpty())
        assertEquals(setOf("1:news"), c.hiddenCategories)
        assertEquals("Sport", c.categoryNames["1:sport"])
    }

    @Test
    fun `what this session has open is never written`() {
        val c = SectionCustomizations(
            lockedCategories = setOf("1:adult"),
            sealedCategories = setOf("1:adult"),
            sealedCategoryIds = setOf(7L),
            sealHidden = setOf("1:adult"),
        )
        val json = JSONObject(CustomizationJson.serialize(c))
        assertTrue(json.has("lockedCats"))
        assertFalse(json.keys().asSequence().any { it.startsWith("sealed") })
        val back = CustomizationJson.parse(json.toString())
        assertTrue(back.sealedCategories.isEmpty())
        assertTrue(back.sealedCategoryIds.isEmpty())
        assertTrue(back.sealHidden.isEmpty())
    }

    @Test
    fun `a section holding only a lock is not empty`() {
        assertFalse(SectionCustomizations(lockedCategories = setOf("1:adult")).isEmpty)
    }
}
