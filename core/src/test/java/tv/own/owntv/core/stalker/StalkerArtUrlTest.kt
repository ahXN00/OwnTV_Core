package tv.own.owntv.core.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Portals often send a poster as a server path; it has to become a URL an image loader can open. */
class StalkerArtUrlTest {

    private val apiBase = "http://portal.test:8080/stalker_portal/server/load.php"

    @Test
    fun `a server path resolves against the portal's origin, not its API path`() {
        assertEquals(
            "http://portal.test:8080/stalker_portal/screenshots/1/abc.jpg",
            StalkerClient.absoluteArt("/stalker_portal/screenshots/1/abc.jpg", apiBase),
        )
        assertEquals(
            "http://portal.test:8080/screenshots/abc.jpg",
            StalkerClient.absoluteArt("screenshots/abc.jpg", apiBase),
        )
    }

    @Test
    fun `an absolute URL is kept as it is`() {
        val cdn = "https://cdn.test/posters/abc.jpg?size=big"
        assertEquals(cdn, StalkerClient.absoluteArt(cdn, apiBase))
    }

    @Test
    fun `blank and zero mean no poster`() {
        assertNull(StalkerClient.absoluteArt(null, apiBase))
        assertNull(StalkerClient.absoluteArt("  ", apiBase))
        assertNull(StalkerClient.absoluteArt("0", apiBase))
    }
}
