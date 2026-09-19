package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which playlist the user is watching right now, so background work can step out of its way.
 *
 * This exists because [OpenStreamRegistry] cannot answer the question, although it looks as though
 * it should. That register is the **connection budget** — Multiview tiles and recordings claim
 * against it because they have to ask "may I open one more?". Fullscreen playback never asks, so it
 * never claims, so `openOn(sourceId).total > 0` is false for the single most important case there
 * is. The background catalogue drain was gated on exactly that expression and consequently never
 * yielded once; a channel played for four minutes on the reference portal and the drain paged
 * straight through it (plan N1f-3, §4D).
 *
 * The engines in `:player-core` cannot raise this themselves — they are handed a URL and have no
 * notion of a `sourceId`. So it is the host's playback screen, which holds the row, that opens and
 * closes a session. That makes this one of core's assigned hooks, in the same family as
 * `CoreBuildInfo` and `CrashRecorder.diagnostics`: core owns the state and the meaning, the app
 * supplies the event.
 *
 * Deliberately separate from [OpenStreamRegistry] rather than folded into it. Widening that
 * register would mean fullscreen playback spending from a budget shared with recordings, which
 * would start refusing Multiview tiles the user is entitled to. Nothing here can affect Multiview
 * or recordings at all.
 *
 * Also deliberately not persisted, for the same reason the claim register is not: a session left
 * behind by a crash would hold a drain off forever.
 */
class WatchSession {

    private val _watching = MutableStateFlow<Set<Long>>(emptySet())

    /** The playlists currently on screen. A set, because Multiview can show several at once. */
    val watching: StateFlow<Set<Long>> = _watching.asStateFlow()

    /** The user started watching something from [sourceId]. Safe to call twice. */
    fun open(sourceId: Long) {
        if (sourceId < 0) return
        _watching.value = _watching.value + sourceId
        Log.i(TAG, "watch open sourceId=$sourceId watching=${_watching.value}")
    }

    /** Playback of [sourceId] stopped. Safe to call for a session that was never opened. */
    fun close(sourceId: Long) {
        _watching.value = _watching.value - sourceId
        Log.i(TAG, "watch close sourceId=$sourceId watching=${_watching.value}")
    }

    /**
     * Nothing is playing at all — what a player screen calls when it is torn down and cannot say
     * which source it held. Cheaper and safer than leaking a session.
     */
    fun closeAll() {
        _watching.value = emptySet()
    }

    fun isWatching(sourceId: Long): Boolean = sourceId in _watching.value

    private companion object {
        const val TAG = "WatchSession"
    }
}
