package tv.own.owntv.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.own.owntv.core.sync.M3uSyncer
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server that closes a kept-alive socket while it sits in OkHttp's pool (many small HTTP servers
 * drop idle connections after a few seconds). The shared client has connection-failure recovery off,
 * so the next GET reuses the dead socket and fails before any response arrives.
 */
class HttpClientStaleSocketTest {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val connections = AtomicInteger()
    private val firstClosed = CountDownLatch(1)
    private val url = "http://127.0.0.1:${server.localPort}/playlist.m3u"
    private val client = HttpClient(OkHttpClient.Builder().retryOnConnectionFailure(false).build())

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                val n = connections.incrementAndGet()
                Thread { serve(socket, closeAfterFirst = n == 1) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Answers every request on [socket] with a keep-alive 200; the first connection is closed right
     *  after its first answer, as an idle-timeout would. */
    private fun serve(socket: Socket, closeAfterFirst: Boolean) = socket.use {
        val reader = it.getInputStream().bufferedReader()
        val out = it.getOutputStream()
        while (true) {
            val requestLine = reader.readLine() ?: return
            if (requestLine.isEmpty()) continue
            while (reader.readLine()?.isNotEmpty() == true) Unit
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: keep-alive\r\n\r\n#EXTM3U".toByteArray())
            out.flush()
            if (closeAfterFirst) { it.close(); firstClosed.countDown(); return }
        }
    }

    private suspend fun fetch(maxAttempts: Int) = client.get(url, maxAttempts = maxAttempts) { it.readBytes().decodeToString() }

    /** One request that leaves its socket in the pool, then the server closes that socket. */
    private fun primeStaleSocket() = runBlocking {
        assertEquals("#EXTM3U", fetch(maxAttempts = 1))
        assertTrue(firstClosed.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // let the FIN reach the client side
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a single attempt fails on the socket the server closed`() {
        primeStaleSocket()
        try {
            runBlocking { fetch(maxAttempts = 1) }
            fail("expected the stale pooled socket to fail")
        } catch (_: IOException) {
            assertEquals("no new connection was tried", 1, connections.get())
        }
    }

    @Test
    fun `the M3U download retries on a fresh connection`() {
        primeStaleSocket()
        assertEquals("#EXTM3U", runBlocking { fetch(maxAttempts = M3uSyncer.M3U_DOWNLOAD_ATTEMPTS) })
        assertEquals(2, connections.get())
    }
}
