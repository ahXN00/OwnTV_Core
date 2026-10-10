package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The typed text turned into an FTS MATCH expression. The index splits a name at every punctuation
 * mark ("Spider-Man" is the two words spider and man), so the query has to be split the same way.
 */
class SearchFtsQueryTest {

    @Test
    fun ftsQuery_splitsAtPunctuationLikeTheIndex() {
        // Was "SpiderM*" and "SpiderMan*": no indexed word starts with that, so nothing was found.
        assertEquals("Spider* M*", SearchReader.ftsQuery("Spider-M"))
        assertEquals("Spider* Man*", SearchReader.ftsQuery("Spider-Man"))
        assertEquals("Spider*", SearchReader.ftsQuery("Spider-"))
        assertEquals("Ocean* s* 11*", SearchReader.ftsQuery("Ocean's 11"))
    }

    @Test
    fun ftsQuery_keepsWordsAndLetters() {
        assertEquals("harry* pot*", SearchReader.ftsQuery("  harry   pot "))
        assertEquals("Pokémon*", SearchReader.ftsQuery("Pokémon"))
    }

    @Test
    fun ftsQuery_isNullWithNothingToMatch() {
        assertNull(SearchReader.ftsQuery("-- !"))
    }
}
