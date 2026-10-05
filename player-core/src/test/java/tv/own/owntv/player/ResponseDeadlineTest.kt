package tv.own.owntv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ResponseDeadlineTest {

    private val server = ServerSocket(0)
    private val missed = AtomicInteger(0)
    private val answered = AtomicInteger(0)

    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor(ResponseDeadline(300, { missed.incrementAndGet() }, { answered.incrementAndGet() }))
        .build()

    private val url get() = "http://127.0.0.1:${server.localPort}/live/1.ts"

    @After fun close() = server.close()

    /** Accept one request; answer it after [delayMs], or never when null. */
    private fun serve(delayMs: Long?) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                val input = s.getInputStream().bufferedReader()
                while (input.readLine()?.isNotEmpty() == true) Unit // the request headers
                if (delayMs == null) { Thread.sleep(5_000); return@use }
                Thread.sleep(delayMs)
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    flush()
                }
                Thread.sleep(500)
            }
        }
    }

    @Test
    fun `a server that never answers is abandoned at the deadline`() {
        serve(delayMs = null)
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            fail("expected a timeout")
        } catch (expected: SocketTimeoutException) {
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("gave up at the deadline, not the read timeout ($tookMs ms)", tookMs < 3_000)
        assertEquals(1, missed.get())
        assertEquals(0, answered.get())
    }

    @Test
    fun `a redirect is not counted as an answer`() {
        // The panel's 302 always answers before the silent media server behind it.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        flush()
                    }
                    while (input.readLine()?.isNotEmpty() == true) Unit // the redirected request, never answered
                    Thread.sleep(5_000)
                }
            }
        }
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            fail("expected a timeout")
        } catch (expected: SocketTimeoutException) {
        }
        assertEquals(0, answered.get())
        assertEquals(1, missed.get())
    }

    @Test
    fun `each hop of a longer redirect chain gets its own deadline, and only the final answer counts`() {
        // panel → 302 → intermediate → 302 → media server that answers late but inside its own deadline:
        // the chain as a whole takes longer than one deadline and must still succeed.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    val out = s.getOutputStream()
                    for (hop in listOf("/hop2", "/hop3")) {
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        Thread.sleep(200)
                        out.write("HTTP/1.1 302 Found\r\nLocation: $hop\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    Thread.sleep(200)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    out.flush()
                    Thread.sleep(500)
                }
            }
        }
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            assertEquals("ok", r.body.string())
        }
        assertEquals(0, missed.get())
        assertEquals("the two redirects are not answers, the final 200 is", 1, answered.get())
    }

    @Test
    fun `a prompt answer goes through, and its body outlives the deadline`() {
        serve(delayMs = 50)
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            Thread.sleep(500) // past the deadline: it must no longer apply once the headers are in
            assertEquals("ok", r.body.string())
        }
        assertEquals(0, missed.get())
        assertEquals(1, answered.get())
    }
}
