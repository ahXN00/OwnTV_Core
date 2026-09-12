package tv.own.owntv.core.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one running recording is currently doing, for the pill and the player's REC indicator. */
data class RecordingProgress(
    val id: Long,
    val title: String,
    val channelName: String,
    val bytes: Long,
    /** When the recording is due to stop, so the pill can say how much is left rather than how big it is. */
    val stopMs: Long,
)

/**
 * App-wide "something is recording" signal, shaped after
 * [tv.own.owntv.core.download.DownloadActivityTracker] and the sync trackers before it, so the pill
 * both apps already have can carry recording lines beside its sync and download ones (D13).
 *
 * A **map**, not a single nullable: unlike downloads, recordings run concurrently up to the
 * playlist's connection budget (D10), so there can be several at once and the pill shows a line for
 * each. Purely observational — nothing reads this to decide anything.
 */
class RecordingActivityTracker {

    private val _active = MutableStateFlow<Map<Long, RecordingProgress>>(emptyMap())

    /** Every recording currently running, oldest-started first. Empty when nothing is recording. */
    val active: StateFlow<Map<Long, RecordingProgress>> = _active.asStateFlow()

    fun progress(progress: RecordingProgress) {
        _active.value = _active.value + (progress.id to progress)
    }

    /** This one ended — finished, failed or stopped by hand. The pill simply drops its line. */
    fun finished(id: Long) {
        _active.value = _active.value - id
    }
}
