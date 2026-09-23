package tv.own.owntv.player

/**
 * Decides when a reconnected live stream has been playing long enough to earn back its reconnect
 * budget. A single advancing poll is not enough: a channel that plays for two seconds and dies again
 * would reset the budget on every attempt and reconnect for ever. Mirrors the ExoPlayer live engine's
 * [LivePreviewEngine.HEALTHY_MS] rule so both engines give up on the same kind of channel.
 */
internal class LiveHealthClock(private val healthyMs: Long = LivePreviewEngine.HEALTHY_MS) {
    private var sinceMs = -1L

    /** Playback advanced at [nowMs]. True once it has advanced without interruption for [healthyMs]. */
    fun onProgress(nowMs: Long): Boolean {
        if (sinceMs < 0) sinceMs = nowMs
        return nowMs - sinceMs >= healthyMs
    }

    /** Playback stopped advancing (stall, buffering, pause) — the sustained run starts over. */
    fun reset() {
        sinceMs = -1L
    }
}
