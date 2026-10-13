package tv.own.owntv.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/** Everything a live tune hands an engine besides the URL. */
data class LiveRequest(
    val meta: MediaMeta,
    val userAgent: String?,
    val prerollSecs: Int?,
    val liveBuffer: LiveBuffer.Override?,
    val httpHeaders: String?,
    val drmConfig: String?,
    val manifestType: String?,
    val directSource: String?,
)

/** What mpv did with a channel it was just given: a picture (or the spinner clearing), or an error. */
data class MpvOutcome(val opened: Boolean, val error: String?)

/**
 * The two live engines, as [LiveTuneController] drives them.
 *
 * Exactly the operations the controller uses and nothing more. It exists so the controller's
 * sequencing — superseding tunes, the ladder, the give-up alarm — can be unit-tested against a fake;
 * production is [EnginePair], a thin pass-through to the real engines.
 */
interface LiveEngines {
    /** What ExoPlayer holds right now, or null. */
    val exoUrl: String?
    val exoIsHls: Boolean
    val exoFailed: Boolean
    /** ExoPlayer is working through its own reconnects after having played (see [LivePreviewEngine.stillReconnecting]). */
    val exoStillReconnecting: Boolean get() = false
    /** ExoPlayer cannot play this stream's content (see [LivePreviewEngine.contentBroken]). */
    val exoContentBroken: Boolean get() = false
    /** Whether mpv holds a stream (full-screen live, a film, a catch-up). */
    val mpvHasStream: Boolean

    fun exoPlay(url: String, muted: Boolean, request: LiveRequest)
    fun exoSetMuted(muted: Boolean)
    fun exoStop()
    fun exoReleaseUhdDecoder()
    fun exoAbandon(reason: String)

    /** Run [LiveExoWatchdog] on ExoPlayer until the outcome is settled. */
    suspend fun watchExo(
        channelName: String,
        stillOurs: () -> Boolean,
        handOver: suspend (String) -> Unit,
        onOpened: () -> Unit,
        postponeDeadline: (Long) -> Unit,
        log: (String) -> Unit,
        openTimeoutMs: Long = LiveExoWatchdog.OPEN_TIMEOUT_MS,
    )

    fun mpvPlay(url: String, request: LiveRequest)
    fun mpvStop()
    suspend fun mpvStopAndAwaitRelease()
    fun mpvAbandon(reason: String)

    /**
     * Wait up to [timeoutMs] for mpv to open or fail; null when it did neither.
     *
     * **"Opened" is an mpv signal from this load only** — first frame, playback-restart, or advancing
     * time-pos, tied to the load generation captured at [mpvPlay]. Never the app's own buffering flag,
     * and never mpv's paused-for-cache: both are stale or seeded true at load and say nothing about
     * whether this stream arrived. Covers audio-only and radio. An error wins.
     */
    suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome?

    /** The Stalker re-resolve hook, installed on both engines, or cleared with null. */
    fun setReconnectProvider(provider: ReconnectUrlProvider?)

    /** How far into its current stream the live engine on screen is — for a saved copy's clock (N4). */
    fun positionMs(onExo: Boolean): Long = 0L

    // --- Host failover (design §5, §8) ---

    /** What ExoPlayer's current failure says about the server (typed, A5). */
    val exoHostFailure: tv.own.owntv.core.network.HostFailureClass get() = tv.own.owntv.core.network.HostFailureClass.UNKNOWN

    /** The last mpv/FFmpeg error line of the current load, for the A5 message table; null if none. */
    val mpvLastError: String? get() = null

    /** Wait until a channel that had played on ExoPlayer has used up its own reconnects. */
    suspend fun awaitExoReconnectsExhausted() { kotlinx.coroutines.awaitCancellation() }

    /** Wait until mpv gives a live channel that had played up as lost (its stall budget is spent). */
    suspend fun awaitMpvLiveLost() { kotlinx.coroutines.awaitCancellation() }
}

/** Start [url] on a live ExoPlayer engine with everything [request] carries — a tune, or a Multiview tile. */
internal fun LivePreviewEngine.play(url: String, muted: Boolean, request: LiveRequest) = play(
    url,
    muted = muted,
    meta = request.meta,
    userAgent = request.userAgent,
    prerollSecsOverride = request.prerollSecs,
    liveBufferOverride = request.liveBuffer,
    httpHeaders = request.httpHeaders,
    drmConfig = request.drmConfig,
    manifestType = request.manifestType,
    directSource = request.directSource,
)

