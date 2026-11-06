package tv.own.owntv.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.own.owntv.core.live.WatchSession

/**
 * Brings the background catalogue drain back as soon as playback of its playlist stops.
 *
 * The drain steps aside the moment the user watches something from the playlist it is filling, and
 * comes back after a fifteen-minute retry delay. That delay is right while the user is still watching,
 * but an evening of short programmes then barely moves the drain: most of its time goes to waiting
 * after the player has already closed. So when a playlist leaves [WatchSession], a pass resting behind
 * that delay is replaced by one starting [RESUME_DELAY_MINUTES] later.
 *
 * Nothing loads *during* playback: the drain still checks for playback before every page, and a pass
 * that starts while the user is back on a channel stops before its first request and rests again.
 * The short delay keeps a zap or a move from the preview pane to full screen from even starting one.
 */
class BackfillResume(
    watchSession: WatchSession,
    private val resume: suspend (sourceId: Long) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    init {
        scope.launch {
            var before = emptySet<Long>()
            watchSession.watching.collect { now ->
                (before - now).forEach { runCatching { resume(it) } }
                before = now
            }
        }
    }

    companion object {
        const val RESUME_DELAY_MINUTES = 1L
    }
}
