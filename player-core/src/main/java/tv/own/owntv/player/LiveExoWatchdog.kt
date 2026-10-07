package tv.own.owntv.player

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first

/**
 * Everything that decides a live channel on ExoPlayer is not working, and that mpv should be given a
 * turn.
 *
 * **This is the part that makes live TV survive real hardware**, and it is the reason a second engine
 * is worth having at all. The engine itself opens a stream and reports what happens; none of that
 * says when to stop believing it. These rungs were each added because a real channel on a real device
 * failed in a way nothing else caught:
 *
 * - **A picture that never arrives while the audio plays.** ExoPlayer never errors, so nothing times
 *   out — the user simply watches a black screen with sound.
 * - **Signed segment URLs the provider refuses.** Media3 can only re-issue the URL it already
 *   resolved, so retrying cannot recover it; mpv re-reads the playlist with a fresh token.
 * - **A stream that opens its playlist and then delivers nothing.** No frame, no error. The engine's
 *   own stall watchdog is armed only *after* the first successful play, so nothing fires and the
 *   spinner sits there for ever.
 * - **No decodable audio track.** An AC3/E-AC3/DTS stream on a box without that decoder plays
 *   silently and forever.
 * - **Played, then froze.** The engine's reconnect ladder is deliberately patient — well over two
 *   minutes of frozen picture before it admits defeat — and mpv, which often plays the very same
 *   channel, was never given a turn.
 *
 * It lived inside the television's `LiveViewModel` until now, which is why the phone had no second
 * engine to offer: not because anyone decided a phone should go without, but because the logic sat
 * where a phone could not reach it. Nothing here knows what a television is. It talks to a
 * [LivePreviewEngine] and calls back.
 *
 * **Every rung is gated on [stillOurs].** A watchdog outlives the tune that armed it — the user zaps,
 * backs out, or starts a film — and firing after that would stop a stream nobody complained about.
 * On the television's single-Activity player that stream can be a completely different programme.
 */
