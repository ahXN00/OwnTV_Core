package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgTextTest {
    @Test
    fun `escaped line breaks become real ones`() {
        assertEquals(
            "Czechia vs England\nAt 29-9-2026, MECCA 21:45\nUEFA Nations League",
            EpgText.clean("""Czechia vs England\r\nAt 29-9-2026, MECCA 21:45\r\nUEFA Nations League\r\n"""),
        )
    }

    @Test
    fun `lone escaped n and r count too, and a run is one break`() {
        assertEquals("One\nTwo\nThree", EpgText.clean("""One\nTwo\r\r\nThree"""))
    }

    @Test
    fun `an escaped tab is a space and spaces collapse`() {
        assertEquals("Sport News", EpgText.clean("""Sport\t  News"""))
    }

    @Test
    fun `ordinary text is left alone`() {
        assertEquals("A sharp-witted idealist makes a wish.", EpgText.clean("A sharp-witted idealist makes a wish."))
    }

    @Test
    fun `nothing left means no synopsis`() {
        assertNull(EpgText.clean("""\r\n"""))
        assertNull(EpgText.clean(null))
    }
}
