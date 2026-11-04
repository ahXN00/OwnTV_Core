package tv.own.owntv.core.timeshift

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The whole buffer end to end on the JVM: a fake provider streaming live TS, the downloader
 * saving it, a player reading it back over loopback, and the park / wake / resume rules.
 */
class TimeshiftManagerTest {

    private val dir: File = Files.createTempDirectory("timeshift-manager").toFile()
    private val provider = ServerSocket(0)
    private val connections = AtomicInteger(0)
    @Volatile private var providerOpen = true

    init {
        // Endless TS: PAT, PMT, then a keyframe every 10 video packets, 100 ms of PCR per packet.
        thread(isDaemon = true) {
            while (providerOpen) {
                val socket = runCatching { provider.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching {
                        socket.use { s ->
                            val input = s.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) {}
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(TsCutterTest.packet(0, true, payload = TsCutterTest.patPayload()))
                            out.write(TsCutterTest.packet(TsCutterTest.PMT_PID, true, payload = TsCutterTest.pmtPayload()))
                            var i = 0L
                            while (providerOpen) {
                                out.write(TsCutterTest.packet(TsCutterTest.VIDEO_PID, true, rai = i % 10 == 0L, pcrMs = i * 100))
                                out.flush()
                                i++
                                Thread.sleep(2)
                            }
                        }
                    }
                }
            }
        }
    }

    private val manager = TimeshiftManager(OkHttpClient(), { dir }, idleParkMs = 300)

    private fun target(sourceId: Long = -1) = TimeshiftDownloader.Target(
        url = { "http://127.0.0.1:${provider.localPort}/live.ts" },
        userAgent = "test",
        headers = emptyMap(),
        maxVideoHeight = null,
        sourceId = sourceId,
    )

    @After
    fun cleanUp() {
        providerOpen = false
        manager.closeAll()
        provider.close()
        runCatching { dir.deleteRecursively() }
    }

    private fun readSome(session: TimeshiftSession, from: Long?, bytes: Int): HttpURLConnection {
        val conn = URL(TimeshiftServer.urlFor(session, from)).openConnection() as HttpURLConnection
        conn.readTimeout = 5_000
        val input = conn.inputStream
        var got = 0
        val buf = ByteArray(4096)
        while (got < bytes) got += input.read(buf, 0, minOf(buf.size, bytes - got)).coerceAtLeast(0)
        return conn
    }

    @Test
    fun `saves a live stream into keyframe pieces that a player reads back over loopback`() = runBlocking {
        val opened = manager.open("ch1", target(), windowMinutes = 15, firstPieceTimeoutMs = 5_000)
        assertNotNull(opened)
        val session = opened!!.session
        Thread.sleep(800)
        assertTrue("several pieces", (session.livePieceIndex() ?: 0) >= 2)
        val conn = readSome(session, 0, 188 * 20)
        assertEquals(1, session.readerCount)
        conn.disconnect()
    }

    @Test
    fun `nobody reading parks the buffer, a returning player wakes it, and a return offers where they left`() = runBlocking {
        val session = manager.open("ch1", target(), 15, 5_000)!!.session
        Thread.sleep(1_000) // idle for longer than 300 ms: parked, the provider connection closed
        val before = connections.get()
        assertTrue(!session.isClosed)

        // Coming back to the channel through the controller wakes it and says where the user was.
        val back = manager.open("ch1", target(), 15, 5_000)
        assertSame(session, back!!.session)
        assertNotNull(back.resumeAtWallMs)
        Thread.sleep(300)
        assertTrue("reconnected once", connections.get() == before + 1)
    }

    /**
     * A television in standby: the wall clock runs on, but the CPU sleeps and the manager's own timers
     * barely move, so the parked buffer is still there when the screen comes back.
     */
    private fun sleepingManager(now: AtomicLong) =
        TimeshiftManager(OkHttpClient(), { dir }, clock = { now.get() }, idleParkMs = 300)

    @Test
    fun `a player back long after the park gets fresh pieces, not the ones from before`() = runBlocking {
        val now = AtomicLong(1_000_000L)
        val sleeping = sleepingManager(now)
        try {
            val session = sleeping.open("ch1", target(), 15, 5_000)!!.session
            assertTrue("saving", eventually { (session.livePieceIndex() ?: 0) >= 2 })
            assertTrue("parked", eventually { !sleeping.isSaving(-1) })
            now.addAndGet(TimeshiftRules.PARKED_GRACE_MS + 60_000)

            // The television restoring its channel: the same live address, straight to the server.
            val conn = readSome(session, null, 188 * 20)
            assertEquals("only what came after the return", now.get(), session.oldestWallMs())
            conn.disconnect()
        } finally {
            sleeping.closeAll()
        }
    }

    @Test
    fun `a channel opened again long after the park starts a new buffer`() = runBlocking {
        val now = AtomicLong(1_000_000L)
        val sleeping = sleepingManager(now)
        try {
            val first = sleeping.open("ch1", target(), 15, 5_000)!!.session
            assertTrue("parked", eventually { !sleeping.isSaving(-1) })
            now.addAndGet(TimeshiftRules.PARKED_GRACE_MS + 60_000)

            val again = sleeping.open("ch1", target(), 15, 5_000)!!
            assertNotSame(first, again.session)
            assertNull("nothing left to resume from", again.resumeAtWallMs)
            assertTrue("the old copy is gone", eventually { first.isClosed })
        } finally {
            sleeping.closeAll()
        }
    }

    @Test
    fun `another channel parks the first, and one parked buffer at most`() = runBlocking {
        val first = manager.open("ch1", target(), 15, 5_000)!!.session
        val second = manager.open("ch2", target(), 15, 5_000)!!.session
        manager.open("ch3", target(), 15, 5_000)!!.session
        assertTrue("the oldest parked buffer is gone", eventually { first.isClosed })
        assertTrue(!second.isClosed)
    }

    @Test
    fun `going back to the channel just left wakes its own copy, with where the user was`() = runBlocking {
        val first = manager.open("ch1", target(), 15, 5_000)!!.session
        val second = manager.open("ch2", target(), 15, 5_000, leavingWallMs = 1_234L)!!.session
        val back = manager.open("ch1", target(), 15, 5_000, leavingWallMs = 5_678L)!!
        assertSame(first, back.session)
        assertEquals(1_234L, back.resumeAtWallMs)
        Thread.sleep(200)
        assertTrue("the channel left in between is kept parked", !second.isClosed)
    }

    @Test
    fun `saving is reported for the playlist being downloaded, and not once parked`() = runBlocking {
        manager.open("7:1", target(sourceId = 7), 15, 5_000)!!
        assertTrue(manager.isSaving(7))
        assertTrue(!manager.isSaving(8))
        // Nobody reading: parked after about a second, the connection closed.
        assertTrue(eventually { !manager.isSaving(7) })
    }

    @Test
    fun `a stream that is not TS is not buffered`() = runBlocking {
        val notTs = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                notTs.accept().use { s ->
                    s.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) {} }
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                    s.getOutputStream().write(ByteArray(600 * 1024) { 0x11 })
                }
            }
        }
        val garbage = TimeshiftDownloader.Target({ "http://127.0.0.1:${notTs.localPort}/x" }, "t", emptyMap(), null)
        assertNull(manager.open("ch9", garbage, 15, 5_000))
        notTs.close()
    }

    /**
     * Polls [condition] until it holds or [timeoutMs] passes. The manager closes and parks on its own
     * threads, so a fixed sleep made these tests fail whenever the machine was busy.
     */
    private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
