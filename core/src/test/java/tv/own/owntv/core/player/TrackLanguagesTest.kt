package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** N14: the shared preferred-language list and the "Original language" choice. */
class TrackLanguagesTest {

    @Test
    fun `every code is a distinct 639-2 code the platform can name`() {
        assertEquals(TrackLanguages.CODES.size, TrackLanguages.CODES.toSet().size)
        TrackLanguages.CODES.forEach { code ->
            assertEquals(3, code.length)
            assertTrue(code, TrackLanguages.displayName(code, Locale.ENGLISH) != code)
        }
    }

    @Test
    fun `the old fourteen languages are all still offered`() {
        val old = listOf("eng", "spa", "fra", "deu", "ita", "por", "nld", "rus", "ara", "hin", "zho", "jpn", "kor", "tur")
        assertTrue(TrackLanguages.CODES.containsAll(old))
    }

    @Test
    fun `names come in the display language`() {
        assertEquals("German", TrackLanguages.displayName("deu", Locale.ENGLISH))
        assertEquals("Deutsch", TrackLanguages.displayName("deu", Locale.GERMAN))
    }

    @Test
    fun `original is never handed to an engine as a language`() {
        assertEquals("", TrackLanguages.forEngine(TrackLanguages.ORIGINAL))
        assertEquals("deu", TrackLanguages.forEngine("deu"))
        assertEquals("", TrackLanguages.forEngine(""))
    }

    @Test
    fun `a TMDB language becomes the code tracks carry`() {
        assertEquals("kor", TrackLanguages.fromIso6391("ko"))
        assertEquals("deu", TrackLanguages.fromIso6391("de"))
        assertNull(TrackLanguages.fromIso6391(null))
        assertNull(TrackLanguages.fromIso6391(""))
    }

    @Test
    fun `two-letter form`() {
        assertEquals("de", TrackLanguages.twoLetter("deu"))
        assertEquals("zh", TrackLanguages.twoLetter("zho"))
    }
}
