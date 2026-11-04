package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.player.StartFailureLadder.Rung

class StartFailureLadderTest {

    private fun input(
        live: Boolean = true,
        hasUrl: Boolean = true,
        autoRetries: Int = 0,
        maxRetries: Int = 3,
        altFormat: Boolean = false,
        catchupPhp: Boolean = false,
        triedTolerant: Boolean = false,
        error: String? = null,
    ) = StartFailureLadder.Input(live, hasUrl, autoRetries, maxRetries, altFormat, catchupPhp, triedTolerant, error)

    private val notFound = "ffmpeg: https: HTTP error 404 Not Found"
    private val sessionInUse = "ffmpeg: http: HTTP error 458"

    @Test
    fun `an alternate URL form goes first`() {
        assertEquals(Rung.ALT_FORMAT, StartFailureLadder.next(input(altFormat = true, catchupPhp = true)))
        assertEquals(Rung.CATCHUP_PHP, StartFailureLadder.next(input(live = false, catchupPhp = true)))
    }

    @Test
    fun `retries until the budget is spent, then the tolerant demuxer once`() {
        assertEquals(Rung.AUTO_RETRY, StartFailureLadder.next(input(autoRetries = 2)))
        assertEquals(Rung.TOLERANT_DEMUX, StartFailureLadder.next(input(autoRetries = 3)))
        assertNull(StartFailureLadder.next(input(autoRetries = 3, triedTolerant = true)))
        // No URL left (a stop during the backoff): nothing to retry.
        assertNull(StartFailureLadder.next(input(hasUrl = false)))
    }

    @Test
    fun `a hard refusal is repeated once and gets no tolerant demuxer`() {
        assertEquals(Rung.AUTO_RETRY, StartFailureLadder.next(input(error = notFound)))
        assertNull(StartFailureLadder.next(input(autoRetries = 1, error = notFound)))
    }

    @Test
    fun `a busy panel keeps the whole ladder on live, a film refused by its session does not`() {
        assertEquals(Rung.AUTO_RETRY, StartFailureLadder.next(input(autoRetries = 2, error = sessionInUse)))
        assertEquals(Rung.TOLERANT_DEMUX, StartFailureLadder.next(input(autoRetries = 3, error = sessionInUse)))
        assertEquals(Rung.AUTO_RETRY, StartFailureLadder.next(input(live = false, maxRetries = 2, error = sessionInUse)))
        assertNull(StartFailureLadder.next(input(live = false, maxRetries = 2, autoRetries = 1, error = sessionInUse)))
    }
}
