package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/** A3: one normaliser for every writer, origin-only rewrites, and nothing changes for a source without backups. */
class HostPoolTest {

    private fun xtream(url: String = "http://pro.example.top:8080", backups: List<String> = emptyList(), active: String? = null, on: Boolean = true) =
        SourceEntity(
            id = 7, name = "X", type = SourceType.XTREAM, url = url, username = "u", password = "p",
            backupUrls = HostPool.encode(backups), activeUrl = active, failoverEnabled = on,
        )

    @Test
    fun `a bare host takes the primary's scheme`() {
        assertEquals("http://b.example.top:8080", HostPool.normalize("b.example.top:8080", "http://a.example.top"))
        assertEquals("https://b.example.top", HostPool.normalize("b.example.top", "https://a.example.top"))
    }

    @Test
    fun `default ports and case are folded so the same server is one entry`() {
        assertEquals("http://pro.8kapp.top", HostPool.normalize("pro.8kapp.top:80", "http://x.example"))
        assertEquals("http://pro.8kapp.top", HostPool.normalize("HTTP://Pro.8kApp.TOP/", "http://x.example"))
        assertEquals("https://pro.8kapp.top", HostPool.normalize("https://pro.8kapp.top:443", "http://x.example"))
        assertEquals("https://pro.8kapp.top:8443", HostPool.normalize("https://pro.8kapp.top:8443", "http://x.example"))
    }

    @Test
    fun `a provider link becomes its origin and its login is dropped`() {
        assertEquals("http://b.example.top:8080", HostPool.normalize("http://b.example.top:8080/get.php?username=u&password=p&type=m3u", null))
        assertEquals("http://b.example.top", HostPool.normalize("http://u:p@b.example.top/player_api.php?username=u", null))
        assertEquals("http://b.example.top", HostPool.normalize("http://b.example.top/live/u/p/123.ts", null))
    }

    @Test
    fun `junk and path-prefixed servers are rejected`() {
        assertNull(HostPool.normalize("xyz", null))
        assertNull(HostPool.normalize("", null))
        assertNull(HostPool.normalize("ftp://b.example.top", null))
        assertNull(HostPool.normalize("http://b.example.top/iptv", null))
        assertNull(HostPool.normalize("b.example .top", null))
    }

    @Test
    fun `adding a paste splits, dedups against the primary and the list, and reports the rest`() {
        val r = HostPool.add(
            "b.example.top, c.example.top\nb.example.top:80  http://a.example.top xyz;d.example.top",
            primary = "http://a.example.top",
            existing = listOf("http://c.example.top"),
        )
        assertEquals(listOf("http://c.example.top", "http://b.example.top", "http://d.example.top"), r.backups)
        assertEquals(2, r.added)
        assertEquals(3, r.duplicates)
        assertEquals(listOf("xyz"), r.invalid)
    }

    @Test
    fun `the list is capped at 50`() {
        val many = (1..60).map { "h$it.example.top" }
        assertEquals(HostPool.MAX_BACKUPS, HostPool.sanitize(many, "http://a.example.top").size)
        assertEquals(HostPool.MAX_BACKUPS, HostPool.add(many.joinToString(" "), "http://a.example.top", emptyList()).backups.size)
    }

    @Test
    fun `an http backup under an https primary is not secure`() {
        assertTrue(HostPool.isNotSecure("http://b.example.top", "https://a.example.top"))
        assertFalse(HostPool.isNotSecure("https://b.example.top", "https://a.example.top"))
        assertFalse(HostPool.isNotSecure("http://b.example.top", "http://a.example.top"))
    }

    @Test
    fun `rewrite swaps only a pool origin and keeps the rest byte for byte`() {
        val pool = listOf("http://a.example.top:8080", "http://b.example.top")
        assertEquals(
            "http://b.example.top/live/u/p/1%7C2.ts?x=1",
            HostPool.rewrite("http://a.example.top:8080/live/u/p/1%7C2.ts?x=1", "http://b.example.top", pool),
        )
        // A directSource on another server, a logo, and junk stay as they were.
        assertEquals("http://cdn.other.net/a.ts", HostPool.rewrite("http://cdn.other.net/a.ts", "http://b.example.top", pool))
        assertEquals("not a url", HostPool.rewrite("not a url", "http://b.example.top", pool))
    }

    @Test
    fun `request and canonical forms round-trip to exactly what sync stored`() {
        val s = xtream(url = "http://Pro.Example.top:8080/", backups = listOf("b.example.top"), active = "http://b.example.top")
        val stored = "http://Pro.Example.top:8080/movie/u/p/9.mkv"
        val request = HostPool.forRequest(stored, s)
        assertEquals("http://b.example.top/movie/u/p/9.mkv", request)
        assertEquals(stored, HostPool.canonical(request, s))
        // Back on the primary, the request URL is the stored one.
        assertEquals(stored, HostPool.forRequest(stored, s, host = HostPool.pool(s).first()))
    }

