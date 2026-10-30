package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderMergeTest {

    @Test
    fun `a move on one playlist keeps the other playlist's positions`() {
        val afterOne = mergeCategoryPositions(emptyMap(), listOf("1:b", "1:a", "1:c"), setOf("1:b"))
        val afterTwo = mergeCategoryPositions(afterOne, listOf("2:y", "2:x"), setOf("2:y"))
        assertEquals(mapOf("1:b" to 0, "2:y" to 0), afterTwo)
    }

    @Test
    fun `only carried and earlier moved categories get a position`() {
        assertEquals(
            mapOf("1:c" to 0, "1:b" to 2),
            mergeCategoryPositions(mapOf("1:b" to 0), listOf("1:c", "1:a", "1:b"), setOf("1:c")),
        )
    }

    @Test
    fun `a category moved again takes its new position, nothing is duplicated`() {
        assertEquals(
            mapOf("custom1" to 1, "2:x" to 4),
            mergeCategoryPositions(mapOf("custom1" to 0, "2:x" to 4), listOf("1:a", "custom1"), setOf("custom1")),
        )
    }
}
