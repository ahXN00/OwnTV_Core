package tv.own.owntv.player

/**
 * The first rungs of mpv's "playback didn't start" ladder, run by [OwnTVPlayer] when an END_FILE arrives
 * before the item ever played. These rungs only change the request (another URL form, the same request
 * again, a tolerant demuxer), so which one applies is a pure decision. The rescue rungs after them — decoder
 * rescues, the User-Agent retry, ExoPlayer, the error — act as they decide and stay in the player.
 */
internal object StartFailureLadder {

    enum class Rung {
        /** The live channel's other format (`.ts` ⇄ `.m3u8`). */
        ALT_FORMAT,
        /** The catch-up archive's `timeshift.php` form. */
        CATCHUP_PHP,
        /** The same request again, after a backoff. */
        AUTO_RETRY,
        /** One pass with the demuxer's error tolerance on. */
        TOLERANT_DEMUX,
    }

    data class Input(
        val live: Boolean,
        val hasUrl: Boolean,
        val autoRetries: Int,
        val maxRetries: Int,
        /** The live channel has an untried other format (already worked out by the caller). */
        val altFormatAvailable: Boolean,
        /** The catch-up archive has an untried `timeshift.php` form. */
        val catchupPhpAvailable: Boolean,
        val triedTolerantDemux: Boolean,
        val lastMpvError: String?,
    )

    /** The rung to take now, or null when these are spent and the rescue rungs take over. */
    fun next(i: Input): Rung? = when {
        i.altFormatAvailable -> Rung.ALT_FORMAT
        i.catchupPhpAvailable -> Rung.CATCHUP_PHP
        // A panel that answered with an HTTP refusal will answer the identical request the same way; cut the
        // repeats short so the fallbacks that DO change the request run instead of a storm — for a film as
        // much as a channel: a dead film link took six opens before its error. A *busy* answer (458/429/408)
        // is not a refusal and keeps its full ladder — see [OwnTVPlayer.httpRefusalKind].
        i.autoRetries < i.maxRetries && i.hasUrl &&
            !((OwnTVPlayer.isHardHttpRefusal(i.lastMpvError) || (!i.live && OwnTVPlayer.isSessionLimitRefusal(i.lastMpvError))) &&
                i.autoRetries >= OwnTVPlayer.HARD_REFUSAL_MAX_RETRIES) -> Rung.AUTO_RETRY
        !i.triedTolerantDemux && i.hasUrl &&
            OwnTVPlayer.ladderRungUseful(OwnTVPlayer.LadderRung.TOLERANT_DEMUX, i.lastMpvError, i.live) -> Rung.TOLERANT_DEMUX
        else -> null
    }
}
