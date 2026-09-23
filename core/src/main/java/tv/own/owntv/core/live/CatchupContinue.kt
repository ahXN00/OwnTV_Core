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
}
