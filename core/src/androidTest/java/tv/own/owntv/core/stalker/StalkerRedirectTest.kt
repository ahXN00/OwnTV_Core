package tv.own.owntv.core.stalker

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A portal whose address redirects to another host. OkHttp drops `Authorization` on a cross-host
 * redirect, so the session must use the host the handshake landed on, or every call after the
 * handshake reaches the real portal without its token.
 *
 * Instrumented, not a unit test: the client parses with `android.util.JsonReader`, a stub on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class StalkerRedirectTest {

    /** One request as a server saw it: the request line and its `Authorization` header, if any. */
    private data class Seen(val line: String, val authorization: String?)

    /** A tiny HTTP/1.1 server answering every request with whatever [answer] returns for its line. */
    private class Server(private val answer: (String) -> String) {
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val seen = CopyOnWriteArrayList<Seen>()
        val base = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (_: IOException) { break }
                    Thread {
                        s.use {
                            val reader = it.getInputStream().bufferedReader()
                            val out = it.getOutputStream()
                            while (true) {
                                val line = reader.readLine() ?: return@use
                                if (line.isEmpty()) continue
                                var auth: String? = null
                                while (true) {
                                    val header = reader.readLine() ?: return@use
                                    if (header.isEmpty()) break
                                    if (header.startsWith("Authorization:", ignoreCase = true)) auth = header.substringAfter(':').trim()
                                }
                                seen += Seen(line, auth)
                                out.write(answer(line).toByteArray())
                                out.flush()
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun json(body: String) =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"

    private val portal = Server { line ->
        when {
            "action=handshake" in line -> json("""{"js":{"token":"T1"}}""")
            "action=get_profile" in line -> json("""{"js":{"id":"1","name":"stb"}}""")
            else -> json("""{"js":{}}""")
        }
    }

    /** The address the user typed: sends every request on to [portal], query and all. */
    private val front = Server { line ->
        val query = line.substringAfter('?', "").substringBefore(' ')
        "HTTP/1.1 302 Found\r\nLocation: ${portal.base}/stalker_portal/server/load.php?$query\r\nContent-Length: 0\r\n\r\n"
    }

    private val client = StalkerClient(OkHttpClient())

    @After
    fun tearDown() {
        front.socket.close()
        portal.socket.close()
    }

    @Test
    fun sessionUsesTheHostTheHandshakeLandedOn() = runBlocking {
        val handshake = client.resolveHandshake(front.base, "00:1A:79:00:00:01")

        assertEquals("${portal.base}/stalker_portal/server/load.php", handshake.apiBase)
        assertEquals("T1", handshake.token)

        client.getProfile(handshake.apiBase, "00:1A:79:00:00:01", handshake.token)
        val profile = portal.seen.single { "action=get_profile" in it.line }
        assertEquals("Bearer T1", profile.authorization)
        assertTrue("nothing after the handshake goes to the old address", front.seen.none { "action=get_profile" in it.line })
    }

    @Test
    fun portalWithoutRedirectKeepsItsAddress() = runBlocking {
        val handshake = client.resolveHandshake(portal.base, "00:1A:79:00:00:01")
        assertEquals("${portal.base}/portal.php", handshake.apiBase)
    }
}
