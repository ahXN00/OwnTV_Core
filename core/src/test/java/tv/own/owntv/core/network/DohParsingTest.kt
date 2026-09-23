package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class DohParsingTest {

    @Test
    fun `json answers keep only A and AAAA, never a CNAME host name`() {
        // What Cloudflare answers for www.github.com: a CNAME first, then the address.
        val json = """{"Status":0,"Answer":[
            {"name":"www.github.com","type":5,"TTL":3191,"data":"github.com."},
            {"name":"github.com","type":1,"TTL":15,"data":"140.82.121.3"},
            {"name":"github.com","type":28,"TTL":15,"data":"2606:50c0:8000::153"}]}"""
        val got = DnsConfigHolder.parseDohJson(json).map { it.hostAddress }
        assertEquals(listOf("140.82.121.3", "2606:50c0:8000:0:0:0:0:153"), got)
    }

    @Test
    fun `a json answer that is not an IP literal is skipped`() {
        val json = """{"Answer":[{"type":1,"data":"not.an.ip"},{"type":28,"data":"host.example"},{"type":1,"data":""}]}"""
        assertEquals(emptyList<InetAddress>(), DnsConfigHolder.parseDohJson(json))
        assertEquals(emptyList<InetAddress>(), DnsConfigHolder.parseDohJson("""{"Status":3}"""))
    }

    @Test
    fun `wire url carries the binary query with id 0`() {
        val url = DnsConfigHolder.dohWireUrl(DohPresets.QUAD9, "example.com", 28)
        assertTrue(url.startsWith("https://dns.quad9.net/dns-query?dns="))
        val param = url.substringAfter("dns=")
        assertTrue("no padding in base64url", '=' !in param && '+' !in param && '/' !in param)
        val bytes = Base64.getUrlDecoder().decode(param)
        assertEquals(DnsConfigHolder.buildDnsQuery("example.com", 28, id = 0).toList(), bytes.toList())
        assertEquals("…&dns= on a URL that already has a query", '&', DnsConfigHolder.dohWireUrl("https://x/q?a=1", "h", 1).substringBefore("dns=").last())
    }

    @Test
    fun `a real binary answer is read from after the whole header`() {
        // dns.google A, exactly as dns.google answered on 2026-09-23 (ID 0, two answers). The reader used
        // to start the question at byte 8, not 12, and returned nothing for every answer — plain custom
        // DNS fell back to the system resolver on every lookup, and the DoH presets failed their test.
        val google = hex("00008180000100020000000003646e7306676f6f676c650000010001c00c00010001000000d2000408080404c00c00010001000000d2000408080808")
        assertEquals(listOf("8.8.4.4", "8.8.8.8"), DnsConfigHolder.parseDnsResponse(google, expectedId = 0).map { it.hostAddress })
        // Quad9's answer carries an extra (OPT) record after the answers; it must not disturb them.
        val quad9 = hex("00008180000100020000000103646e7306676f6f676c650000010001c00c00010001000000ec000408080808c00c00010001000000ec0004080804040000290200000000000000")
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), DnsConfigHolder.parseDnsResponse(quad9, expectedId = 0).map { it.hostAddress })
        // A different ID is someone else's answer.
        assertEquals(emptyList<InetAddress>(), DnsConfigHolder.parseDnsResponse(google, expectedId = 7))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `cache keeps an answer for its time and never an empty one`() {
        var now = 0L
        val cache = DnsCache(ttlMs = 1_000, now = { now })
        val addr = listOf(InetAddress.getByName("1.2.3.4"))
        cache.put("k", addr)
        cache.put("empty", emptyList())
        now = 999
        assertEquals(addr, cache.get("k"))
        assertNull(cache.get("empty"))
        now = 1_000
        assertNull(cache.get("k"))
    }

    @Test
    fun `first read waits once and then gives up for good`() {
        val gate = FirstRead("test")
        val t0 = System.nanoTime()
        gate.await(timeoutMs = 50)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 45)
        val t1 = System.nanoTime()
        gate.await(timeoutMs = 5_000)
        assertTrue("second wait must not block", (System.nanoTime() - t1) / 1_000_000 < 1_000)
        FirstRead("open", open = true).await(timeoutMs = 5_000) // returns at once
    }
}
