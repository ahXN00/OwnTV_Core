package tv.own.owntv.core.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedDnsTest {
    private val first = listOf(InetAddress.getByAddress("live.invalid", byteArrayOf(10, 0, 0, 1)))
    private val second = listOf(InetAddress.getByAddress("live.invalid", byteArrayOf(10, 0, 0, 2)))

    /** Answers from [answers] in turn; null = fail, HANG = never answer until released. */
    private class ScriptedDns(vararg answers: List<InetAddress>?) : Dns {
        private val script = answers.toMutableList()
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            val next = synchronized(script) { if (script.size > 1) script.removeAt(0) else script[0] }
            if (next === HANG) { release.await(10, TimeUnit.SECONDS); throw UnknownHostException(hostname) }
            return next ?: throw UnknownHostException(hostname)
        }
        companion object { val HANG = emptyList<InetAddress>() }
    }

    @Test
    fun `a fresh answer is returned and remembered`() {
        val dns = BoundedDns(timeoutMs = 1_000, delegate = ScriptedDns(first, second))
        assertEquals(first, dns.lookup("live.invalid"))
        assertEquals("a newer answer always wins", second, dns.lookup("live.invalid"))
    }

    @Test
    fun `a failed lookup falls back to the last good answer`() {
        val dns = BoundedDns(timeoutMs = 1_000, delegate = ScriptedDns(first, null))
        dns.lookup("live.invalid")
        assertEquals(first, dns.lookup("live.invalid"))
    }

    @Test
    fun `the fallback log line does not name the host`() {
        val lines = mutableListOf<String>()
        val dns = BoundedDns(timeoutMs = 1_000, delegate = ScriptedDns(first, null), log = { lines += it })
        dns.lookup("live.invalid")
        dns.lookup("live.invalid")
        assertEquals(1, lines.size)
        assertFalse(lines.single(), "live.invalid" in lines.single())
    }

    @Test
    fun `a hung lookup gives up at the deadline and falls back`() {
        val scripted = ScriptedDns(first, ScriptedDns.HANG)
        val dns = BoundedDns(timeoutMs = 200, delegate = scripted)
        dns.lookup("live.invalid")
        val started = System.nanoTime()
        assertEquals(first, dns.lookup("live.invalid"))
        assertTrue("returned at the deadline, not when DNS gave up", System.nanoTime() - started < 2_000_000_000L)
        scripted.release.countDown()
    }

    @Test
    fun `with nothing remembered a hung lookup is an UnknownHostException`() {
        val scripted = ScriptedDns(ScriptedDns.HANG)
        val dns = BoundedDns(timeoutMs = 200, delegate = scripted)
        try {
            dns.lookup("live.invalid")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
        } finally {
            scripted.release.countDown()
        }
    }

    @Test
    fun `lookups of a host already being resolved share that query`() {
        val scripted = ScriptedDns(ScriptedDns.HANG)
        val dns = BoundedDns(timeoutMs = 100, delegate = scripted)
        repeat(3) { runCatching { dns.lookup("live.invalid") } }
        assertEquals(1, scripted.calls.get())
        scripted.release.countDown()
    }
}
