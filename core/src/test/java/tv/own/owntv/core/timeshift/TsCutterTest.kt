package tv.own.owntv.core.timeshift

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** P16a — where a live TS stream is cut, and that every piece is a place a player can start. */
class TsCutterTest {

    private class Pieces : TsCutter.Sink {
        val pieces = mutableListOf(ByteArrayOutputStream())
        val durations = mutableListOf<Long>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = pieces.last().write(bytes, offset, length)
        override fun cut(durationMs: Long) {
            durations += durationMs
            pieces += ByteArrayOutputStream()
        }
    }

    private val pat = packet(pid = 0, pusi = true, payload = patPayload())
    private val pmt = packet(pid = PMT_PID, pusi = true, payload = pmtPayload())

    /** PAT, PMT, then video every 500 ms of PCR with a keyframe every [keyEvery] packets. */
    private fun stream(frames: Int, keyEvery: Int = 4, rai: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat); out.write(pmt)
        for (i in 0 until frames) {
            out.write(packet(pid = VIDEO_PID, pusi = true, rai = rai && i % keyEvery == 0, pcrMs = i * 500L))
        }
        return out.toByteArray()
    }

    @Test
    fun `cuts on keyframes once a piece is long enough, each new piece opening with PAT and PMT`() {
        val sink = Pieces()
        val cutter = TsCutter(targetMs = 2_000L, wallClock = { 0L })
        val bytes = stream(12)
        cutter.feed(bytes, 0, bytes.size, sink)
        // Keyframes at 0, 2, 4 s: cuts at 2 s and 4 s.
        assertEquals(listOf(2_000L, 2_000L), sink.durations)
        assertEquals(3, sink.pieces.size)
        for (piece in sink.pieces.drop(1)) {
            val bytes = piece.toByteArray()
            assertArrayEquals(pat, bytes.copyOfRange(0, 188))
            assertArrayEquals(pmt, bytes.copyOfRange(188, 376))
            assertTrue("third packet is the keyframe", bytes[376 + 5].toInt() and 0x40 != 0)
        }
    }

    @Test
    fun `the same stream fed in awkward chunks is cut identically`() {
        val whole = Pieces()
        TsCutter(2_000L) { 0L }.feed(stream(12), 0, stream(12).size, whole)
        val chunked = Pieces()
        val cutter = TsCutter(2_000L) { 0L }
        val bytes = stream(12)
        var pos = 0
        val sizes = intArrayOf(1, 7, 187, 189, 500, 33)
        var s = 0
        while (pos < bytes.size) {
            val n = minOf(sizes[s++ % sizes.size], bytes.size - pos)
            cutter.feed(bytes, pos, n, chunked)
            pos += n
        }
        assertEquals(whole.durations, chunked.durations)
        assertEquals(whole.pieces.map { it.toByteArray().toList() }, chunked.pieces.map { it.toByteArray().toList() })
    }

    @Test
    fun `leading garbage is skipped and a stream with no sync is not TS`() {
        val sink = Pieces()
        val cutter = TsCutter(2_000L) { 0L }
        val garbage = ByteArray(1000) { 0x11 }
        cutter.feed(garbage + stream(4), 0, 1000 + stream(4).size, sink)
        assertTrue(cutter.synced)
        assertEquals(1000L, cutter.discardedBytes)

        val notTs = TsCutter(2_000L) { 0L }
        notTs.feed(ByteArray(4096) { 0x22 }, 0, 4096, Pieces())
        assertFalse(notTs.synced)
    }

    @Test
    fun `a stream that never flags keyframes is still cut, later`() {
        val sink = Pieces()
        val bytes = stream(30, rai = false)
        TsCutter(2_000L) { 0L }.feed(bytes, 0, bytes.size, sink)
        // No random-access flags: cut on a picture start after three targets (6 s), at 6 s and 12 s.
        assertEquals(listOf(6_000L, 6_000L), sink.durations)
    }

    companion object {
        const val PMT_PID = 0x100
        const val VIDEO_PID = 0x101

        fun packet(pid: Int, pusi: Boolean, rai: Boolean = false, pcrMs: Long? = null, payload: ByteArray = ByteArray(0)): ByteArray {
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = 0x47
            p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xFF).toByte()
            var pos = 4
            if (rai || pcrMs != null) {
                p[3] = 0x30 // adaptation + payload
                val afLen = if (pcrMs != null) 7 else 1
                p[4] = afLen.toByte()
                p[5] = ((if (rai) 0x40 else 0) or (if (pcrMs != null) 0x10 else 0)).toByte()
                if (pcrMs != null) {
                    val base = pcrMs * 90
                    p[6] = (base shr 25).toByte(); p[7] = (base shr 17).toByte(); p[8] = (base shr 9).toByte()
                    p[9] = (base shr 1).toByte(); p[10] = ((base and 1) shl 7).toByte(); p[11] = 0
                }
                pos = 5 + afLen
            } else {
                p[3] = 0x10
            }
            val body = if (payload.isEmpty() && pid == VIDEO_PID) byteArrayOf(0, 0, 1, 0xE0.toByte()) else payload
            System.arraycopy(body, 0, p, pos, body.size)
            return p
        }

        fun patPayload(): ByteArray = byteArrayOf(
            0, // pointer
            0x00, 0xB0.toByte(), 13, // table id, section length 13
            0, 1, 0xC1.toByte(), 0, 0, // ts id, version, section, last
            0, 1, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte(), // program 1 -> PMT
            0, 0, 0, 0, // CRC (not checked)
        )

        fun pmtPayload(): ByteArray = byteArrayOf(
            0,
            0x02, 0xB0.toByte(), 18,
            0, 1, 0xC1.toByte(), 0, 0,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), // PCR pid
            0xF0.toByte(), 0, // program info length 0
            0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0, // H.264
            0, 0, 0, 0,
        )
    }
}
