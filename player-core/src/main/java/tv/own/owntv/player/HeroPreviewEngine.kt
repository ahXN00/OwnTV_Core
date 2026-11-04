package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tv.own.owntv.core.network.HttpClient

/**
 * ExoPlayer engine for the Home screen hero preview.
 *
 * It is intentionally small: muted only, VOD start-position support, no HUD integration, and a single surface.
 * The Home screen keeps it alive while the hero is focused so the preview starts quickly and can be
 * reused across hero items without rebuilding the player each time.
 */
@UnstableApi
class HeroPreviewEngine(
    private val context: Context,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    settings: tv.own.owntv.core.settings.SettingsRepository? = null,
    /** True while another engine (mpv) already holds a stream — used for the one-session guard. */
    private val streamInUse: () -> Boolean = { false },
) {
    /** Mirrors Settings → Picture → Hardware decoding, like every other engine. Read at [build]
     *  time; a change rebuilds on the next preview (see the decode-path check in [play]). */
    @Volatile private var hwDecodingEnabled = true

    init {
        settings?.hwDecoding
            ?.onEach { hwDecodingEnabled = it }
            ?.launchIn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate))
    }

    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var hasStarted = false

    // No-frame watchdog. A hero stream that connects but never renders (dead decoder, audio-only
    // remux, a server that accepts the socket and dribbles) used to leave the engine in LOADING
    // forever, which the Home screen draws as a spinner on top of the poster with nothing behind it.
    // Every other engine bounds this; here it just falls back to the poster.
    private val noFrameWatchdog = NoFrameWatchdog {
        // Deliberately NOT gated on [hasStarted]: that flag is set on STATE_READY, which a stream that
        // buffers fine and never renders a frame reaches — and the preview only leaves LOADING on the
        // first frame, so the guard disarmed the watchdog for exactly the case it exists for and left a
        // permanent spinner over the poster. The deadline is dropped on first frame, error, stop and
        // release, so reaching here means no frame arrived.
        if (currentUrl != null) {
            android.util.Log.w(TAG, "Hero preview produced no frame in ${NO_FRAME_TIMEOUT_MS}ms — falling back to the poster")
            stop()
            _state.value = State.ERROR
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    var currentUrl: String? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (currentUrl == null && playbackState != Player.STATE_IDLE) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!hasStarted) _state.value = State.LOADING
                }
                Player.STATE_READY -> {
                    hasStarted = true
                }
                Player.STATE_ENDED -> {
                    // REPEAT_MODE_ONE should keep the current clip looping; this is just a fallback.
                }
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            noFrameWatchdog.disarm()
            if (currentUrl != null) _state.value = State.PLAYING
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "Hero preview error: ${error.errorCodeName}", error)
            // HTTP 458 = "account session in use": the provider allows one stream at a time. Remember it
            // so every engine (Live preview included) stops competing for that single session.
            currentUrl?.let { url -> if (httpStatusOf(error) == 458) LiveStreamQuirks.rememberSessionLimit(url) }
            noFrameWatchdog.disarm()
            hasStarted = false
            currentUrl = null
            player?.run {
                stop()
                clearMediaItems()
            }
            _state.value = State.ERROR
        }
    }

    /** The HTTP status behind a load failure — shared with the other ExoPlayer engines. */
    private fun httpStatusOf(error: Throwable?): Int? = PlayerErrors.httpStatusOf(error)

    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    private var builtForUa: String = HttpClient.DEFAULT_USER_AGENT
    private var builtForHeaders: Map<String, String> = emptyMap()
    private var builtForSoftware = false

    /** [httpHeaders] = this item's own `Key: Value` request headers (M3U `#EXTVLCOPT`/`#EXTHTTP`), or
     *  null for none. A stream that needs a Referer to open needs it here too, or the hero preview is
     *  the one place in the app that gets a 403 while the player itself plays the item fine. */
    fun play(url: String, seekToMs: Long = 0L, userAgent: String? = null, httpHeaders: String? = null) {
        // One-session provider with its single stream already in use: previewing here would knock the
        // user's own playback off the air (or just fail with a 458). Stay on the poster instead —
        // the same rule the Live preview pane follows.
        if (streamInUse() && LiveStreamQuirks.isSingleSession(url)) {
            stop()
            return
        }
        val headers = tv.own.owntv.core.network.StreamHeaders.decode(httpHeaders)
        // An item's own User-Agent is more specific than the source-wide one, so it wins — same rule as
        // the live and VOD engines.
        val effectiveUa = tv.own.owntv.core.network.StreamHeaders.userAgentOf(headers)
            ?: userAgent?.takeIf { it.isNotBlank() }
            ?: HttpClient.DEFAULT_USER_AGENT
        val requestHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        currentUrl = url
        val startPositionMs = seekToMs.coerceAtLeast(0L)
        hasStarted = false
        _state.value = State.LOADING
        runCatching {
            // Rebuild when the request identity or the decode path no longer matches what this player was
            // built with: the data-source factory and the renderer factory are both fixed at construction.
            val wantSoftware = !hwDecodingEnabled
            if (effectiveUa != builtForUa || requestHeaders != builtForHeaders || wantSoftware != builtForSoftware) {
                player?.release(); player = null
            }
            val p = player ?: build(effectiveUa, requestHeaders).also {
                player = it
                builtForUa = effectiveUa
                builtForHeaders = requestHeaders
                builtForSoftware = wantSoftware
            }
            surface?.let { p.setVideoSurface(it) }
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.setMediaItem(MediaItem.fromUri(url), startPositionMs)
            p.prepare()
            p.playWhenReady = true
            noFrameWatchdog.arm(NO_FRAME_TIMEOUT_MS)
        }.onFailure {
            android.util.Log.w(TAG, "Hero preview play failed for ${HttpClient.redactUrl(url)}", it)
            hasStarted = false
            currentUrl = null
            player?.run {
                stop()
                clearMediaItems()
            }
            _state.value = State.ERROR
        }
    }

    fun stop() {
        noFrameWatchdog.disarm()
        currentUrl = null
        hasStarted = false
        _state.value = State.IDLE
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        noFrameWatchdog.disarm()
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        surface = null
        hasStarted = false
        currentUrl = null
        _state.value = State.IDLE
    }

    private fun build(
        ua: String = HttpClient.DEFAULT_USER_AGENT,
        headers: Map<String, String> = emptyMap(),
    ): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(streamingHttp.client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 1_000, 2_000)
            .build()
        return ExoPlayer.Builder(context)
            // Shares the decode-path config of the real engines ([ownTVRenderers]) instead of a stock
            // factory: the hero preview draws the same UHD-HEVC trailers the player does, on the same
            // VPU. forceStereo is belt-and-braces — the audio track is disabled below, so no sink is
            // built at all, but nothing here may ever claim the HDMI passthrough output.
            // softwareFirst follows the same setting the other engines read: a user who turned hardware
            // decoding off did so to work around a broken vendor decoder, and the home screen was the one
            // place still handing that decoder a 4K stream.
            .setRenderersFactory(ownTVRenderers(context, forceStereo = true, softwareFirst = !hwDecodingEnabled))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(OWNTV_MEDIA_AUDIO, false)
            .build()
            .apply {
                // The preview is silent by definition. `volume = 0f` still decoded the audio track and
                // opened a real AudioTrack — on a surround setup that could be an encoded passthrough
                // sink, held by a muted home-screen preview while the actual player wanted it.
                // Disabling the track type outright costs no decoder, no sink, and no contention.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                volume = 0f
                addListener(listener)
            }
    }

    companion object {
        private const val TAG = "HeroPreviewEngine"

        /** No first frame within this window → give up and leave the poster showing. Armed at load;
         *  shared with [ExoSubtitleEngine]'s budget — see [NoFrameWatchdog] for the reasoning. */
        private const val NO_FRAME_TIMEOUT_MS = 12_000L
    }
}
