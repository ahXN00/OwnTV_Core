package tv.own.owntv.core.live

/**
 * What to do when a catch-up programme reaches its end and auto-play is on.
 *
 * A finished archive programme used to leave a black screen, which is the whole reason this exists.
 * The decision looks obvious — "play the next one" — but it has three outcomes, and picking the wrong
 * one is worse than doing nothing:
 *
 *  - the next programme has already finished airing → its archive is complete, play it;
 *  - the next programme is on the air **right now** → the viewer has caught up with the present, and
 *    its archive covers only the part that has aired, so it would end again within seconds and drop
 *    them back here. Hand over to the live stream instead — that IS the next programme;
 *  - anything else (no guide beyond this point, or a gap before the next entry) → stop, exactly as
 *    the app behaves today.
 *
 * Pure, no Android and no I/O, so the boundaries are unit-testable without a player or a database.
 */
object CatchupContinue {

    sealed interface Next {
        /** Play this already-aired programme from the channel's archive. */
        data class Programme(val startMs: Long, val stopMs: Long) : Next

        /** Caught up with the present — put the live stream on instead. */
        data object Live : Next

        /** Nothing to continue with. */
        data object Stop : Next
    }

    /**
     * [nextStartMs]/[nextStopMs] are the guide entry following the one that just ended, on the same
     * clock the user sees (any EPG shift already applied), or null when the guide stops there.
     */
    fun decide(nextStartMs: Long?, nextStopMs: Long?, nowMs: Long): Next = when {
        nextStartMs == null || nextStopMs == null -> Next.Stop
        nextStopMs <= nowMs -> Next.Programme(nextStartMs, nextStopMs)
        nextStartMs <= nowMs -> Next.Live
        else -> Next.Stop
    }

    /**
     * The archive on screen stopped arriving while it was playing (the player's `archiveStalled`):
     * should the viewer be handed to the live stream?
     *
     * Yes when the replayed programme ([programmeStopMs], on the user's clock) is still on air — its
     * archive is being written as it airs and the provider may stop serving it partway, as one did on
     * the TCL 13 minutes behind live; the live stream is that programme. Yes for a rewind
     * ([programmeStopMs] null) watched within [LIVE_EDGE_MS] of now ([watchingWallMs]) — it has run into
     * the end of the recording. Otherwise no: a finished programme's archive is complete, so its stall
     * is a network fault the player's own recovery owns, and live is not what the viewer asked for.
     */
    fun liveAfterStall(programmeStopMs: Long?, watchingWallMs: Long?, nowMs: Long): Boolean = when {
        programmeStopMs != null -> programmeStopMs > nowMs
        watchingWallMs != null -> nowMs - watchingWallMs <= LIVE_EDGE_MS
        else -> false
    }

    /** How close to now a rewind counts as having reached the end of the recording. */
    const val LIVE_EDGE_MS = 3 * 60_000L
}
