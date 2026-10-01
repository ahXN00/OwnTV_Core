package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerKeysTest {

    @Test
    fun oldSingleKeyIsAListOfOne() = assertEquals(listOf("B6Z9MM1LhEA"), TrailerKeys.split("B6Z9MM1LhEA"))

    @Test
    fun joinedKeysSplitInOrder() = assertEquals(listOf("a", "b", "c"), TrailerKeys.split("a,b,c"))

    @Test
    fun nullAndBlankAreEmpty() {
        assertEquals(emptyList<String>(), TrailerKeys.split(null))
        assertEquals(emptyList<String>(), TrailerKeys.split(" , "))
    }

    @Test
    fun joinDropsDuplicatesAndBlanks() = assertEquals("a,b", TrailerKeys.join(listOf("a", " ", "b", "a")))

    @Test
    fun joinKeepsAtMostMax() = assertEquals(TrailerKeys.MAX, TrailerKeys.split(TrailerKeys.join(List(9) { "k$it" })).size)

    @Test
    fun joinOfNothingIsNull() = assertNull(TrailerKeys.join(emptyList()))
}
