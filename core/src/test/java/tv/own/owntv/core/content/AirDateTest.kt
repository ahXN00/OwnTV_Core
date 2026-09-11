package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * When an episode first aired: which of the two sources wins, and the UTC rule that keeps the
 * calendar day from sliding by one.
 */
class AirDateTest {

    private fun utcMidnight(date: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = AirDate.UTC }
            .parse(date)!!.time

    @Test
    fun of_prefersTheProvidersOwnDate() {
        val provider = utcMidnight("2019-04-14")
        assertEquals(provider, AirDate.of(provider, "2011-04-17"))
    }

    @Test
    fun of_fallsBackToTmdbWhenThePanelDatesNothing() {
        assertEquals(utcMidnight("2011-04-17"), AirDate.of(null, "2011-04-17"))
    }

    @Test
    fun of_isNullWhenNeitherKnows() {
        assertNull(AirDate.of(null, null))
        assertNull(AirDate.of(null, ""))
    }

    @Test
    fun parse_readsThePlainDayAndIgnoresAnyTimeAfterIt() {
        assertEquals(utcMidnight("2019-04-14"), AirDate.parse("2019-04-14"))
        assertEquals(utcMidnight("2019-04-14"), AirDate.parse("2019-04-14 21:00:00"))
        assertEquals(utcMidnight("2019-04-14"), AirDate.parse("  2019-04-14  "))
    }

    /** Panels write this for "we don't know", and a user must not be told an episode aired in year 0. */
    @Test
    fun parse_rejectsTheEmptyDatePanelsSend() {
        assertNull(AirDate.parse("0000-00-00"))
        assertNull(AirDate.parse("0000-01-01"))
    }

    @Test
    fun parse_rejectsAnythingThatIsNotADay() {
        assertNull(AirDate.parse("not a date"))
        assertNull(AirDate.parse("2019"))
        assertNull(AirDate.parse("14/04/2019"))
        assertNull(AirDate.parse(" "))
    }

    /**
     * The whole reason for pinning both ends to UTC: parsed in a zone behind Greenwich and the stored
     * instant would be the previous day's evening, which formatted anywhere else reads as the wrong
     * date. This asserts the instant itself, independently of whatever zone the test machine is in.
     */
    @Test
    fun parse_isTheSameInstantWhateverTheDeviceZone() {
        val expected = 1_555_200_000_000L // 2019-04-14T00:00:00Z
        assertEquals(expected, AirDate.parse("2019-04-14"))
    }
}
