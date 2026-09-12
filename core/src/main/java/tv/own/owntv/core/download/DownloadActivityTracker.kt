package tv.own.owntv.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "a download is running" signal for the shell's status pill, shaped after
 * [tv.own.owntv.core.sync.SyncActivityTracker] and [tv.own.owntv.core.sync.EpgActivityTracker].
 * [DownloadEngine] reports here as it transfers, so the pill both apps already have can carry a
 * download line alongside the sync lines. Purely observational: nothing reads this to make decisions.
 *
 * Downloads run strictly one at a time ([DownloadEngine.drainQueue] serialises them), so there is at
 * most one active transfer — hence a single nullable rather than a map.
 */
class DownloadActivityTracker {

    data class ActiveDownload(val title: String, val downloadedBytes: Long, val totalBytes: Long) {
        /** 0f..1f when the size is known; null while the server has not said how big the file is. */
        val progress: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    private val _active = MutableStateFlow<ActiveDownload?>(null)

    /** The transfer currently running, or null when nothing is being downloaded. */
    val active: StateFlow<ActiveDownload?> = _active.asStateFlow()

    fun progress(progress: DownloadProgress) {
        _active.value = ActiveDownload(progress.title, progress.downloadedBytes, progress.totalBytes)
    }

    /** The transfer ended — completed, failed, paused or deleted. The pill simply drops the line. */
    fun finished() {
        _active.value = null
    }
}
