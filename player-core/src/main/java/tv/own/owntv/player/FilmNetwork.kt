package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/**
 * N18 — a film's buffer, network timeout and mid-play reconnects, resolved against the device.
 *
 * Films only: live has its own latency, pre-buffer and "Give up after" settings and its own reconnect
 * rules. Every "Auto" (0) is exactly what the engines did before these settings existed.
 *
 * **The buffer is seconds, never memory.** Each engine's byte ceiling stays the device tier's
 * ([PlayerBudget]), so a longer buffer cannot cost more memory than today — on a 2 GB television
 * (48 MiB) a 4K film fills the ceiling long before the seconds run out. That is why the setting reads
 * "up to", and why nothing here needs a separate low-memory clamp.
 */
internal object FilmNetwork {

    /** mpv's own wait on a silent server, which Auto keeps. ExoPlayer's is its HTTP client's. */
    const val MPV_AUTO_TIMEOUT_SECS = 60

    /** Playback since the last reopen that gives the reconnect budget back — live's rule too. */
    const val HEALTHY_MS = 60_000L

    /** Seconds of film to buffer ahead: the user's choice, or the tier's. */
    fun bufferSecs(userSecs: Int, budget: PlayerBudget): Int =
        if (userSecs > 0) userSecs else budget.cacheSecs.toIntOrNull() ?: 30

    /** mpv's `demuxer-readahead-secs`: the user's choice, or the tier's own (which differs from its cache). */
    fun readaheadSecs(userSecs: Int, budget: PlayerBudget): String =
        if (userSecs > 0) userSecs.toString() else budget.readaheadSecs

    fun mpvTimeoutSecs(userSecs: Int): Int = if (userSecs > 0) userSecs else MPV_AUTO_TIMEOUT_SECS

    /**
     * Whether a film that dropped at [posMs] may be reopened, as the new count of attempts used — or
     * null when [budget] is spent. A minute of playback since the last reopen (at [lastAtPosMs]) starts
     * the count again, so a two-hour film on a line that drops once an hour never runs out.
     */
    fun nextReconnect(used: Int, lastAtPosMs: Long, posMs: Long, budget: Int): Int? {
        val counted = if (used > 0 && posMs - lastAtPosMs >= HEALTHY_MS) 0 else used
        return if (counted < budget) counted + 1 else null
    }
}
