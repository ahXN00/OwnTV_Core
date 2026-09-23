package tv.own.owntv.core.settings

/**
 * How close to the live edge to play Live TV, trading latency against stability. Applied on the next
 * channel open (live streams only — VOD is unaffected):
 *  - ExoPlayer live → a `MediaItem.LiveConfiguration` target offset (the main HLS latency lever);
 *  - mpv live → `demuxer-readahead-secs`.
 *
 * [BALANCED] is the default and applies no override — the engines keep their existing behaviour, so
 * it can never regress a stream that already works.
 */
enum class LiveLatency {
    LOW,
    BALANCED,
    STABLE,
    CUSTOM;

    companion object {
        val DEFAULT = BALANCED
        fun fromName(name: String?): LiveLatency = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Maps a [LiveLatency] choice to an effective buffer size in seconds (null = keep engine defaults). */
object LiveBuffer {
    const val LOW_SECS = 2
    const val STABLE_SECS = 15
    const val CUSTOM_MIN = 1
    const val CUSTOM_MAX = 60
    const val CUSTOM_DEFAULT = 8

    /** Below this many seconds counts as "lower than Balanced" — the low-latency warning threshold. */
    const val WARN_BELOW_SECS = CUSTOM_DEFAULT

    fun clampCustom(secs: Int): Int = secs.coerceIn(CUSTOM_MIN, CUSTOM_MAX)

    /** True when [secs] is a real, below-Balanced buffer worth warning the user about. */
    fun isLowLatency(secs: Int?): Boolean = secs != null && secs < WARN_BELOW_SECS

    /**
     * A per-playlist latency override, carrying an effective depth that is **itself** nullable —
     * Balanced means "engine defaults, no target offset". A plain `Int?` could not tell "this playlist
     * says Balanced" from "this playlist says nothing", which is the difference between overriding the
     * global setting and inheriting it.
     */
    @JvmInline
    value class Override(val secs: Int?)

    /** Effective live buffer in seconds for [mode]; null for [LiveLatency.BALANCED] (no override). */
    fun effectiveSeconds(mode: LiveLatency, customSecs: Int): Int? = when (mode) {
        LiveLatency.LOW -> LOW_SECS
        LiveLatency.STABLE -> STABLE_SECS
        LiveLatency.CUSTOM -> clampCustom(customSecs)
        LiveLatency.BALANCED -> null
    }

    /**
     * What Balanced has always meant in ExoPlayer's `DefaultLoadControl` (8 s resume / 10 s stop).
     * Every other preset is the same shape moved: `min = N`, `max = N + 2`, so the socket's idle
     * window — which is `max − min` in wall-clock, not the buffer depth — stays 2 s at every setting.
     * That narrow window is deliberate and load-bearing: a raw-TS socket parked longer gets culled by
     * provider restreamers, and the EOF that follows costs a visible reconnect.
     */
    const val BALANCED_SECS = 8
    private const val IDLE_WINDOW_SECS = 2

    /** Today's hardcoded start thresholds, and what "Pre-buffer = Off" still means. */
    const val DEFAULT_START_MS = 1_000
    const val DEFAULT_RESTART_MS = 2_000

    /** The "Pre-buffer" choices, in seconds. 0 = Off (engine defaults). */
    val PREROLL_CHOICES = listOf(0, 2, 5, 10)
    const val PREROLL_OFF = 0

    /** Resolved ExoPlayer `DefaultLoadControl` durations for a live tune. */
    data class LoadControlMs(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
    )

    /**
     * Turn the user's Live latency preset ([bufferSecs], null = Balanced) and "Pre-buffer"
     * choice ([prerollSecs], 0 = Off) into load-control durations (F06/F07).
     *
     * With the defaults (Balanced + Off) this returns **exactly** the values that were hardcoded
     * before, so the default Live TV behaviour is byte-identical.
     *
     * The pre-roll raises the buffer floor when it has to: `DefaultLoadControl` requires
     * `minBufferMs >= bufferForPlayback*`, and asking to buffer 10 s before starting means the buffer
     * must be allowed to hold 10 s in the first place — so a deep pre-roll wins over a shallow preset
     * rather than throwing.
     */
    fun loadControlFor(bufferSecs: Int?, prerollSecs: Int): LoadControlMs {
        val prerollMs = prerollSecs.coerceAtLeast(0) * 1000
        val startMs = if (prerollMs > 0) prerollMs else DEFAULT_START_MS
        val restartMs = if (prerollMs > 0) prerollMs else DEFAULT_RESTART_MS
        val depthSecs = bufferSecs ?: BALANCED_SECS
        val minMs = (depthSecs * 1000).coerceAtLeast(maxOf(startMs, restartMs))
        return LoadControlMs(
            minBufferMs = minMs,
            maxBufferMs = minMs + IDLE_WINDOW_SECS * 1000,
            bufferForPlaybackMs = startMs,
            bufferForPlaybackAfterRebufferMs = restartMs,
        )
    }

    /**
     * Byte cap for the live buffer. The default (24 MB / 16 MB low-RAM) is reached at ~7 s on a
     * 25 Mbps UHD stream, which is what keeps that socket reading continuously — but it also means a
     * user who asked for a 15 s buffer would silently get 7. Scale the cap with the depth so the
     * request is real, bounded so a 4K channel can never pin an unreasonable amount of the app heap.
     *
     * [prerollSecs] counts towards the depth for the same reason: [loadControlFor] raises the *time*
     * floor to hold the pre-roll, so a cap left at the Balanced size would stop the load before that
     * floor could ever be reached. On a high-bitrate feed even the scaled cap can be the binding one —
     * `LivePreviewEngine`'s re-buffer-flap detector is the backstop for that (it drops the pre-roll for
     * that stream), because no sane heap budget holds 10 s of a 4K channel.
     *
     * [lowSpec] caps the scaling at 2× instead of 3× (S16): on a 2 GB TV the live buffer's 48 MB stacks
     * with mpv's 48 MiB cache while an engine handoff overlaps the two.
     */
    fun targetBufferBytes(bufferSecs: Int?, prerollSecs: Int, defaultBytes: Int, lowSpec: Boolean = false): Int {
        val depthSecs = maxOf(bufferSecs ?: BALANCED_SECS, prerollSecs.coerceAtLeast(0))
        if (depthSecs <= BALANCED_SECS) return defaultBytes
        val scaled = defaultBytes.toLong() * depthSecs / BALANCED_SECS
        return scaled.coerceAtMost(defaultBytes.toLong() * if (lowSpec) 2 else 3).toInt()
    }
}