    @Test
    fun `a source without backups, with the switch off, or not Xtream is left exactly alone`() {
        val stored = "http://pro.example.top:8080/live/u/p/1.ts"
        assertEquals(stored, HostPool.forRequest(stored, xtream()))
        assertFalse(HostPool.failoverActive(xtream()))
        assertFalse(HostPool.failoverActive(xtream(backups = listOf("b.example.top"), on = false)))
        val m3u = xtream(backups = listOf("b.example.top")).copy(type = SourceType.M3U)
        assertTrue(HostPool.pool(m3u).isEmpty())
        assertEquals(stored, HostPool.forRequest(stored, m3u))
    }

    @Test
    fun `switch off keeps the in-use server for requests`() {
        val s = xtream(backups = listOf("b.example.top"), active = "http://b.example.top", on = false)
        assertEquals("http://b.example.top", HostPool.inUse(s))
        assertEquals("http://b.example.top/live/u/p/1.ts", HostPool.forRequest("http://pro.example.top:8080/live/u/p/1.ts", s))
    }

    @Test
    fun `a path-prefixed primary disables failover and ignores the active host`() {
        val s = xtream(url = "http://a.example.top/iptv", backups = listOf("b.example.top"), active = "http://b.example.top")
        assertTrue(HostPool.hasPathPrefix(s.url))
        assertFalse(HostPool.failoverActive(s))
        assertEquals("http://a.example.top/iptv/live/u/p/1.ts", HostPool.forRequest("http://a.example.top/iptv/live/u/p/1.ts", s))
    }

    @Test
    fun `an active host that left the pool falls back to the primary`() {
        val s = xtream(backups = listOf("b.example.top"), active = "http://gone.example.top")
        assertEquals("http://pro.example.top:8080", HostPool.inUse(s))
    }

    @Test
    fun `an edited row keeps the stored in-use server unless Server URL changed or it left the list`() {
        val stored = xtream(backups = listOf("b.example.top", "c.example.top"), active = "http://c.example.top")
        val staleEdit = stored.copy(activeUrl = null, name = "renamed")
        assertEquals("http://c.example.top", HostPool.reconcileActive(staleEdit, stored))
        assertNull(HostPool.reconcileActive(staleEdit.copy(url = "http://new.example.top"), stored))
        assertNull(HostPool.reconcileActive(staleEdit.copy(backupUrls = HostPool.encode(listOf("http://b.example.top"))), stored))
    }

    @Test
    fun `next cycles in list order and position counts from one`() {
        val pool = listOf("http://a.example.top", "http://b.example.top", "http://c.example.top")
        assertEquals("http://b.example.top", HostPool.next(pool, "http://a.example.top"))
        assertEquals("http://a.example.top", HostPool.next(pool, "http://c.example.top"))
        assertNull(HostPool.next(listOf("http://a.example.top"), "http://a.example.top"))
        assertEquals(3, HostPool.position(pool, "http://c.example.top"))
        assertEquals(0, HostPool.position(pool, "http://x.example.top"))
        assertEquals("pool:7", HostPool.poolKey(7))
    }

    @Test
    fun `display shows origins only and drops http's default port and scheme`() {
        assertEquals("pro.example.top", HostPool.display("http://pro.example.top:80/get.php?username=u"))
        assertEquals("http://pro.example.top:8080", HostPool.display("http://user:pw@pro.example.top:8080"))
        assertEquals("https://pro.example.top", HostPool.display("https://pro.example.top:443/"))
        assertEquals("https://pro.example.top:8443", HostPool.display("https://pro.example.top:8443"))
    }

    @Test
    fun `a stream saved under an older server url moves onto the in-use server`() {
        val s = xtream(url = "http://10.255.255.1", backups = listOf("http://cg.example.top", "http://f.example.top"), active = "http://cg.example.top")
        val stale = "http://pro.example.top/live/u/p/671009.m3u8"
        assertEquals("http://cg.example.top/live/u/p/671009.m3u8", HostPool.forRequest(stale, s))
        assertEquals("http://pro.example.top/live/u/p/671009.m3u8", HostPool.canonical(stale, s))
    }

    @Test
    fun `an off-list url with other credentials is untouched`() {
        val s = xtream(url = "http://10.255.255.1", backups = listOf("http://cg.example.top", "http://f.example.top"), active = "http://cg.example.top")
        val url = "http://pro.example.top/live/other/secret/1.ts"
        assertEquals(url, HostPool.forRequest(url, s))
    }

    @Test
    fun `an off-list non-panel path is untouched`() {
        val s = xtream(url = "http://10.255.255.1", backups = listOf("http://cg.example.top", "http://f.example.top"), active = "http://cg.example.top")
        val url = "http://pro.example.top/images/u/p/logo.png"
        assertEquals(url, HostPool.forRequest(url, s))
    }
}
