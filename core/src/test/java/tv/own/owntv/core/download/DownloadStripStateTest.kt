package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType

/**
 * `downloadStripFor` is what both apps ask "is this item downloading, and how far along?", so the
 * television and the phone can only agree if this function decides it.
 */
class DownloadStripStateTest {

    private fun row(
        status: DownloadStatus,
        downloaded: Long = 0,
        total: Long = 0,
        id: Long = 1,
    ) = DownloadEntity(
        id = id,
        profileId = 1,
        mediaType = MediaType.MOVIE,
        itemId = id,
        title = "Item $id",
        streamUrl = "http://example.test/$id",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
    )

    @Test
    fun `no rows means no strip`() {
        assertNull(downloadStripFor(emptyList()))
    }

    @Test
    fun `all completed means no strip`() {
        val rows = listOf(
            row(DownloadStatus.COMPLETED, 10, 10, id = 1),
            row(DownloadStatus.COMPLETED, 20, 20, id = 2),
        )
        assertNull(downloadStripFor(rows))
    }

    @Test
    fun `a running row reports downloading with its fraction`() {
        val state = downloadStripFor(listOf(row(DownloadStatus.RUNNING, 25, 100)))!!
        assertEquals(DownloadStripKind.DOWNLOADING, state.kind)
        assertEquals(1, state.count)
        assertEquals(0.25f, state.progress!!, 0.0001f)
    }

    @Test
    fun `running with an unknown total is indeterminate`() {
        val state = downloadStripFor(listOf(row(DownloadStatus.RUNNING, 25, 0)))!!
        assertEquals(DownloadStripKind.DOWNLOADING, state.kind)
        assertNull(state.progress)
    }

    @Test
    fun `queued only reports queued and no fraction`() {
        val rows = listOf(row(DownloadStatus.QUEUED, id = 1), row(DownloadStatus.QUEUED, id = 2))
        val state = downloadStripFor(rows)!!
        assertEquals(DownloadStripKind.QUEUED, state.kind)
        assertEquals(2, state.count)
        assertNull(state.progress)
    }

    @Test
    fun `paused only reports paused and keeps the fraction`() {
        val state = downloadStripFor(listOf(row(DownloadStatus.PAUSED, 30, 60)))!!
        assertEquals(DownloadStripKind.PAUSED, state.kind)
        assertEquals(0.5f, state.progress!!, 0.0001f)
    }

    @Test
    fun `failed only reports failed and counts only the failures`() {
        val rows = listOf(
            row(DownloadStatus.FAILED, 5, 100, id = 1),
            row(DownloadStatus.COMPLETED, 100, 100, id = 2),
        )
        val state = downloadStripFor(rows)!!
        assertEquals(DownloadStripKind.FAILED, state.kind)
        assertEquals(1, state.count)
        assertTrue(state.isError)
        assertNull(state.progress)
    }

    @Test
    fun `mixed states report the in-progress framing over every active row`() {
        val rows = listOf(
            row(DownloadStatus.QUEUED, 0, 100, id = 1),
            row(DownloadStatus.PAUSED, 50, 100, id = 2),
            row(DownloadStatus.FAILED, 50, 100, id = 3),
            row(DownloadStatus.COMPLETED, 100, 100, id = 4),
        )
        val state = downloadStripFor(rows)!!
        assertEquals(DownloadStripKind.DOWNLOADING, state.kind)
        assertEquals(3, state.count)
        // 100 of 300 active bytes — the completed row is not counted on either side.
        assertEquals(1f / 3f, state.progress!!, 0.0001f)
    }

    @Test
    fun `progress never leaves zero to one even when the server undercounts`() {
        val state = downloadStripFor(listOf(row(DownloadStatus.RUNNING, 150, 100)))!!
        assertEquals(1f, state.progress!!, 0.0001f)
    }

    @Test
    fun `the tracker turns bytes into a fraction and clears when the transfer ends`() {
        val tracker = DownloadActivityTracker()
        assertNull(tracker.active.value)
        tracker.progress(DownloadProgress("A Film", 40, 200))
        val active = tracker.active.value!!
        assertEquals("A Film", active.title)
        assertEquals(0.2f, active.progress!!, 0.0001f)
        tracker.finished()
        assertNull(tracker.active.value)
    }

    @Test
    fun `the tracker has no fraction until the size is known`() {
        val tracker = DownloadActivityTracker()
        tracker.progress(DownloadProgress("A Film", 40, 0))
        assertNull(tracker.active.value!!.progress)
    }
}