class LiveExoWatchdog(
    private val engine: LivePreviewEngine,
    /**
     * Whether the channel this watchdog was armed for is still the one playing on this engine. False
     * ends the watch silently — a newer tune owns the engine now.
     */
    private val stillOurs: () -> Boolean,
    /** Hand the channel to the next rung of the ladder, saying why in words a support log can use. */
    private val handOver: suspend (reason: String) -> Unit,
    /** The channel produced a picture: the opening budget has been met and its alarm can stand down. */
    private val onOpened: () -> Unit,
    /**
     * Give back a budget that was spent on a wait the app agreed to — an HTTP 429 with a
     * `Retry-After`, which is the panel naming the second this channel frees up.
     */
    private val postponeDeadline: (Long) -> Unit,
    /** One line per decision, for the support log. */
    private val log: (String) -> Unit,
) {

    /**
     * Watch a freshly started channel until it opens, fails, or proves it never will.
     *
     * Returns when the outcome is settled. A channel that opens continues into [watchAfterFirstFrame]
     * rather than returning, because "it started" is not the end of the story.
     */
    suspend fun watch(channelName: String) = coroutineScope {
        // Runs alongside the terminal-state wait below: audio and position can be progressing fine, so
        // ExoPlayer never reaches ERROR, while a video track never renders a single frame.
        launch {
            engine.noVideoDetected.first { it }
            if (stillOurs()) handOver("no video frame rendered (audio plays, no picture)")
        }
        launch {
            engine.segmentsRefused.first { it }
            if (stillOurs()) handOver("provider refuses ExoPlayer's signed segment URLs")
        }

        // Bounded, because "neither" is a real outcome. A pre-buffer is requested silence: 10s of it
        // means the first frame is *supposed* to be ~10s out, so the deadline moves with it or every
        // pre-buffered channel looks stuck.
        val openBudgetMs = OPEN_TIMEOUT_MS + engine.activePrerollSecs.coerceAtLeast(0) * 1000L
        var terminal: LivePreviewEngine.State?
        var waitsSeen = 0
        while (true) {
            terminal = withTimeoutOrNull(openBudgetMs) {
                engine.state.first {
                    it == LivePreviewEngine.State.PLAYING || it == LivePreviewEngine.State.ERROR
                }
            }
            if (terminal != null) break
            if (!stillOurs()) return@coroutineScope
            val waits = engine.providerBackOffsSpent
            // Nothing pending and no new wait since the last deadline → this really is a stuck open.
            if (engine.providerBackOff.value == null && waits == waitsSeen) break
            waitsSeen = waits
            // The budget just spent was the panel's own countdown, not this channel failing to open,
            // so give it back — otherwise the whole-tune deadline would abandon a channel that is
            // simply queued behind a wait we agreed to.
            postponeDeadline(openBudgetMs)
        }
        if (!stillOurs()) return@coroutineScope

        if (terminal == null) {
            handOver("ExoPlayer never opened it (${openBudgetMs / 1000}s, no frame and no error)")
            return@coroutineScope
        }
        if (terminal == LivePreviewEngine.State.ERROR) {
            // onPlayerError assigns the state before the detail, and a collector can resume inline on
            // Dispatchers.Main.immediate — so yield first, or the detail is always read as null.
            yield()
            handOver("ExoPlayer error before first frame: ${engine.errorInfo.value?.raw ?: engine.error.value}")
            return@coroutineScope
        }

        // One unconditional line per tune saying whether ExoPlayer ever opened it. Without this a
        // support log shows the tune and then nothing at all, which reads identically whether the
        // channel played, wedged with the watchers still waiting, or the watcher never ran.
        log("'$channelName' opened on ExoPlayer")
        onOpened()
        // Give the track list a moment to settle, then route silent streams to the other engine.
        delay(TRACK_SETTLE_MS)
        if (!stillOurs()) return@coroutineScope
        if (engine.audioUnsupported.value) {
            handOver("no decodable audio track")
            return@coroutineScope
        }
        watchAfterFirstFrame(channelName)
    }

    /**
     * Keep watching a channel that HAS opened, and hand it over if it then wedges for good.
     *
     * A brief re-buffer is not that — it is normal on live television and the engine recovers by
     * itself — so only a stall that outlasts [STALL_HANDOFF_MS] counts. Nothing here fires while the
     * channel is playing.
     */
    private suspend fun watchAfterFirstFrame(channelName: String) {
        var lastLoggedMs = 0L
        while (stillOurs()) {
            // Suspends for as long as the channel is healthy — LOADING means buffering or reconnecting.
            val left = engine.state.first { it != LivePreviewEngine.State.PLAYING }
            if (!stillOurs()) return
            if (left == LivePreviewEngine.State.IDLE) return // stopped or zapped away — not our business
            // Throttled: a stream that re-buffers several times a second would otherwise fill the log.
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastLoggedMs >= STALL_LOG_THROTTLE_MS) {
                lastLoggedMs = nowMs
                log("'$channelName' stopped playing (state=$left) — ${STALL_HANDOFF_MS / 1000}s to recover")
            }
            var recovered = if (left == LivePreviewEngine.State.ERROR) {
                null
            } else {
                withTimeoutOrNull(STALL_HANDOFF_MS) {
                    engine.state.first { it != LivePreviewEngine.State.LOADING }
                }
            }
            if (!stillOurs()) return
            // Out of time, but the engine's reconnect is receiving data: it is refilling, not dead, and
            // handing over now would throw away a stream about to show a picture.
            if (left != LivePreviewEngine.State.ERROR && recovered == null && engine.receivingData) {
                log("'$channelName' still stalled, but its reconnect is receiving data — ${STALL_REFILL_GRACE_MS / 1000}s more to show a picture")
                recovered = withTimeoutOrNull(STALL_REFILL_GRACE_MS) {
                    engine.state.first { it != LivePreviewEngine.State.LOADING }
                }
                if (!stillOurs()) return
            }
            if (recovered == LivePreviewEngine.State.PLAYING) continue // it came back — keep watching
            if (recovered == LivePreviewEngine.State.IDLE) return
            yield() // let onPlayerError finish assigning the detail (see the ERROR branch above)
            val reason = if (left == LivePreviewEngine.State.ERROR || recovered != null) {
                "ExoPlayer gave up mid-stream: ${engine.errorInfo.value?.raw ?: engine.error.value}"
            } else {
                "played, then stalled for ${STALL_HANDOFF_MS / 1000}s without recovering"
            }
            handOver(reason)
            // Handed over, but with nowhere to go ExoPlayer kept the channel and is still reconnecting.
            // Keep watching for one outcome the ladder can still answer: the stream's content turning
            // out unplayable on ExoPlayer, which mpv may play.
            if (!stillOurs() || !engine.stillReconnecting) return
            val settled = engine.state.first { it != LivePreviewEngine.State.LOADING }
            if (!stillOurs()) return
            if (settled == LivePreviewEngine.State.PLAYING) continue
            if (settled == LivePreviewEngine.State.ERROR && engine.contentBroken) {
                yield() // see the ERROR branch above
                handOver("ExoPlayer can't play this stream's content: ${engine.errorInfo.value?.raw ?: engine.error.value}")
            }
            return
        }
    }

    companion object {
        /**
         * How long a channel may take to show its first frame, *on top of* any requested pre-buffer.
         * Past this it is not slow, it is stuck.
         *
         * Was 25s while a channel could buffer for ever without starting; the engine now calls that in
         * about four seconds and fails the load ([LivePreviewEngine]'s open watchdog), so the only
         * thing left to wait for is a genuinely slow panel. Still not 5s: a 4K channel on a distant
         * panel legitimately spends several seconds on the first segment plus decoder setup, and
         * bouncing those off the faster engine costs more than the extra seconds save.
         */
        const val OPEN_TIMEOUT_MS = 12_000L

        /**
         * How long a channel that HAS played may stay stalled before it goes to the other engine.
         *
         * DERIVED from the engine's own death verdict rather than chosen independently. The two
         * numbers had drifted badly apart: this was a flat 30s while the engine calls a stalled feed
         * dead at 12s and reconnects at 13.5s, so the handoff sat through two of the engine's own
         * reconnect attempts — fifteen seconds of frozen picture spent waiting for a recovery that had
         * already been tried and failed.
         *
         * The grace on top is one reconnect's worth: the engine's first retry gets a fair chance to
         * actually open before the channel is offered to the other player. Sitting through a SECOND
         * one is what this no longer does.
         */
        val STALL_HANDOFF_MS = LivePreviewEngine.DEATH_VERDICT_MS + STALL_HANDOFF_GRACE_MS

        /**
         * Grace on top of the engine's own verdict: room for the engine's first reconnect to open, not
         * for a second one to be attempted.
         */
        private const val STALL_HANDOFF_GRACE_MS = 2_000L

        /**
         * Extra time for a reconnect that is already receiving data when [STALL_HANDOFF_MS] runs out.
         * Only then: a channel with nothing arriving is handed over at [STALL_HANDOFF_MS] as before.
         *
         * Measured on a 4K raw-TS channel with the upstream link pulled for 15s, the first reconnect
         * needs ~4s to show a picture: rebuild the source (~0.8s), the panel's redirect + first byte
         * (~1.3s), then refill to the restart threshold (~1.3s). The handoff landed 0.6s before the
         * picture came back, mid-refill, so a channel that recovered by itself was reported as failed.
         */
        private const val STALL_REFILL_GRACE_MS = 3_000L

        /** Let the track list settle after the first frame before judging the audio. */
        private const val TRACK_SETTLE_MS = 300L

        /** A flapping stream must not fill the log with the same line. */
        private const val STALL_LOG_THROTTLE_MS = 5_000L
    }
}
