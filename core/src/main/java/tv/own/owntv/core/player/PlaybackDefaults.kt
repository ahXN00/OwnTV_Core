package tv.own.owntv.core.player

/**
 * Playback defaults the settings store has to know before any engine exists.
 *
 * The engine that acts on them lives in the player, but the value a fresh install starts from is
 * part of the persisted setting, so it is declared here and referenced from the engine — never the
 * other way round, and never copied.
 */
object PlaybackDefaults {
    /** Settings → "Give up on a channel after", in seconds. Long enough for a 4K channel on a distant panel to
     *  open, short enough that a removed channel is not ninety seconds of black screen. */
    const val LIVE_TUNE_BUDGET_SECS = 30
}
