package tv.own.owntv.core.player

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The v44 move of a DataStore set into `playback_quirks`, run at most once per install (owner
 * decision 14 keeps the old file on disk, untouched, for one release). Every read and write of the
 * store awaits it, so nothing is ever read from a half-filled table — a cold-start auto-tune included.
 *
 * [copy] must be idempotent: a crash between it and [markDone] simply repeats it next time.
 */
internal class CopyOnce(
    private val isDone: suspend () -> Boolean,
    private val markDone: suspend () -> Unit,
    private val copy: suspend () -> Unit,
) {
    private val mutex = Mutex()
    @Volatile private var done = false

    suspend fun ensure() {
        if (done) return
        mutex.withLock {
            if (done) return
            if (!isDone()) {
                copy()
                markDone()
            }
            done = true
        }
    }
}
