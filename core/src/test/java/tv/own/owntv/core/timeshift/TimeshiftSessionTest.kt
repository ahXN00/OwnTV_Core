package tv.own.owntv.core.timeshift

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** P16a — the buffer on disk: pieces, the wall clock, gaps, dropping, and readers that follow the edge. */
class TimeshiftSessionTest {

    private val dir: File = Files.createTempDirectory("timeshift-test").toFile()
    private val now = AtomicLong(1_000_000L)
    private val session = TimeshiftSession("tok", "ch", File(dir, "s"), clock = { now.get() })

    @After
    fun cleanUp() {
        session.close()
        runCatching { dir.deleteRecursively() }
    }

    private fun piece(bytes: ByteArray, durationMs: Long) {
        session.beginPiece()
        session.write(bytes, 0, bytes.size)
        session.flush()
        session.endPiece(durationMs)
    }

    @Test
    fun `pieces follow each other on the wall clock, re-anchored after a gap`() {
        piece(byteArrayOf(1), 2_000)
        now.addAndGet(500) // a burst: the second piece arrives early, but starts where the first ended
        piece(byteArrayOf(2), 2_000)
        session.markGap()
        now.set(1_060_000L) // a minute later
        piece(byteArrayOf(3), 2_000)

        assertEquals(1_000_000L, session.wallStartOf(0))
        assertEquals(1_002_000L, session.wallStartOf(1))
        assertEquals(1_060_000L, session.wallStartOf(2))
        assertEquals(listOf(1_004_000L until 1_060_000L), session.gaps())
        assertEquals(1L, session.pieceAt(1_003_000L))
        assertEquals(0L, session.pieceAt(0L)) // before the oldest: the oldest
    }

    @Test
    fun `a reader gets every piece in order, then waits at the live edge for more`() {
        piece(byteArrayOf(1, 2), 2_000)
        session.beginPiece()
        session.write(byteArrayOf(3), 0, 1)
        session.flush()
        val reader = session.open(0)
        val buf = ByteArray(16)
        val got = mutableListOf<Byte>()
        while (got.size < 3) {
            val n = reader.read(buf, 0, buf.size)
            (0 until n).forEach { got += buf[it] }
        }
        assertEquals(listOf<Byte>(1, 2, 3), got)
        assertEquals(1, session.readerCount)

        // The next bytes arrive while the reader is blocked.
        val writer = thread {
            Thread.sleep(100)
            session.write(byteArrayOf(4), 0, 1)
            session.flush()
        }
        val n = reader.read(buf, 0, buf.size)
        writer.join()
        assertEquals(1, n)
        assertEquals(4.toByte(), buf[0])
        reader.close()
        assertEquals(0, session.readerCount)
    }

    @Test
    fun `a reader of a dropped piece starts at the oldest, and fragmented MP4 gets its init first`() {
        session.setContainer(TimeshiftSession.Container.FMP4, byteArrayOf(9, 9))
        repeat(6) { piece(byteArrayOf(it.toByte()), 60_000) }
        // Window of 2 min over six 1-minute pieces, plenty of space: three go (the newest three stay).
        assertEquals(3, session.trim(windowMs = 120_000, usableBytes = Long.MAX_VALUE))
        val reader = session.open(0)
        val buf = ByteArray(3)
        var read = 0
        while (read < 3) read += reader.read(buf, read, 3 - read)
        assertArrayEquals(byteArrayOf(9, 9, 3), buf)
        reader.close()
    }

    @Test
    fun `closing ends every reader and deletes the folder`() {
        piece(byteArrayOf(1), 2_000)
        val reader = session.open(1) // not written yet: blocks
        val result = AtomicLong(0)
        val t = thread { result.set(reader.read(ByteArray(4), 0, 4).toLong()) }
        Thread.sleep(100)
        session.close()
        t.join(2_000)
        assertEquals(-1L, result.get())
        assertTrue(!File(dir, "s").exists())
        assertNull(session.livePieceIndex())
    }
}
