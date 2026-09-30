package tv.own.owntv.core.download

import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus

/**
 * Transfer speed over the last few seconds (Stage G3, "12.4 MB/s · 3 min left"). A sliding window
 * rather than bytes-since-start, so a resumed download or a stall shows the speed *now*.
 * Pure: the caller hands in the clock.
 */
class TransferRate(private val windowMs: Long = 5_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Record [bytes] transferred so far at [atMs]; returns bytes per second, or null until measurable. */
    fun sample(atMs: Long, bytes: Long): Long? {
        // A restart from zero (server ignored Range) must not read as a negative speed.
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(atMs to bytes)
        while (samples.size > 2 && atMs - samples.first().first > windowMs) samples.removeFirst()
        val (t0, b0) = samples.first()
        val elapsed = atMs - t0
        return if (elapsed < MIN_SPAN_MS) null else (bytes - b0) * 1000 / elapsed
    }

    companion object {
        private const val MIN_SPAN_MS = 900L

        /** Seconds until done at [bytesPerSecond]; null when the size or the speed is unknown. */
        fun secondsLeft(downloaded: Long, total: Long, bytesPerSecond: Long?): Long? {
            if (total <= 0 || bytesPerSecond == null || bytesPerSecond <= 0) return null
            return ((total - downloaded).coerceAtLeast(0) + bytesPerSecond - 1) / bytesPerSecond
        }
    }
}

/** The download queue's order, as the engine drains it: QUEUED and RUNNING rows, oldest first. */
object DownloadQueue {

    /** The row that runs just before [id] ("Queued · starts after …"); null when it is next or not queued. */
    fun startsAfter(rows: List<DownloadEntity>, id: Long): DownloadEntity? {
        val order = rows.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
            .sortedBy { it.createdAt }
        val i = order.indexOfFirst { it.id == id }
        return if (i > 0 && order[i].status == DownloadStatus.QUEUED) order[i - 1] else null
    }
}
