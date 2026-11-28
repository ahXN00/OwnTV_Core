package tv.own.owntv.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckRetryTest {

    @Test fun `a server error is asked again with a growing pause`() {
        assertEquals(1_500L, UpdateManager.checkRetryDelayMs(503, null, attempt = 0))
        assertEquals(3_000L, UpdateManager.checkRetryDelayMs(502, null, attempt = 1))
    }

    @Test fun `it gives up after two more tries`() {
        assertNull(UpdateManager.checkRetryDelayMs(503, null, attempt = 2))
    }

    @Test fun `the server's own Retry-After is honoured, within a cap`() {
        assertEquals(4_000L, UpdateManager.checkRetryDelayMs(503, "4", attempt = 0))
        assertEquals(10_000L, UpdateManager.checkRetryDelayMs(503, "120", attempt = 0))
        assertEquals("a date form is not a number of seconds", 1_500L, UpdateManager.checkRetryDelayMs(503, "Wed, 21 Oct 2026 07:28:00 GMT", attempt = 0))
    }

    @Test fun `anything but a 5xx fails at once`() {
        assertNull(UpdateManager.checkRetryDelayMs(403, null, attempt = 0))
        assertNull(UpdateManager.checkRetryDelayMs(404, "5", attempt = 0))
        assertNull(UpdateManager.checkRetryDelayMs(429, "5", attempt = 0))
    }
}
