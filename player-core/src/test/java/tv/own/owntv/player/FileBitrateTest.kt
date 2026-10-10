package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A film's Bitrate row: bytes over the media they cover, never the burst download speed, never 0. */
class FileBitrateTest {

    private val mb = 1_000_000L / 8 // bytes in one megabit

    @Test
    fun `nothing until five seconds of media are covered, then the average`() {
        val b = FileBitrate()
        assertNull(b.bitsPerSecond(totalBytes = 0, bufferedMs = 60_000))
        assertNull(b.bitsPerSecond(totalBytes = 32 * mb, bufferedMs = 64_000))
        // 80 Mbit for 10 s of media = 8 Mbps, however fast or slowly it arrived.
        assertEquals(8_000_000L, b.bitsPerSecond(totalBytes = 80 * mb, bufferedMs = 70_000))
    }

    @Test
    fun `a full buffer that downloads nothing keeps the last average instead of reading 0`() {
        val b = FileBitrate()
        b.bitsPerSecond(totalBytes = 0, bufferedMs = 0)
        assertEquals(8_000_000L, b.bitsPerSecond(totalBytes = 240 * mb, bufferedMs = 30_000))
        assertEquals(8_000_000L, b.bitsPerSecond(totalBytes = 240 * mb, bufferedMs = 30_000))
    }

    @Test
    fun `a seek starts over, so the jump in buffered position is not divided into old bytes`() {
        val b = FileBitrate()
        b.bitsPerSecond(totalBytes = 0, bufferedMs = 0)
        b.bitsPerSecond(totalBytes = 80 * mb, bufferedMs = 10_000)
        b.restart()
        assertNull(b.bitsPerSecond(totalBytes = 80 * mb, bufferedMs = 1_800_000))
        assertEquals(4_000_000L, b.bitsPerSecond(totalBytes = 120 * mb, bufferedMs = 1_810_000))
    }

    @Test
    fun `a tracker reset or a discarded buffer starts over by itself`() {
        val b = FileBitrate()
        b.bitsPerSecond(totalBytes = 500 * mb, bufferedMs = 100_000)
        assertNull(b.bitsPerSecond(totalBytes = 0, bufferedMs = 100_000)) // counter reset
        assertNull(b.bitsPerSecond(totalBytes = 10 * mb, bufferedMs = 50_000)) // buffer went back
        assertEquals(2_000_000L, b.bitsPerSecond(totalBytes = 30 * mb, bufferedMs = 60_000))
    }
}
