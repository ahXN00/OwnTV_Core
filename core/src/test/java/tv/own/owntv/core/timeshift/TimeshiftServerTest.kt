package tv.own.owntv.core.timeshift

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** P16b — the players read the buffer over loopback HTTP, from any piece, and only with the token. */
class TimeshiftServerTest {

    private val dir: File = Files.createTempDirectory("timeshift-server").toFile()
    private val session = TimeshiftSession("secret-token", "ch", File(dir, "s"))

    @After
    fun cleanUp() {
        TimeshiftServer.unregister(session)
        session.close()
        runCatching { dir.deleteRecursively() }
    }

    private fun piece(vararg bytes: Byte) {
        session.beginPiece()
        session.write(bytes, 0, bytes.size)
        session.flush()
        session.endPiece(2_000)
    }

    @Test
    fun `streams the buffer from the asked piece, as MPEG-TS`() {
        piece(1, 2); piece(3, 4); piece(5)
        TimeshiftServer.register(session)
        val url = TimeshiftServer.urlFor(session, 1)
        assertTrue(TimeshiftServer.isLocal(url))
        assertTrue(url.endsWith("/secret-token/1.ts"))
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.readTimeout = 2_000
        assertEquals(200, conn.responseCode)
        assertEquals("video/mp2t", conn.contentType)
        val input = conn.inputStream
        val got = ByteArray(3)
        var n = 0
        while (n < 3) n += input.read(got, n, 3 - n)
        assertArrayEquals(byteArrayOf(3, 4, 5), got)
        conn.disconnect()
    }

    @Test
    fun `an unknown token is a 404`() {
        TimeshiftServer.register(session)
        val good = TimeshiftServer.urlFor(session, 0)
        val conn = URL(good.replace("secret-token", "guess")).openConnection() as HttpURLConnection
        assertEquals(404, conn.responseCode)
    }
}
