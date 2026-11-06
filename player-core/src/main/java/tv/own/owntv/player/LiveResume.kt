package tv.own.owntv.player

/**
 * Play after a long pause on a live channel goes back to the live edge instead of resuming the old buffer.
 *
 * A paused live engine keeps whatever it had buffered, and the panel's stream has moved on: resuming it
 * played minutes-old pictures, or froze on data the server had long dropped. A short pause still resumes
 * where it was. The saved copy ([tv.own.owntv.core.timeshift.TimeshiftServer]) is the one live stream
 * that is *meant* to be paused and continued, so it never jumps; a catch-up or a rewind into the archive
 * is not live content at all and never reaches this.
 */
internal object LiveResume {
    const val LIVE_EDGE_AFTER_PAUSE_MS = 60_000L

    /** [pausedAtMs] is 0 when nothing paused this load. */
    fun backToLiveEdge(live: Boolean, fromSavedCopy: Boolean, pausedAtMs: Long, nowMs: Long): Boolean =
        live && !fromSavedCopy && pausedAtMs > 0 && nowMs - pausedAtMs > LIVE_EDGE_AFTER_PAUSE_MS
}
