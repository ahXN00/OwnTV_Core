package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType

class DownloadRateTest {

    private val mb = 1_000_000L

    @Test fun `speed needs about a second of samples`() {
        val rate = TransferRate()
        assertNull(rate.sample(0, 0))
        assertNull(rate.sample(500, 6 * mb))
        assertEquals(12 * mb, rate.sample(1000, 12 * mb))
    }

    @Test fun `speed follows the last window, not the whole transfer`() {
        val rate = TransferRate(windowMs = 5_000)
        rate.sample(0, 0)
        for (t in 1..10) rate.sample(t * 1000L, t * 10 * mb) // 10 MB/s
        for (t in 11..16) rate.sample(t * 1000L, 100 * mb + (t - 10) * 2 * mb) // then 2 MB/s
        assertEquals(2 * mb, rate.sample(17_000, 114 * mb))
    }

    @Test fun `a restart from zero is not a negative speed`() {
        val rate = TransferRate()
        rate.sample(0, 50 * mb)
        rate.sample(1000, 60 * mb)
        assertNull(rate.sample(2000, 0))
        assertEquals(5 * mb, rate.sample(3000, 5 * mb))
    }

    @Test fun `time left rounds up and needs size and speed`() {
        assertEquals(180L, TransferRate.secondsLeft(downloaded = 0, total = 1800 * mb, bytesPerSecond = 10 * mb))
        assertEquals(1L, TransferRate.secondsLeft(downloaded = 99, total = 100, bytesPerSecond = 1000))
        assertNull(TransferRate.secondsLeft(0, 0, 10))
        assertNull(TransferRate.secondsLeft(0, 100, null))
        assertNull(TransferRate.secondsLeft(0, 100, 0))
    }

    private fun row(id: Long, status: DownloadStatus, created: Long) = DownloadEntity(
        id = id, profileId = 1, mediaType = MediaType.MOVIE, itemId = id, title = "T$id",
        streamUrl = "http://x/$id", status = status, createdAt = created,
    )

    @Test fun `queued item starts after the one ahead of it`() {
        val running = row(1, DownloadStatus.RUNNING, 10)
        val first = row(2, DownloadStatus.QUEUED, 20)
        val second = row(3, DownloadStatus.QUEUED, 30)
        val done = row(4, DownloadStatus.COMPLETED, 5)
        val paused = row(5, DownloadStatus.PAUSED, 15)
        val rows = listOf(second, done, first, paused, running)
        assertEquals(running, DownloadQueue.startsAfter(rows, 2))
        assertEquals(first, DownloadQueue.startsAfter(rows, 3))
        assertNull(DownloadQueue.startsAfter(rows, 1)) // running: not waiting
        assertNull(DownloadQueue.startsAfter(rows, 5)) // paused: not in the queue
    }
}
