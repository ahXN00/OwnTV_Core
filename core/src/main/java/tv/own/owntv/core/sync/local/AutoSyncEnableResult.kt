package tv.own.owntv.core.sync.local

/**
 * What came of asking the other device to allow automatic sync.
 *
 * Three answers, because the screen has to say three different things: it started (the switch goes
 * on), it was refused (the switch stays off and the other person said no), or the question never
 * arrived (the switch stays off and the other device was not reachable). A failure is not a refusal:
 * "they said no" and "nobody was there" lead to different advice, and neither is an error in this
 * device's settings.
 */
sealed interface AutoSyncEnableResult {
    /** The far side accepted, and the schedule is saved and running. */
    data object Enabled : AutoSyncEnableResult

    /** The far side said no, or nobody answered in time. Nothing changed on either side. */
    data object Declined : AutoSyncEnableResult

    /** The request never got an answer it could act on. */
    data class Failed(val reason: SyncFailure) : AutoSyncEnableResult
}
