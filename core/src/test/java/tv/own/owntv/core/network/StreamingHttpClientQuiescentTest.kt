package tv.own.owntv.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/** Timeout path: an accepted call that never replies is cancelled, then the client goes quiet. */
class StreamingHttpClientQuiescentTest {

    @Test
    fun `awaitQuiescent cancels a call that never replies`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = Thread {
            try {
                server.accept()
            } catch (_: IOException) {
            }
        }
        accepted.start()
        val streaming = StreamingHttpClient(OkHttpClient())
        try {
            streaming.client.newCall(
                Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(),
            ).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
            waitUntil(2_000L) { streaming.callsInFlight == 1 }
            assertFalse(runBlocking { streaming.awaitQuiescent(timeoutMs = 100) })
            waitUntil(2_000L) { streaming.callsInFlight == 0 }
            assertEquals(0, streaming.callsInFlight)
        } finally {
            server.close()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(25)
        }
    }
}