/**
 * Opened only on an mpv signal from THIS load (first frame, playback-restart or advancing time-pos,
 * tied to [loadGeneration]); never the app's own buffering flag nor mpv's paused-for-cache, which are
 * stale or seeded at load. Covers audio-only and radio. An error wins.
 *
 * mpv's own retry/format ladder reloads bump the generation, which is monotonic, so a later load of
 * the same tune counts (stop/zap is covered by the controller's isStillMpv).
 */
internal suspend fun awaitMpvOutcomeOf(
    loadGeneration: Int,
    startedGeneration: Flow<Int>,
    error: Flow<Any?>,
    timeoutMs: Long,
): MpvOutcome? = withTimeoutOrNull(timeoutMs) {
    combine(startedGeneration, error) { started, err ->
        when {
            err != null -> MpvOutcome(opened = false, error = err.toString())
            started >= loadGeneration -> MpvOutcome(opened = true, error = null)
            else -> null
        }
    }.first { it != null }
}

/** The real engines: the live ExoPlayer engine and the process-wide mpv player. */
class EnginePair(private val exo: LivePreviewEngine, private val mpv: OwnTVPlayer) : LiveEngines {
    override val exoUrl: String? get() = exo.currentUrl
    override val exoIsHls: Boolean get() = exo.isHlsStream
    override val exoFailed: Boolean get() = exo.state.value == LivePreviewEngine.State.ERROR
    override val exoStillReconnecting: Boolean get() = exo.stillReconnecting
    override val exoContentBroken: Boolean get() = exo.contentBroken
    override val mpvHasStream: Boolean get() = mpv.hasActiveStream

    override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) = exo.play(url, muted, request)

    override fun exoSetMuted(muted: Boolean) = exo.setMuted(muted)
    override fun exoStop() = exo.stop()
    override fun exoReleaseUhdDecoder() = exo.releaseDecoderForUhd()
    override fun exoAbandon(reason: String) { exo.abandon(reason) }

    override suspend fun watchExo(
        channelName: String,
        stillOurs: () -> Boolean,
        handOver: suspend (String) -> Unit,
        onOpened: () -> Unit,
        postponeDeadline: (Long) -> Unit,
        log: (String) -> Unit,
        openTimeoutMs: Long,
    ) {
        LiveExoWatchdog(exo, stillOurs, handOver, onOpened, postponeDeadline, log, openTimeoutMs).watch(channelName)
    }

    override fun mpvPlay(url: String, request: LiveRequest) = mpv.play(
        url = url,
        title = request.meta.title,
        subtitle = request.meta.subtitle,
        logoUrl = request.meta.logoUrl,
        isLive = true,
        muted = false,
        userAgent = request.userAgent,
        httpHeaders = request.httpHeaders,
        // The same stable key ExoPlayer files this channel under, so a zoom or volume set on one
        // engine is not forgotten when the channel falls back to the other.
        contentKey = request.meta.contentKey,
        livePrerollSecsOverride = request.prerollSecs,
        liveBufferOverride = request.liveBuffer,
    )

    override fun mpvStop() = mpv.stop()
    override suspend fun mpvStopAndAwaitRelease() { mpv.stopAndAwaitRelease() }
    override fun mpvAbandon(reason: String) = mpv.abandonLive(reason)

    override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? {
        val gen = mpv.currentLoadGeneration
        return awaitMpvOutcomeOf(gen, mpv.mpvStartedGeneration, mpv.error, timeoutMs)
    }

    override fun setReconnectProvider(provider: ReconnectUrlProvider?) {
        exo.reconnectUrlProvider = provider
        mpv.reconnectUrlProvider = provider
    }

    override fun positionMs(onExo: Boolean): Long = if (onExo) exo.livePositionMs() else mpv.position.value

    override val exoHostFailure: tv.own.owntv.core.network.HostFailureClass get() = exo.hostFailure
    override val mpvLastError: String? get() = mpv.lastErrorLine

    override suspend fun awaitExoReconnectsExhausted() { exo.reconnectsExhausted.first() }

    override suspend fun awaitMpvLiveLost() {
        mpv.error.first { it == PlaybackFailure.LostConnection }
    }
}
