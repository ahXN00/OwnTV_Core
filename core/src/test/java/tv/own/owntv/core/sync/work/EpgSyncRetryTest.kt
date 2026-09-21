package tv.own.owntv.core.sync.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.stalker.StalkerEpgLoader
import java.io.IOException

/**
 * Which EPG sync failures are worth another attempt.
 *
 * The case that prompted this: a Stalker portal whose guide had been emptied answered every route
 * successfully with nothing in it. That arrived as a plain `IOException`, which this worker read as
 * "network trouble" and retried three times with backoff — so Settings → EPG sat on "Connecting…"
 * for six minutes before admitting a failure that was certain from the first second.
 */
class EpgSyncRetryTest {

    private val transient = IOException("unexpected end of stream on http://portal/")

    @Test
    fun `a portal with no guide is not retried`() {
        assertFalse(shouldRetryEpgSync(StalkerEpgLoader.PortalHasNoGuideException(), online = true, runAttemptCount = 0))
    }

    /** A feed that downloaded and parsed in full, and simply had nothing for the days we keep. */
    @Test
    fun `a feed with no programmes in the window is not retried`() {
        assertFalse(shouldRetryEpgSync(EpgRepository.NoProgrammesInWindowException(), online = true, runAttemptCount = 0))
    }

    /** The playlist behind a portal guide was deleted; no amount of waiting brings it back. */
    @Test
    fun `a portal guide whose playlist is gone is not retried`() {
        assertFalse(shouldRetryEpgSync(EpgRepository.PortalGuideSourceGoneException(7L), online = true, runAttemptCount = 0))
    }

    @Test
    fun `a broken connection is still retried`() {
        assertTrue(shouldRetryEpgSync(transient, online = true, runAttemptCount = 0))
    }

    @Test
    fun `the retry budget is still respected`() {
        assertFalse(shouldRetryEpgSync(transient, online = true, runAttemptCount = MAX_EPG_RETRY_ATTEMPTS))
    }

    /** A bad URL or malformed XML is terminal, exactly as before. */
    @Test
    fun `a permanent error is not retried`() {
        assertFalse(shouldRetryEpgSync(IllegalArgumentException("bad url"), online = true, runAttemptCount = 0))
    }
}
