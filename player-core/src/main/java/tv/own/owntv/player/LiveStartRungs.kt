package tv.own.owntv.player

/**
 * Which rung the live ExoPlayer engine takes when a channel fails before its first picture — the order of
 * [LivePreviewEngine]'s `onPlayerError` ladder as a pure decision. The provider back-off sits between
 * [before] and [after] and stays in the engine: it acts as it decides (it starts the countdown).
 */
internal object LiveStartRungs {

    enum class Rung {
        /** A `.ts` endpoint answered with an HLS manifest: reopen the same URL as HLS. */
        REDIRECT_HLS,
        /** The panel was learned as HLS, but this channel is plain TS: drop the lesson, reopen as TS. */
        DROP_HOST_HLS_LESSON,
        /** The response was a DASH manifest: reopen the same URL as DASH. */
        REDIRECT_DASH,
        /** Refused while the previous engine still holds the one session: wait it out once. */
        SESSION_WAIT,
        /** Refused on the User-Agent: once more under the neutral one. */
        FALLBACK_USER_AGENT,
        /** Every hardware decoder is taken: say so and stop. */
        DECODER_EXHAUSTED,
        /** The decoder died before the first frame: again on a fresh one, see [decoderRebuildDelayMs]. */
        REBUILD_DECODER,
        /** The endpoint is the wrong shape: its `.ts` / `.m3u8` sibling. */
        ALT_FORMAT,
        /** The panel's own address for the channel — the last rung. */
        DIRECT_SOURCE,
        /** Nothing left: the error, which hands the channel to mpv. */
        FAIL,
    }

    data class Input(
        val containerUnsupported: Boolean,
        val formatFailure: Boolean,
        /** The HTTP status the failure carried, or null when no response status was involved. */
        val httpStatus: Int?,
        val decoderFailure: Boolean,
        val decoderExhausted: Boolean,
        /** The HTTP status is an identity refusal (the User-Agent, not the request). */
        val identityRefusal: Boolean,
        val activeIsHls: Boolean,
        val activeIsDash: Boolean,
        val responseWasHls: Boolean,
        val responseWasDash: Boolean,
        val hlsFromHostLesson: Boolean,
        val redirectedHlsRetryDone: Boolean,
        val redirectedDashRetryDone: Boolean,
        val sessionLimitSeen: Boolean,
        val sessionLimitRetryDone: Boolean,
        /** No UA retry yet, the source sets no UA of its own, and the fallback is not already in use. */
        val fallbackUserAgentAvailable: Boolean,
        /** Decoder rebuilds this load has already spent. */
        val decoderRebuildsDone: Int,
        val altFormatRetryDone: Boolean,
        /** [LivePreviewEngine.shouldTryDirectSource] for this failure. */
        val directSourceUseful: Boolean,
    )

    /** The rungs before the provider back-off, or null to go on to it. */
    fun before(i: Input): Rung? = when {
        // Some Xtream panels advertise a `.ts` endpoint but redirect it to an `.m3u8` manifest; content type
        // selection happened before the redirect, so the progressive extractor sees `#EXTM3U`.
        !i.redirectedHlsRetryDone && !i.activeIsHls && i.responseWasHls && i.containerUnsupported -> Rung.REDIRECT_HLS
        i.hlsFromHostLesson && LiveStreamQuirks.disprovesHlsLesson(i.formatFailure, i.httpStatus) ->
            Rung.DROP_HOST_HLS_LESSON
        !i.redirectedDashRetryDone && !i.activeIsDash && i.responseWasDash && i.containerUnsupported -> Rung.REDIRECT_DASH
        i.sessionLimitSeen && !i.sessionLimitRetryDone -> Rung.SESSION_WAIT
        else -> null
    }

    /** The rungs after the provider back-off; [Rung.FAIL] when every one is spent. */
    fun after(i: Input): Rung = when {
        i.fallbackUserAgentAvailable && i.identityRefusal -> Rung.FALLBACK_USER_AGENT
        // Checked before the rebuild, which is for a decoder that *died*, not for one that was never free.
        i.decoderExhausted -> Rung.DECODER_EXHAUSTED
        i.decoderFailure && decoderRebuildDelayMs(i.decoderRebuildsDone) != null -> Rung.REBUILD_DECODER
        !i.altFormatRetryDone && i.formatFailure -> Rung.ALT_FORMAT
        i.directSourceUseful -> Rung.DIRECT_SOURCE
        else -> Rung.FAIL
    }

    /**
     * How long to let the old codec go before rebuild number [rebuildsDone] + 1, or null when they are
     * spent. One rebuild was not always enough: a V4L2 decoder on a Pi 4 refused its configuration
     * right after a channel change and again on the immediate rebuild, while a manual Retry a moment
     * later played the channel. Waiting longer each time gives the hardware time to settle; three keep
     * the whole series to about 13 s, inside the default "Give up after" with room left for the other
     * engine. A shorter "Give up after" still ends the tune on time: its alarm stops the series.
     */
    fun decoderRebuildDelayMs(rebuildsDone: Int): Long? = DECODER_REBUILD_DELAYS_MS.getOrNull(rebuildsDone)

    /**
     * Whether an open that buffered but never started is the decoder's fault: a decoder already failed
     * on this load and the video renderer is still not ready. The rebuilt codec can fail silently —
     * audio buffers, the picture never comes and no error is raised — so without this the series of
     * rebuilds ended at the first quiet failure.
     */
    fun stalledOpenIsDecoder(decoderFailedThisLoad: Boolean, videoReady: Boolean?): Boolean =
        decoderFailedThisLoad && videoReady == false

    private val DECODER_REBUILD_DELAYS_MS = listOf(500L, 1_500L, 3_000L)
}
