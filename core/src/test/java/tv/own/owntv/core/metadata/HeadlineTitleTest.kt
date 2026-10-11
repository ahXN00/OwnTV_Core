package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadlineTitleTest {

    @Test
    fun dropsATagBetweenBars() = assertEquals("Trailer Park Boys (2001)", HeadlineTitle.of("|MULTI| Trailer Park Boys (2001)"))

    @Test
    fun dropsATagBeforeABar() = assertEquals("The Movie Name (2021) [HD]", HeadlineTitle.of("EN| The Movie Name (2021) [HD]"))

    @Test
    fun dropsStackedTags() = assertEquals("Dark", HeadlineTitle.of("|EN| 4K | Dark"))

    @Test
    fun keepsDashPrefixesThatTellCopiesApart() =
        assertEquals("4K-NF - Musafir Cafe (2026) (IN)", HeadlineTitle.of("4K-NF - Musafir Cafe (2026) (IN)"))

    @Test
    fun keepsABarInsideTheName() = assertEquals("Sky | Cinema", HeadlineTitle.of("Sky | Cinema"))

    @Test
    fun keepsATitleCaseWordBeforeABar() = assertEquals("Love | Death", HeadlineTitle.of("Love | Death"))

    @Test
    fun tagOnlyNameKeepsTheProviderSpelling() = assertEquals("|MULTI|", HeadlineTitle.of("|MULTI|"))
}
