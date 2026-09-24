package tv.own.owntv.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Stop playing in a while, for a user who is falling asleep to it (N17; the phone's M14 fixes).
 *
 * One per process, because the countdown has to outlive the screen it was set on. Deliberately **not**
 * persisted: a timer that survived a restart would stop a stream someone started hours later.
 *
 * - **It counts against a deadline, not ticks.** The phone's first version subtracted a second per
 *   tick, so every late tick (a busy main thread, a sleeping CPU) made it run long. Each tick now reads
 *   [clock] — `elapsedRealtime`, which keeps counting in deep sleep — so a late wake-up fires at once.
 * - **It ends itself when playback ends.** [active] is the media session's "an engine is attached";
 *   once it has been false for [IDLE_GRACE_MS] the timer is dropped, so it cannot stop something the
 *   user starts later. The grace exists because a switch from a channel to a film detaches for a
 *   moment, and that is not the user stopping.
 * - **What "stop" means is the app's.** [stopPlayback] is assigned by the host: on a phone the tuner
 *   stops, on a television the player closes *and* the preview pane stops, which closing alone does not.
 */
class SleepTimer(
    active: Flow<Boolean>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** Assigned by the app; null (nothing to stop yet) makes a firing timer a no-op. */
    var stopPlayback: (() -> Unit)? = null

    private var job: Job? = null
    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Milliseconds left, or null when no timer is running. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    init {
        // Keyed on both, so a timer started while nothing is attached is dropped too, not only one
        // whose playback ends after it was set.
        scope.launch {
            combine(active, remainingMs.map { it != null }.distinctUntilChanged()) { playing, running -> !playing && running }
                .distinctUntilChanged()
                .collectLatest { idle ->
                    if (idle) {
                        delay(IDLE_GRACE_MS)
                        cancel()
                    }
                }
        }
    }

    /** Start (or replace) the countdown. A non-positive duration stops playback at once. */
    fun start(durationMs: Long) {
        job?.cancel()
        if (durationMs <= 0L) {
            job = null
            _remainingMs.value = null
            stopPlayback?.invoke()
            return
        }
        val deadline = clock() + durationMs
        _remainingMs.value = durationMs
        job = scope.launch {
            while (true) {
                val left = deadline - clock()
                if (left <= 0L) break
                _remainingMs.value = left
                delay(minOf(left, TICK_MS))
            }
            job = null
            _remainingMs.value = null
            stopPlayback?.invoke()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
    }

    companion object {
        /** The whole minutes both apps' labels show, rounded up: "Stops in 1 min" until it really is over. */
        fun minutesLeft(remainingMs: Long): Int = ((remainingMs + 59_999L) / 60_000L).toInt()

        /** What both apps offer, in minutes. Round numbers, because nobody falls asleep to 37. */
        val CHOICES_MINUTES: List<Int> = listOf(15, 30, 45, 60, 90)

        /** A second: the label counts down in minutes, and a coarser tick makes the last one lie. */
        internal const val TICK_MS = 1_000L

        /** How long nothing may play before the timer is dropped — longer than any engine handover. */
        internal const val IDLE_GRACE_MS = 3_000L
    }
}
