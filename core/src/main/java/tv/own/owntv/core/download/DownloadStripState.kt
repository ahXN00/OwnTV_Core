package tv.own.owntv.core.download

import androidx.compose.runtime.Immutable
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus

/**
 * Display-only download state shared by both apps, so a television and a phone never disagree about
 * what "downloading" means. Pure data over core entities — the drawing stays in each app.
 */
@Immutable
enum class DownloadStripKind { DOWNLOADING, QUEUED, PAUSED, FAILED }

@Immutable
data class DownloadStripState(
    val kind: DownloadStripKind,
    val count: Int,
    /** 0f..1f when a size is known; null = indeterminate (queued / unknown total). */
    val progress: Float?,
) {
    val isError: Boolean get() = kind == DownloadStripKind.FAILED
}

/**
 * Builds a strip state from the download rows that belong to one item (a single movie/episode, or all
 * of a series' episodes). Returns null when nothing is in flight — i.e. no rows, or every row already
 * COMPLETED — so the caller can hide the strip. FAILED rows still surface so the user isn't left guessing.
 */
fun downloadStripFor(rows: List<DownloadEntity>): DownloadStripState? {
    val active = rows.filter { it.status != DownloadStatus.COMPLETED }
    if (active.isEmpty()) return null

    val running = active.filter { it.status == DownloadStatus.RUNNING }
    val paused = active.filter { it.status == DownloadStatus.PAUSED }
    val failed = active.filter { it.status == DownloadStatus.FAILED }
    val queued = active.filter { it.status == DownloadStatus.QUEUED }

    val downloaded = active.sumOf { it.downloadedBytes }
    val total = active.sumOf { it.totalBytes }
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    return when {
        running.isNotEmpty() -> DownloadStripState(DownloadStripKind.DOWNLOADING, active.size, fraction)
        queued.isNotEmpty() && paused.isEmpty() && failed.isEmpty() -> DownloadStripState(DownloadStripKind.QUEUED, active.size, null)
        paused.isNotEmpty() && running.isEmpty() && failed.isEmpty() -> DownloadStripState(DownloadStripKind.PAUSED, active.size, fraction)
        failed.isNotEmpty() && running.isEmpty() && queued.isEmpty() && paused.isEmpty() ->
            DownloadStripState(DownloadStripKind.FAILED, failed.size, null)
        // Mixed states (some queued/paused/failed together) → report the in-progress framing.
        else -> DownloadStripState(DownloadStripKind.DOWNLOADING, active.size, fraction)
    }
}
