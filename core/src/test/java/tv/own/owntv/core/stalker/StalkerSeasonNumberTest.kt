package tv.own.owntv.core.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StalkerSeasonNumberTest {

    @Test
    fun idSuffixWins() = assertEquals(2, StalkerClient.seasonNumberOf("280:2", "Season 5"))

    @Test
    fun idSuffixZeroIsSpecials() = assertEquals(0, StalkerClient.seasonNumberOf("280:0", "Specials"))

    @Test
    fun specialsByNameIsZero() = assertEquals(0, StalkerClient.seasonNumberOf("9911", "Specials"))

    @Test
    fun singularSpecialIsZero() = assertEquals(0, StalkerClient.seasonNumberOf("9911", "Special"))

    @Test
    fun trailingNumberInName() = assertEquals(3, StalkerClient.seasonNumberOf("9912", "Season 3"))

    @Test
    fun seasonZeroByName() = assertEquals(0, StalkerClient.seasonNumberOf("9913", "Season 00"))

    @Test
    fun noNumberIsNull() = assertNull(StalkerClient.seasonNumberOf("9914", "Extras"))
}
