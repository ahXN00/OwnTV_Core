package tv.own.owntv.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "a catalog sync is running" signal for the shell's unobtrusive status pill. Every sync —
 * foreground add, backgrounded add, WorkManager remainder/refresh — funnels through
 * [SyncManager.sync], which reports here.
 *
 * [active] is a decision input as well as a display one. Two readers depend on it: the background
 * catalogue drain yields while a sync of the source it is draining is running, and a host app's
 * post-sync discovery pass waits for this map to settle empty and abandons a pass when one starts.
 * Those readers are why this is a map keyed by source id rather than a flag — several playlists
 * syncing at once collapse into a single non-empty-to-empty transition. [lastCompleted] is the
 * pill's alone.
 */
class SyncActivityTracker {

    data class ActiveSync(val sourceId: Long, val sourceName: String, val stage: ImportStage? = null)

    data class CompletedSync(val sourceId: Long, val sourceName: String, val result: SyncResult, val timestamp: Long)

    private val _active = MutableStateFlow<Map<Long, ActiveSync>>(emptyMap())
    private val _lastCompleted = MutableStateFlow<CompletedSync?>(null)

    /** All currently-running syncs keyed by sourceId (usually 0 or 1 entries). */
    val active: StateFlow<Map<Long, ActiveSync>> = _active.asStateFlow()

    /** The last completed sync (success, failure, or cancellation). */
    val lastCompleted: StateFlow<CompletedSync?> = _lastCompleted.asStateFlow()

    fun started(sourceId: Long, sourceName: String) {
        _active.value = _active.value + (sourceId to ActiveSync(sourceId, sourceName))
    }

    fun progress(sourceId: Long, stage: ImportStage) {
        val existing = _active.value[sourceId] ?: return
        _active.value = _active.value + (sourceId to existing.copy(stage = stage))
    }

    fun finished(sourceId: Long, sourceName: String, result: SyncResult) {
        _active.value = _active.value - sourceId
        _lastCompleted.value = CompletedSync(sourceId, sourceName, result, android.os.SystemClock.elapsedRealtime())
    }

    /**
     * Clears a completed sync once the pill has picked it up for display, so it isn't re-shown on
     * every fresh composition of the pill (e.g. after exiting fullscreen playback). Guarded by
     * timestamp so a newer completion that arrived in the meantime is never dropped.
     */
    fun consumeCompleted(timestamp: Long) {
        if (_lastCompleted.value?.timestamp == timestamp) {
            _lastCompleted.value = null
        }
    }
}
