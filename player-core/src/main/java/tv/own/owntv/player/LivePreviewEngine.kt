package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import tv.own.owntv.core.player.PlayerBudget
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.settings.LiveBuffer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.network.HttpClient
import java.util.Locale
import tv.own.owntv.core.drm.toMediaDrmConfiguration
import tv.own.owntv.core.network.StreamHeaders

/**
 * ExoPlayer (Media3) that drives the muted **in-pane Live preview**. ExoPlayer starts HLS far faster than
 * mpv (which full-probes ~5 s before the first frame), so scrolling the channel list feels responsive.
 *
 * The **full** player stays on mpv (4K/HDR direct path, broad IPTV/raw-TS compatibility) — going fullscreen
 * [stop]s this engine and hands the channel to mpv. Preview and fullscreen use separate SurfaceViews on
 * separate screens, so the two decoders never share a surface. Historically one long-lived
 * instance, like [OwnTVPlayer]; it's [stop]ped (not released) whenever the preview isn't on screen.
 *
 * **Multiview needs several at once, and several is safe.** Every field of this class is
 * per-instance — the 25 `@Volatile`s included, which are marked for cross-thread visibility and not
 * for sharing — and the companion holds only constants and pure functions. What genuinely is
 * process-wide is shared on purpose and stays that way: [LiveDiagnosticsLog] (one log for the
 * device, and its `init` is idempotent), [AudioOutputPolicy]'s stereo latch (a fact about the
 * hardware, not about one stream) and the injected [PlayerDiagnostics]. [LiveEnginePool] is what
 * owns more than one of these, and what keeps exactly one of them audible.
 *
 * All calls must be on the main thread (ExoPlayer is single-threaded): the VM invokes [play]/[stop]/
 * [setMuted] from the UI thread and the Compose surface invokes [setSurface] from the holder callback.
 */
@UnstableApi
class LivePreviewEngine(
    private val context: Context,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    private val diagnostics: PlayerDiagnostics,
    settings: tv.own.owntv.core.settings.SettingsRepository,
    connectivity: tv.own.owntv.core.network.ConnectivityObserver,
    private val playbackPrefs: tv.own.owntv.core.player.PlaybackPrefsStore,
) : PlaybackEngine {

    // Escape-hatch toggle (Settings → Video player → Diagnostics). When off, no live fps/bitrate
    // measuring runs on this engine — declared values only. Never affects the playback pipeline.
    @Volatile private var measuredStatsEnabled = true
    private val settingsFlow = settings.measuredStreamStats
    private val settingsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    /** Everything this tune must forget when the next channel starts — see [TuneState]. One assignment
     *  in [play] resets the lot, which is what stops a field being left out of the reset by accident. */
    @Volatile private var tune = TuneState()
    // Live latency (#72): target live-edge offset in seconds; null = engine default (Balanced). Applied
    // as a MediaItem.LiveConfiguration on the next channel open.
    @Volatile private var liveBufferSecs: Int? = null
    // The per-playlist Live latency override the current tune was opened with; null = follow the global
    // value above. Wrapped because the override's own value may be null (Balanced) — see LiveBuffer.Override.
    @Volatile private var liveBufferOverride: LiveBuffer.Override? = null
    // "Pre-buffer" (F07): global choice, plus the per-playlist override the current tune
    // was opened with (null = follow the global one). Both are read at [build] time — the load
    // control's durations are fixed when the player is constructed.
    @Volatile private var livePrerollSecs: Int = LiveBuffer.PREROLL_OFF
    @Volatile private var prerollOverrideSecs: Int? = null

    /** The buffering numbers the live [player] instance was actually constructed with. A LoadControl can't
     *  be changed afterwards, so this is what [play] compares against to decide on a rebuild. */
    @Volatile private var builtLoadControl: LiveBuffer.LoadControlMs? = null
    /** The pre-roll this tune should use — zero for a stream already caught unable to satisfy one
     *  ([LiveStreamQuirks.defeatsPreroll]), otherwise the per-playlist override or the global setting. */
    /** The live-edge depth this tune should use: the playlist's override if it set one, else the global
     *  Live latency setting. Null either way means Balanced — engine defaults, no target offset. */
    private fun effectiveLiveBufferSecs(): Int? = liveBufferOverride?.secs ?: liveBufferSecs

    private fun effectivePrerollSecs(): Int {
        val chosen = prerollOverrideSecs ?: livePrerollSecs
        if (chosen <= 0) return chosen
        return if (currentUrl?.let { LiveStreamQuirks.defeatsPreroll(it) } == true) LiveBuffer.PREROLL_OFF else chosen
    }
    /** The pre-roll the current tune is waiting on, in seconds. A caller timing the open has to add this:
     *  a 10 s pre-buffer legitimately delays the first frame by ~10 s before anything is wrong. */
    val activePrerollSecs: Int get() = effectivePrerollSecs()
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    init { LiveDiagnosticsLog.init(context) }

    private var player: ExoPlayer? = null
    /** Media3 independently sends Surface.setFrameRate hints unless explicitly disabled. Keep that path
     *  tied to OwnTV's AFR toggle too; the default preview/in-pane state must never switch the display. */
    @Volatile private var autoFrameRateEnabled = false
    /** Per-tile video ceiling; null = no cap, which is every case but Multiview. See [setMaxVideoHeight]. */
    @Volatile private var maxVideoHeight: Int? = null
    /** Device memory budget, resolved once and reused across player rebuilds (see [build]). */
    private var playerBudget: PlayerBudget? = null
    private var surface: Surface? = null
    private var muted: Boolean = true
    // Volume-0 is NOT a reliable mute. When the TV/AVR declares AC3/E-AC3/DTS support, MediaCodecAudioRenderer
    // picks the passthrough "decoder" and the compressed 5.1 bitstream is forwarded to HDMI untouched —
    // AudioTrack.setVolume() has no effect on an IEC61937 stream, so those channels kept playing sound in a
    // "muted" preview while stereo AAC/MP3 channels muted correctly. So a muted preview also DESELECTS the
    // audio track type, which stops the renderer (and the passthrough sink) outright.
    // Exception: a stream with no video track at all (radio/audio-only) would then have nothing to render and
    // would stall the freeze watchdog — those keep the volume-0 path, which works for their PCM/stereo audio.
    private var audioTrackDisabled = false

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _videoHeight = MutableStateFlow<Int?>(null)
    val videoHeight: StateFlow<Int?> = _videoHeight.asStateFlow()
    // PAR-corrected display aspect (w/h) + native pixel (w, h), used by ExoPreviewSurface's zoom/letterbox
    // sizing (see Modifier.videoZoom). Mirrors OwnTVPlayer._videoAspect/_videoSize so live-on-ExoPlayer
    // zooms identically to live-on-mpv / VOD.
    private val _videoAspect = MutableStateFlow<Float?>(null)
    val videoAspect: StateFlow<Float?> = _videoAspect.asStateFlow()
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()
    // Up-to-4 mini stream chips for the preview pane / player top bar: aspect · resolution · fps · audio.
    private val _streamChips = MutableStateFlow<List<String>>(emptyList())
    override val streamChips: StateFlow<List<String>> = _streamChips.asStateFlow()
    // This engine IS ExoPlayer — static first chip for the fullscreen top bar.
    override val engineChip: StateFlow<String?> = MutableStateFlow("EXO")

    // --- PlaybackEngine: lets the full-screen HUD drive a promoted preview (play/pause, state, volume) ---
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<PlaybackFailure?>(null)
    override val error: StateFlow<PlaybackFailure?> = _error.asStateFlow()
    private val _errorInfo = MutableStateFlow<ErrorInfo?>(null)
    override val errorInfo: StateFlow<ErrorInfo?> = _errorInfo.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    override val videoRes: StateFlow<String?> = _videoRes.asStateFlow()
    private val _volume = MutableStateFlow(100)
    override val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    override val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    private val _audioCount = MutableStateFlow(0)
    override val audioCount: StateFlow<Int> = _audioCount.asStateFlow()
    private val _subCount = MutableStateFlow(0)
    override val subCount: StateFlow<Int> = _subCount.asStateFlow()
    // Subtitle cues + an "on" flag. The Compose surface mounts a SubtitleView ONLY while [subtitleOn] (else
    // any overlaid view knocks the SurfaceView off the hardware-overlay path and stutters 4K — same as VOD).
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> = _cues.asStateFlow()
    private val _subtitleOn = MutableStateFlow(false)
    val subtitleOn: StateFlow<Boolean> = _subtitleOn.asStateFlow()
    // True when the stream HAS audio but ExoPlayer can decode NONE of it (e.g. AC3/E-AC3/DTS on a device
    // without that decoder) — the VM hands such a stream to mpv (FFmpeg decodes everything) so it isn't silent.
    private val _audioUnsupported = MutableStateFlow(false)
    val audioUnsupported: StateFlow<Boolean> = _audioUnsupported.asStateFlow()
    // One-shot per load: audio/position is progressing normally and a video track exists, but ExoPlayer has
    // never rendered a single frame of it — the "audio plays, no picture" case the freeze/frame watchdogs
    // below can't see (they only catch a freeze AFTER frames were once seen, or a total position stall).
    // The VM observes this to try the existing mpv fallback once; legitimate audio-only streams never have
    // a video track, so they never set this.
    private val _noVideoDetected = MutableStateFlow(false)
    val noVideoDetected: StateFlow<Boolean> = _noVideoDetected.asStateFlow()
    // Set true once this tune is observed to be UHD (>1080p). Cheap panels (e.g. some Hisense) leak the 4K
    // hardware decoder if it's merely parked/reused (ExoPlayer's normal stop) instead of fully released —
    // every later channel then waits ~20 s for a decoder slot until the TV reboots. So when we LEAVE a UHD
    // channel via stop() (Back / exit fullscreen / background / leaving the list) we fully release+rebuild
    // the ExoPlayer so its MediaCodec is handed back cleanly. Deliberately NOT triggered from play(): that
    // path is also the preview-pane re-tune on every focus, and rebuilding there churns 4K previews and
    // pushes borderline streams into the mpv fallback. Scoped to UHD only — SD/HD keeps the fast reuse path.
    @Volatile private var sawUhd = false

    private val throughputTracker = ThroughputTracker()
    private val fpsSample = FpsSample()
    private var dropsBaseline = 0

    init {
        // Keep the escape-hatch flag current; turning it off stops any in-flight measuring immediately.
        settingsFlow.onEach { measuredStatsEnabled = it; if (!it) throughputTracker.setEnabled(false) }
            .launchIn(settingsScope)
        // Detailed playback logging (F18) is observed for the whole process in OwnTVApp — this engine is
        // a lazy singleton, so an observer here only started once the user opened Live TV.
        // Live latency no longer only sets a LiveConfiguration offset (which Media3 honours for HLS/DASH
        // and ignores for the raw MPEG-TS most Xtream live URLs are) — it now drives the LoadControl,
        // whose durations are fixed at construction. So a change while a channel is playing rebuilds,
        // exactly like the surround/hardware-decoding settings above. (F06)
        settings.liveBufferSeconds.onEach {
            val changed = liveBufferSecs != it
            liveBufferSecs = it
            // A playlist override outranks the global value, so a global change cannot alter what this
            // tune is using — rebuilding for it would drop the picture to arrive at the same numbers.
            if (changed && liveBufferOverride == null) currentUrl?.let { _ -> rebuildForSettingChange() }
        }.launchIn(settingsScope)
        settings.livePrerollSecs.onEach {
            val changed = livePrerollSecs != it
            livePrerollSecs = it
            if (changed) currentUrl?.let { _ -> rebuildForSettingChange() }
        }.launchIn(settingsScope)
        // Surround mode only takes effect on the next player build (the audio sink's capabilities are
        // fixed at construction), so a change while a channel is playing rebuilds it — same as mpv's
        // in-place reload. Changing the setting also clears the session latch, which is handled by
        // whoever wrote the setting; here we only need the new value and a rebuild.
        settings.surroundMode.onEach { mode ->
            val changed = surroundMode != mode
            surroundMode = mode
            if (changed) currentUrl?.let { rebuildForSettingChange() }
        }.launchIn(settingsScope)
        // "Hardware decoding = Off" used to reach mpv only, which left Live TV — whose default engine is
        // this one — on the hardware decoder the user was trying to avoid. Rebuild so the new selector
        // takes effect; the factory is fixed at construction.
        settings.hwDecoding.onEach { on ->
            val changed = hwDecodingEnabled != on
            hwDecodingEnabled = on
            if (changed) currentUrl?.let { rebuildForSettingChange() }
        }.launchIn(settingsScope)
        // "Preferred audio/subtitle language" reached mpv only (alang/slang). Live TV's default engine is
        // this one, so on every multi-language live channel the setting silently did nothing. Unlike the
        // options above these are track-selection parameters, so they apply in place — no rebuild.
        settings.preferredAudioLang.onEach { lang ->
            if (prefAudioLang != lang) { prefAudioLang = lang; applyLanguagePrefs() }
        }.launchIn(settingsScope)
        settings.preferredSubLang.onEach { lang ->
            if (prefSubLang != lang) { prefSubLang = lang; applyLanguagePrefs() }
        }.launchIn(settingsScope)
        // "Default zoom" was applied by mpv/VOD only (OwnTVPlayer), so a live channel promoted to
        // fullscreen always started at FIT however the user had set it. Applied per tune, same as there.
        settings.defaultZoom.onEach { name ->
            defaultZoom = runCatching { ZoomMode.valueOf(name) }.getOrDefault(ZoomMode.FIT)
        }.launchIn(settingsScope)
        settings.defaultVolume.onEach { defaultVolume = it }.launchIn(settingsScope)
    }

    /** Mirrors Settings → Video player → Hardware decoding. Read at [build] time. */
    @Volatile private var hwDecodingEnabled = true

    /** Settings → Video player → Preferred audio / subtitle language (ISO code, blank = no preference). */
    @Volatile private var prefAudioLang: String = ""
    @Volatile private var prefSubLang: String = ""

    /** Settings → Video player → Default zoom, applied to every new tune. */
    @Volatile private var defaultZoom: ZoomMode = ZoomMode.FIT

    /** Settings → Video player → Default volume, the level a newly tuned channel starts at. */
    @Volatile private var defaultVolume: Int = 100

    /**
     * Push the preferred-language settings into the live player's track selector.
     *
     * Safe to call with no player (the values are re-applied from [play]). A blank preference is written
     * back as "no preference" so clearing the setting takes effect immediately instead of at the next
     * tune. Media3 normalises ISO 639-2 codes itself, so "eng" here matches a track tagged `en`.
     */
    private fun applyLanguagePrefs() {
        val p = player ?: return
        runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(prefAudioLang.takeIf { it.isNotBlank() })
                .setPreferredTextLanguage(prefSubLang.takeIf { it.isNotBlank() })
                .build()
        }
    }

    /** The user's Auto / Stereo only / Surround choice. Read at [build] time. */
    @Volatile private var surroundMode: SurroundMode = SurroundMode.AUTO

    /**
     * Watches this engine's audio output for "accepted the format then played silence" and for a sink
     * that keeps underrunning. Polled from [progressWatchdog] — the tick that already runs whenever a
     * channel is up — so it adds no timer and cannot outlive the engine.
     */
    private val audioWatchdog = AudioWatchdog()
    /** rendererIndex -> (C.TrackType, ready). Written from analytics callbacks, read by [rendererReadiness]. */
    private val rendererReady = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Boolean>>()
    /** A one-line "video=false audio=true" summary of [rendererReady], for the stuck-open diagnostics. */
    private fun rendererReadiness(): String {
        if (rendererReady.isEmpty()) return "renderers=unreported"
        return rendererReady.entries.sortedBy { it.key }.joinToString(" ") { (_, v) ->
            val name = when (v.first) {
                C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"
                else -> "type${v.first}"
            }
            "$name=${v.second}"
        }
    }
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoCodecError: Exception) {
            tune.lastCodecError = codecDetail("video", videoCodecError)
        }
        override fun onAudioCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioCodecError: Exception) {
            tune.lastCodecError = codecDetail("audio", audioCodecError)
        }
        override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
            tune.lastCodecError = "audio: ${audioSinkError.message ?: audioSinkError.javaClass.simpleName}"
        }
        override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            tune.lastVideoDecoder = decoderName
            val hardware = DecoderNames.isHardware(decoderName)
            tune.lastVideoDecoderHardware = hardware
            LiveDiagnosticsLog.event(
                "video decoder: $decoderName (" +
                    (when (hardware) { true -> "hardware"; false -> "software"; null -> "kind unknown" }) +
                    ", init ${initializationDurationMs}ms)",
            )
            // The silent-fallback signature. `setEnableDecoderFallback` keeps a channel playing when the
            // vendor decoder fails, so this is a rescue working as intended — but on TV silicon software
            // decode is what a viewer reports as a blocky or stuttering picture, with no error anywhere to
            // tie it to. Recorded unconditionally (the ring buffer is exported even with detailed logging
            // off), because a support report that already names it saves a whole round of guessing.
            if (hardware == false && hwDecodingEnabled) {
                val message = "hardware decoding is ON but '$decoderName' is a software decoder — " +
                    "the renderer fell back after the hardware one failed"
                android.util.Log.w(LiveDiagnosticsLog.TAG, message)
                LiveDiagnosticsLog.event(message)
            }
            dropsBaseline = currentDroppedFrames(player) // a new decoder session may start its own counters
        }

        /** The one signal that says *which* renderer is holding a channel in BUFFERING. ExoPlayer only
         *  reaches READY once the load control is satisfied AND every enabled renderer reports ready, so
         *  when [openWatchdog] finds a full buffer and no picture, this map names the culprit — video
         *  (no usable keyframe / decoder producing nothing) versus audio (no samples at all, which is what
         *  a loader parked on a shared HLS timestamp adjuster looks like from here). */
        override fun onRendererReadyChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            rendererIndex: Int,
            rendererTrackType: Int,
            isRendererReady: Boolean,
        ) {
            rendererReady[rendererIndex] = rendererTrackType to isRendererReady
        }

        /** Per-chunk failures, which is where a provider that won't serve its own live edge shows up —
         *  [onPlayerError] only sees the aggregate once Media3 has given up on the chunk. */
        override fun onLoadError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            val code = httpStatusOf(error)
            LiveDiagnosticsLog.event(
                "load_error status=${code ?: -1} type=${error.javaClass.simpleName} " +
                    "dataType=${mediaLoadData.dataType} canceled=$wasCanceled " +
                    "uri=${HttpClient.redactUrl(loadEventInfo.uri.toString())}",
            )
            if (code == null || wasCanceled) return
            // Checked before the segment filter: the session limit is refused at the *manifest*, because
            // the panel never lets the request through to a stream at all.
            if (LiveStreamQuirks.isSessionLimit(code)) { noteSessionLimit(loadEventInfo.uri.toString()); return }
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return // a bad manifest is a different failure
            if (!activeIsHls || !LiveStreamQuirks.isEdgeRefusal(code)) return
            noteSegmentRefusal(loadEventInfo.uri.toString(), code)
        }
    }

    /** The HTTP status behind a load failure, following the cause chain Media3 wraps it in. */
    /** The HTTP status behind a load failure — shared with the other ExoPlayer engines. */
    private fun httpStatusOf(error: Throwable?): Int? = PlayerErrors.httpStatusOf(error)

    /** Semantic media details for the playback error renderer. */
    private fun exoSpec(): MediaSpec? {
        val f = player?.videoFormat
        val codec = f?.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) }
        val resolution = if (f != null && f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null
        val decoder = tune.lastVideoDecoder?.let { DecoderSpec.Named(it, hardware = tune.lastVideoDecoderHardware == true) }
            ?: if (f != null) DecoderSpec.Hardware() else null
        return MediaSpec(codec = codec, resolution = resolution, decoder = decoder)
            .takeIf { it.codec != null || it.resolution != null || it.decoder != null }
    }
    private fun mimeName(m: String) = when (m.lowercase()) {
        "hevc" -> "HEVC"; "avc" -> "H.264"; "av01" -> "AV1"; "x-vnd.on2.vp9", "vp9" -> "VP9"
        "mp4v-es" -> "MPEG-4"; "mpeg2" -> "MPEG-2"; else -> m.uppercase()
    }
    private fun codecDetail(kind: String, e: Exception): String {
        (e as? android.media.MediaCodec.CodecException)?.let { return "$kind codec: ${it.diagnosticInfo}" }
        return "$kind codec: ${e.message ?: e.javaClass.simpleName}"
    }

    private var activeIsHls = false
    /** Actual media-source route for the current load, including runtime `.ts` -> HLS detection. */
    val isHlsStream: Boolean get() = activeIsHls
    /** Distinct live segments this load has been refused — the evidence behind [segmentsRefused]. */
    private val refusedSegments = mutableSetOf<String>()
    private val _segmentsRefused = MutableStateFlow(false)
    /** The provider refuses its own signed segment URLs; ExoPlayer cannot recover, mpv can. One-shot
     *  per load, collected by the ViewModel exactly like [noVideoDetected]. */
    val segmentsRefused: StateFlow<Boolean> = _segmentsRefused
    /** Seconds asked for by the most recent `Retry-After` on this load. Captured by the diagnostic
     *  interceptor because response headers never reach [onPlayerError]. */
    @Volatile private var providerRetryAfterSecs: Int? = null
    /** How many of those waits this tune has been through. The ViewModel's open deadline restarts while
     *  this moves, so the attempt made *after* a wait gets a full deadline of its own instead of
     *  inheriting the seconds left over from the refused one. */
    val providerBackOffsSpent: Int get() = tune.providerBackOffs
    private val _providerBackOff = MutableStateFlow<ProviderBackOff?>(null)
    override val providerBackOff: StateFlow<ProviderBackOff?> = _providerBackOff.asStateFlow()
    /** The tuned channel carries a User-Agent the user configured (per-source or per-channel). An explicit
     *  setting is a decision, so it is never swapped out for the fallback identity below. */
    private var uaIsCustom = false
    /**
     * The identity the current channel was tuned with, exactly as the caller supplied it, so recovery
     * (HUD Retry, background restore) replays the same request instead of re-opening with the URL alone.
     *
     * Kept RAW on purpose. Handing the resolved [currentUa] back would read as a user-configured
     * per-channel UA ([uaIsCustom]) and silently disable the fallback-User-Agent retry rung.
     */
    /** The URL of the previous tune, so [play] can tell a genuine zap from the same channel re-opening
     *  (retry, decoder rebuild, background restore) and keep that channel's volume boost. */
    private var lastTunedUrl: String? = null
    @Volatile private var tunedUserAgent: String? = null
    @Volatile private var tunedPrerollSecs: Int? = null
    @Volatile private var tunedLiveBufferOverride: LiveBuffer.Override? = null
    @Volatile private var tunedHttpHeaders: String? = null
    /** This channel's DRM licence details, decoded once per tune (#115); null for a plain stream. */
    @Volatile private var currentDrm: tv.own.owntv.core.drm.DrmConfig? = null
    @Volatile private var tunedDrmConfig: String? = null

    /** Technical readout for the stream-info overlay, from the active ExoPlayer formats. */
    override suspend fun streamInfo(): List<StreamInfoRow> {
        val p = player ?: return emptyList()
        val out = ArrayList<StreamInfoRow>()
        out += StreamInfoRow(StreamInfoLabel.ENGINE, StreamInfoValue.Engine(StreamEngine.EXOPLAYER))
        out += StreamInfoRow(StreamInfoLabel.FORMAT, StreamInfoValue.Format(if (activeIsHls) "HLS" else "MPEG-TS"))
        p.videoFormat?.let { f ->
            out += StreamInfoRow(
                StreamInfoLabel.VIDEO,
                StreamInfoValue.Video(
                    codec = f.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) },
                    width = f.width.takeIf { it > 0 },
                    height = f.height.takeIf { it > 0 },
                    fps = displayFps(f)?.toDouble(),
                ),
            )
            when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> StreamHdrMode.HDR10_PQ
                C.COLOR_TRANSFER_HLG -> StreamHdrMode.HLG
                else -> null
            }?.let { out += StreamInfoRow(StreamInfoLabel.HDR, StreamInfoValue.Hdr(it)) }
            out += bitrateRow(f, throughputTracker)
        }
        out += StreamInfoRow(
            StreamInfoLabel.DECODER,
            // What is decoding, not what was asked for: with decoder fallback in play these can differ,
            // and the setting was the only thing this row ever reported.
            tune.lastVideoDecoder?.let { name ->
                StreamInfoValue.Decoder(
                    DecoderKind.NAMED,
                    name = name,
                    hardware = tune.lastVideoDecoderHardware == true,
                    software = tune.lastVideoDecoderHardware == false,
                )
            } ?: StreamInfoValue.Decoder(
                if (hwDecodingEnabled) DecoderKind.HARDWARE else DecoderKind.SOFTWARE,
                name = "ExoPlayer",
            ),
        )
        p.audioFormat?.let { f ->
            out += StreamInfoRow(
                StreamInfoLabel.AUDIO,
                StreamInfoValue.Audio(
                    codec = f.sampleMimeType?.substringAfterLast('/')?.uppercase(),
                    channelCount = f.channelCount.takeIf { it > 0 },
                    sampleRateHz = f.sampleRate.takeIf { it > 0 },
                ),
            )
        }
        // Which side of the HDMI cable is decoding, and whether the safety net has already fired. This is
        // the only way to verify a surround setup without a receiver that shows its own input format:
        // "passthrough" means the TV/receiver is decoding, "decoded" means OwnTV is and the sink gets PCM.
        out += StreamInfoRow(
            StreamInfoLabel.AUDIO_OUTPUT,
            StreamInfoValue.AudioOutput(
                kind = if (audioWatchdog.passthrough) AudioOutputKind.PASSTHROUGH else AudioOutputKind.DECODED_IN_APP,
                multichannelAllowed = AudioOutputPolicy.allowsMultichannel(surroundMode),
                fallbackReason = AudioOutputPolicy.latchReason,
            ),
        )
        bufferRow(p, dropsBaseline)?.let { out += it }
        // The settings that shaped the buffer above, as this player was ACTUALLY built with them — the only
        // way to tell "Live latency / Pre-buffer didn't apply" from "it applied and the buffer filled that
        // fast" without a working logcat. Worded as an amount of video, never as a wait: "start after 10s"
        // read as a ten-second countdown and made a working setting look broken.
        builtLoadControl?.let { lc ->
            val preroll = effectivePrerollSecs()
            out += StreamInfoRow(
                StreamInfoLabel.LIVE_BUFFER,
                StreamInfoValue.LiveBuffer(
                    prerollEnabled = preroll > 0,
                    prerollSeconds = preroll.toDouble(),
                    depthSeconds = lc.minBufferMs / 1000.0,
                    playlistOverride = prerollOverrideSecs != null,
                ),
            )
        }
        currentUrl?.let { out += StreamInfoRow(StreamInfoLabel.SOURCE, StreamInfoValue.Source(HttpClient.redactUrl(it))) }
        return out
    }
    /** Recompute the preview's mini chips (aspect · resolution · fps · audio · bitrate) from the active
     *  formats. Bitrate is the declared [Format.bitrate] only — measuring live throughput on every
     *  preview stream drags 4K playback, so the chip stays blank for raw MPEG-TS (the debug overlay
     *  still shows a measured value when opened). */
    private fun updateStreamChips() {
        val p = player ?: run { _streamChips.value = emptyList(); _videoFps.value = null; return }
        val chips = ArrayList<String>(5)
        p.videoFormat?.let { f ->
            if (f.width > 0 && f.height > 0) aspectLabel(f.width, f.height)?.let { chips += it }
            qualityLabel(f.width, f.height)?.let { chips += it }
            displayFps(f)?.let { chips += "${Math.round(it)} FPS" }
            f.bitrate.takeIf { it > 0 }?.let { chips += "%.1f Mbps".format(Locale.ROOT, it / 1_000_000.0) }
        }
        p.audioFormat?.let { f ->
            (when (f.channelCount) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> null })?.let { chips += it }
        }
        _streamChips.value = chips
        // Publish the frame rate for the auto-frame-rate switcher too (mpv's OwnTVPlayer has its own
        // videoFps flow; this is the ExoPlayer live equivalent). Declared Format.frameRate when the
        // stream carries one, otherwise the measured sample.
        _videoFps.value = p.videoFormat?.let { displayFps(it) }?.takeIf { it > 0f }
    }

    private val _videoFps = MutableStateFlow<Float?>(null)
    /** Video frame rate of the current live stream, or null while unknown. */
    val videoFps: StateFlow<Float?> = _videoFps.asStateFlow()

    private fun displayFps(f: Format) = f.frameRate.takeIf { it > 0 } ?: fpsSample.lastFps

    override fun refreshStreamChips() = ensureFpsMeasurement()
    override fun setBitrateTrackingEnabled(enabled: Boolean) = throughputTracker.setEnabled(enabled && measuredStatsEnabled)

    private fun ensureFpsMeasurement() {
        // F14: "Measured stream stats" is presented as a *performance* escape hatch, but Auto frame rate
        // depends on this measurement — raw MPEG-TS almost never declares Format.frameRate, so with the
        // stats toggle off `videoFps` stayed null and AFR silently did nothing while appearing enabled.
        // The measurement is cheap and bounded (FPS_MAX_ATTEMPTS samples, then it stops), so AFR keeps it
        // alive on its own; only the continuous throughput/bitrate tracking follows the stats toggle.
        if (!measuredStatsEnabled && !autoFrameRateEnabled) return
        if ((player?.videoFormat?.frameRate ?: 0f) <= 0f) restartFpsMeasurement()
    }

    private fun aspectLabel(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val r = w.toFloat() / h
        return when {
            r in 1.72f..1.82f -> "16:9"
            r in 1.28f..1.40f -> "4:3"
            r >= 2.15f -> "21:9"
            r in 1.55f..1.66f -> "16:10"
            else -> "%.2f:1".format(Locale.ROOT, r)
        }
    }
    private fun qualityLabel(w: Int, h: Int): String? = classifyResolution(w, h)

    private val _currentMeta = MutableStateFlow(MediaMeta())
    override val currentMeta: StateFlow<MediaMeta> = _currentMeta.asStateFlow()
    override val isLiveContent: Boolean = true

    /** URL the preview is currently on (null when stopped) — lets the VM skip a redundant reload. */
    var currentUrl: String? = null
        private set

    /**
     * Reconnect URL provider — set ONLY when the current item's URL is short-lived and must be
     * re-minted on reconnect (Stalker live, plan §5.4.1). Before a reconnect/HUD-retry reload, the
     * engine awaits this; a null provider or null result → replay [currentUrl] as-is (M3U/Xtream,
     * whose URLs are stable). Installed/cleared by `LiveViewModel` on each tune.
     */
    @Volatile var reconnectUrlProvider: tv.own.owntv.core.stalker.ReconnectUrlProvider? = null


    // Live auto-reconnect: a channel that DID play and then errors/stalls (provider hiccup / Wi-Fi blip)
    // re-fetches from the live edge instead of dead-ending. A channel that NEVER opened keeps the old
    // ERROR (so the VM falls back to mpv). tune.retryCount resets whenever playback goes healthy again.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioOnlyConfirmation = Runnable {
        if (currentUrl != null && tune.hasAudioTrack && !tune.hasVideoTrack && !_audioOnly.value &&
            player?.playbackState == Player.STATE_READY
        ) {
            _audioOnlyMedia.value = true
        }
    }
    /** Scope for the reconnect URL-provider (awaiting its suspend freshUrl() off-main, then reloading on main). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stallWatchdog = Runnable { reconnect("buffering stalled") }
    // A STATE_READY on its own is not recovery — a feed that flaps READY→stall→READY every few seconds
    // used to zero tune.retryCount on each blip, so the ladder never advanced and never gave up. The count is
    // only cleared once playback has held for [HEALTHY_MS]; any reconnect cancels this.
    private val healthyReset = Runnable {
        if (tune.retryCount > 0) LiveDiagnosticsLog.event("playback healthy for ${HEALTHY_MS}ms — reconnect ladder reset")
        tune.retryCount = 0
    }

    // Auto-resume after the ladder is spent. The ladder covers ~2 minutes of blind retrying, which is as
    // far as guessing usefully goes — a longer ladder would only make a genuinely dead provider take
    // longer to report. Past that we stop guessing and wait to be told: when the network comes back,
    // resume the channel we were parked on. An outage of any length then recovers by itself, while a
    // provider outage (network never dropped, so nothing fires here) still surfaces its error.
    init {
        connectivity.isOnline
            .onEach { online -> if (online) onNetworkRestored() }
            .launchIn(settingsScope)
    }

    /**
     * The network came back. Only act when a live channel is sitting on the terminal "Lost connection"
     * state — anything else is either already playing, already recovering, or was stopped on purpose,
     * and must not be restarted behind the user's back.
     */
    private fun onNetworkRestored() {
        if (!tune.gaveUp || currentUrl == null || !tune.hasPlayed || tune.stoppingIntentionally) return
        LiveDiagnosticsLog.event("network restored — resuming the channel the ladder gave up on")
        tune.gaveUp = false
        tune.retryCount = 0
        _error.value = null; _errorInfo.value = null
        _state.value = State.LOADING; _buffering.value = true
        reconnect("network restored")
    }

    // Silent-freeze watchdog. A live HLS feed can keep ExoPlayer in STATE_READY with the playback CLOCK
    // still advancing — no buffering event, no onPlayerError — while the video renderer has stopped
    // producing frames (a provider encoder/codec hiccup, a stale/empty segment, a mid-stream codec change).
    // That looks exactly like a frozen channel with "nothing happening", and a position-only watchdog misses
    // it because currentPosition keeps marching with the timeline. So we also tick a counter on every frame
    // actually rendered to the surface (VideoFrameMetadataListener, wired in build()); if frames stop while
    // ExoPlayer insists it's playing, the feed is dead → reconnect. Position-stall (a fully dead feed where
    // even the clock stopped) is kept as a second trigger; audio-only channels have no video frames, so they
    // rely on that position trigger alone.
    private val frameCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var videoRenderer: Renderer? = null
    // The first rendered frame of a load is also the answer to "how long did this tune take?", so it is
    // logged with the elapsed time since play(). It is the number the Happy Eyeballs measurement reads.
    private val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
        frameCounter.incrementAndGet()
        // Latched on the tune, not on the counter: the counter is reset again by the surface-reset and
        // decoder-rebuild paths within one tune, which logged the same tune twice.
        if (!tune.firstFrameLogged && tune.playStartedMs > 0L) {
            tune.firstFrameLogged = true
            LiveDiagnosticsLog.event("first frame after ${android.os.SystemClock.elapsedRealtime() - tune.playStartedMs}ms")
        }
    }
    private var fpsAttempts = 0
    private val fpsFastRefresh: Runnable = Runnable {
        val fresh = player?.let { fpsSample.peek(it) }
        fpsAttempts++
        // Retry until a reading matches a standard rate, capped so a genuinely unusual one doesn't retry forever.
        val done = fpsAttempts >= FPS_MAX_ATTEMPTS || (fpsAttempts >= 2 && fpsSample.confident)
        if (done) {
            fresh?.let { fpsSample.publish(it) }
            updateStreamChips()
        } else {
            mainHandler.postDelayed(fpsFastRefresh, FPS_TICK_MS)
        }
    }
    private fun restartFpsMeasurement() {
        mainHandler.removeCallbacks(fpsFastRefresh)
        fpsSample.resetWindow() // keeps the old reading visible until replaced
        fpsAttempts = 0
        mainHandler.postDelayed(fpsFastRefresh, FPS_BASELINE_MS)
    }
    private val progressWatchdog = object : Runnable {
        override fun run() {
            val p = player
            // Gate on INTENT to play (playWhenReady && STATE_READY), NOT isPlaying. isPlaying drops to false
            // during transient playback suppression and brief internal stalls WITHOUT entering STATE_BUFFERING
            // or STATE_ERROR — and the old gate then reset the freeze counter every poll, so a real frozen-
            // but-"ready" channel was never caught (no spinner / reconnect / error). playWhenReady stays true
            // through those flickers, which is exactly the "should be advancing but isn't" condition we want.
            if (p != null && tune.hasPlayed && p.playWhenReady && p.playbackState == Player.STATE_READY) {
                val now = android.os.SystemClock.elapsedRealtime()
                val frames = frameCounter.get()
                val hasVideo = p.videoFormat != null
                if (frames > 0) tune.everRendered = true
                val pos = p.currentPosition
                val posAdvanced = pos > 0 && pos != tune.lastProgressPos
                if (posAdvanced) { tune.lastProgressPos = pos; tune.lastProgressWallMs = now }
                else if (tune.lastProgressWallMs == 0L) tune.lastProgressWallMs = now // seed on the first ready poll
                if (LiveDiagnosticsLog.enabled) {
                    val liveOffset = p.currentLiveOffset.takeUnless { it == C.TIME_UNSET }
                    val dropped = (currentDroppedFrames(p) - dropsBaseline).coerceAtLeast(0)
                    LiveDiagnosticsLog.event(
                        "health posMs=$pos bufferMs=${p.totalBufferedDuration} liveOffsetMs=${liveOffset ?: -1} " +
                            "frames=$frames dropped=$dropped isPlaying=${p.isPlaying}",
                    )
                }
                // Audio output health. Runs in EVERY surround mode including "Surround" — a user who asked
                // for 5.1 did not ask for silence — and cannot be turned off. On a hit the session latches
                // to stereo (which every engine reads) and this channel is rebuilt on a stereo-only sink.
                audioWatchdog.poll(p.isPlaying)?.let { reason ->
                    LiveDiagnosticsLog.event("audioWatchdog: $reason — forcing stereo for this session")
                    AudioOutputPolicy.latchStereo("exo/live: $reason")
                    PlaybackErrorLog.event(context, "ExoPlayer", live = true, reason = PlayerFailureReason.STEREO_FALLBACK, detail = reason)
                    onAudioFallback?.invoke()
                    rebuildForSettingChange()
                    return
                }
                // Audio-plays-no-video: a video track exists but has never rendered a single frame, even
                // though we're not in the total-freeze case above (position/audio clock IS advancing). Only
                // fires once per load so the VM's one-shot mpv fallback isn't retriggered after it acts.
                if (!_audioOnly.value && !tune.noVideoTriggered && hasVideo && !tune.everRendered && now - tune.readySinceMs >= NO_VIDEO_TIMEOUT_MS) {
                    tune.noVideoTriggered = true
                    LiveDiagnosticsLog.event("progressWatchdog: no video frame after ${now - tune.readySinceMs}ms (pos=$pos advancing, video track present)")
                    _noVideoDetected.value = true
                }
                // Backstop: zero forward progress for the whole window while we intend to play == a dead feed.
                // Wall-clock based, so it CAN'T be missed by isPlaying flicker or a non-functional frame hook.
                val noProgressMs = now - tune.lastProgressWallMs
                if (noProgressMs >= FREEZE_TIMEOUT_MS) {
                    LiveDiagnosticsLog.event("progressWatchdog: no-progress detected for ${noProgressMs}ms (pos=$pos, state=READY, frameHook=${tune.everRendered})")
                    tune.frozenChecks = 0
                    reconnect("stream frozen — no progress ${noProgressMs}ms"); return
                }
                // Picture frozen but the live clock still advances (position moving) — only the rendered-frame
                // count can see this. Guarded by tune.everRendered so a non-functional frame hook can't false-fire.
                // In Audio Mode the surface is intentionally detached, so no frames render and the count
                // sits still — that's expected, not a frozen picture. Skip the frame-based freeze check;
                // the position/no-progress backstop above still catches a genuinely dead feed.
                val framesStuck = !_audioOnly.value && tune.everRendered && hasVideo && frames == tune.lastFrameCount
                tune.lastFrameCount = frames
                if (framesStuck) {
                    if (++tune.frozenChecks >= FROZEN_LIMIT) {
                        LiveDiagnosticsLog.event("progressWatchdog: picture frozen, frames stuck at $frames for ${tune.frozenChecks} polls (pos still advancing)")
                        tune.frozenChecks = 0
                        reconnect("picture frozen"); return
                    }
                } else {
                    tune.frozenChecks = 0
                }
            } else {
                tune.frozenChecks = 0; tune.lastProgressPos = -1L; tune.lastProgressWallMs = 0L
            }
            mainHandler.postDelayed(this, PROGRESS_CHECK_MS)
        }
    }

    private var openStartMs = 0L
    private var openStuckPolls = 0
    private var prerollBufferedMs = 0L
    private var prerollStuckPolls = 0

    /**
     * Watch the *first* open of a live channel and separate the two ways it can fail to start, which look
     * identical from outside (a spinner that never clears) and which nothing else here can see — every
     * other watchdog arms at the first frame, which is exactly what never arrives.
     *
     * **1. The buffer is full and playback still won't start.** `DefaultLoadControl` has released it and
     * the load control is satisfied, so what is missing is a *renderer*: one of them has no sample it can
     * play at the current position. That is a broken stream, not a slow one — a mux whose audio and video
     * timestamps disagree, or an HLS rendition whose loading thread is blocked waiting for a timestamp
     * adjuster that never initialises (Media3 waits for that one forever by default). Traced on a 4K
     * channel that reported *twenty* seconds buffered and never produced a frame, whose `.ts` variant plays
     * — with audible A/V sync drift, the same defect from the other side. Nothing in ExoPlayer will rescue
     * it, so fail the load at once and let the ViewModel retry it in TS / hand it to mpv, instead of
     * holding a dead spinner until its open timeout.
     *
     * **2. The pre-roll can't be reached.** "Pre-buffer = 10s of video" is a threshold on the buffer, not a
     * wait, and a live stream can only be loaded as far ahead as its provider publishes. If the buffer has
     * stopped growing *short of* the threshold there is nothing left to wait for: drop the pre-roll for
     * that one stream and reopen it. A healthy stream keeps filling (at the live edge, roughly a second of
     * media per second) and is left alone. [PREROLL_OPEN_GRACE_MS] past the requested amount is the
     * backstop for one that dribbles rather than stalls outright.
     */
    private val openWatchdog = object : Runnable {
        override fun run() {
            val p = player
            val url = currentUrl
            if (p == null || url == null || tune.hasPlayed || tune.gaveUp || tune.prerollRetunePending) return
            val buffered = p.totalBufferedDuration
            val startMs = builtLoadControl?.bufferForPlaybackMs?.toLong() ?: LiveBuffer.DEFAULT_START_MS.toLong()
            val waitedMs = android.os.SystemClock.elapsedRealtime() - openStartMs
            // (1) enough buffered to start, and it isn't starting.
            if (buffered >= startMs && buffered > 0L) {
                if (++openStuckPolls >= OPEN_STUCK_POLLS) {
                    logHlsPlaylist("open stalled")
                    val detail = "${buffered}ms buffered (needs ${startMs}ms) after ${waitedMs}ms, no frame, " +
                        rendererReadiness()
                    LiveDiagnosticsLog.event("open stalled: $detail — the stream is buffered but unplayable here")
                    android.util.Log.i(
                        LiveDiagnosticsLog.TAG,
                        "open stalled — $detail" + (hlsShape()?.let { ", $it" } ?: ""),
                    )
                    failLoad("buffered but never started playing ($detail)")
                    return
                }
            } else {
                openStuckPolls = 0
            }
            // (2) a pre-roll the stream can't fill.
            val targetMs = effectivePrerollSecs() * 1000L
            if (targetMs > 0L && buffered < targetMs) {
                val grew = buffered - prerollBufferedMs
                if (grew >= PREROLL_MIN_GROWTH_MS) prerollStuckPolls = 0 else prerollStuckPolls++
                val stuck = prerollStuckPolls >= PREROLL_STUCK_POLLS
                val tooLong = waitedMs >= targetMs + PREROLL_OPEN_GRACE_MS
                if (stuck || tooLong) {
                    val why = if (stuck) "buffer stopped growing at ${buffered}ms" else "still ${buffered}ms after ${waitedMs}ms"
                    LiveDiagnosticsLog.event("pre-buffer unreachable ($why of ${targetMs}ms) — reopening this stream without one")
                    android.util.Log.i(LiveDiagnosticsLog.TAG, "preroll defeated — $why of ${targetMs}ms, reopening without pre-buffer")
                    dropPrerollAndReopen(url)
                    return
                }
            } else {
                prerollStuckPolls = 0
            }
            prerollBufferedMs = buffered
            mainHandler.postDelayed(this, PREROLL_POLL_MS)
        }
    }

    /** Start the open deadline for the load that was just prepared. Every attempt gets its own — a retry
     *  after a provider wait is as entitled to the "buffered but never started" check as the first try. */
    private fun armOpenWatchdog() {
        openStartMs = android.os.SystemClock.elapsedRealtime()
        openStuckPolls = 0; prerollBufferedMs = 0L; prerollStuckPolls = 0
        rendererReady.clear()
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.postDelayed(openWatchdog, PREROLL_POLL_MS)
    }

    /**
     * The one way this engine puts a URL into the live player — the first tune and every retry alike.
     *
     * There were eight copies of these four lines, and [armOpenWatchdog] was the line that kept getting
     * left off a new retry path: v4.2.1 had to add it back to four of them by hand. Routing every load
     * through here makes the watchdog's own promise — *every* attempt gets an open deadline — true by
     * construction rather than by review.
     *
     * Always via [mediaSourceFor], never `setMediaItem`: a bare MediaItem drops the TS caption-descriptor
     * override (#57 CC1) and the live target offset, so a channel silently lost its captions on reload.
     *
     * Callers keep their own `runCatching`, because what a failure *means* differs per path (a refused
     * retry is a channel error, a failed reconnect is a lost connection).
     */
    private fun reprepare(p: ExoPlayer, url: String) {
        p.setMediaSource(mediaSourceFor(url))
        p.prepare()
        p.playWhenReady = true
        armOpenWatchdog()
    }

    /** Remember that [url] can't hold a pre-roll and reopen it without one. Posted rather than run inline:
     *  the reopen releases the player, and a caller may be inside that player's own listener callback. */
    private fun dropPrerollAndReopen(url: String) {
        LiveStreamQuirks.rememberPrerollDefeated(url)
        tune.prerollRetunePending = true
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.post { if (currentUrl == url) rebuildForSettingChange() else tune.prerollRetunePending = false }
    }

    /**
     * Catch a stream that oscillates `READY ↔ BUFFERING` several times a second instead of playing.
     *
     * Traced on a 4K live channel with "Pre-buffer = 10s of video": `DefaultLoadControl` starts and
     * resumes playback on **either** threshold — the requested media duration *or* the byte cap — and on
     * a feed that dense the byte cap is reached at well under 10 s of media, so the time threshold can
     * never be met. Playback starts on the byte rule, plays a few frames, falls back under the cap and
     * re-buffers at once: measured at ~8 transitions a second, indefinitely. To the user that is a frozen
     * picture behind a flickering spinner.
     *
     * Nothing else in this class could see it. [stallWatchdog] is cancelled by every `STATE_READY`, and
     * [progressWatchdog] only samples while `STATE_READY` is current and resets its counters whenever a
     * poll misses one — so a flap this fast resets both faster than either can arm.
     *
     * The response is graded: first drop the pre-roll for this stream and reopen it (which is the actual
     * cause, and keeps the channel on ExoPlayer with its captions and audio tracks intact); if it still
     * flaps with no pre-roll, the stream simply cannot sustain playback here, so fail the load and let
     * the ViewModel's ladder retry it in TS / hand it to mpv.
     */
    private fun noteRebufferFlap() {
        if (tune.prerollRetunePending) return
        val now = android.os.SystemClock.elapsedRealtime()
        val pos = player?.currentPosition ?: 0L
        if (now - tune.flapWindowStartMs > FLAP_WINDOW_MS) { tune.flapWindowStartMs = now; tune.flapCount = 0; tune.flapWindowStartPos = pos }
        if (++tune.flapCount < FLAP_LIMIT) return
        // A channel that re-buffers often but still moves forward is merely choppy — that's the reconnect
        // ladder's business, not this. Only "many re-buffers, almost no playback" counts as a flap.
        val advancedMs = pos - tune.flapWindowStartPos
        if (advancedMs >= FLAP_MIN_PROGRESS_MS) { tune.flapWindowStartMs = now; tune.flapCount = 0; tune.flapWindowStartPos = pos; return }
        val url = currentUrl
        val detail = "${tune.flapCount} re-buffers in ${now - tune.flapWindowStartMs}ms, position advanced ${advancedMs}ms"
        tune.flapWindowStartMs = now; tune.flapCount = 0; tune.flapWindowStartPos = pos
        if (url != null && effectivePrerollSecs() > 0) {
            LiveDiagnosticsLog.event("re-buffer flap ($detail) — this stream can't reach its pre-buffer, reopening without one")
            android.util.Log.i(LiveDiagnosticsLog.TAG, "preroll defeated — reopening without pre-buffer ($detail)")
            dropPrerollAndReopen(url)
            return
        }
        LiveDiagnosticsLog.event("re-buffer flap ($detail) with no pre-buffer — giving up on ExoPlayer for this stream")
        failLoad("playback kept re-buffering ($detail)")
    }

    /** The fallback ladder has given up on this channel — there is no rung left, or the whole tune ran
     *  out of time. Same terminal failure as the engine's own, so the HUD shows an error instead of a
     *  spinner that will never clear. */
    fun abandon(reason: String) = failLoad(reason)

    /** Terminal failure of the current load that is NOT worth another reconnect: stand the watchdogs down
     *  and surface an error so the ViewModel can retry elsewhere (TS variant / mpv) immediately. */
    private fun failLoad(reason: String) {
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(progressWatchdog)
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        tune.gaveUp = true
        _isPlaying.value = false; _buffering.value = false
        _error.value = PlayerErrors.visibleFailure(reason, currentUrl, PlaybackFailure.Channel)
        _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(reason), exoSpec(), reason)
        _state.value = State.ERROR
    }

    /** One diagnostic line per ExoPlayer state transition — never includes the stream URL. */
    private fun logStateChange(playbackState: Int) {
        val name = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "UNKNOWN($playbackState)"
        }
        val p = player
        LiveDiagnosticsLog.event(
            "state_changed state=$name playWhenReady=${p?.playWhenReady} isPlaying=${p?.isPlaying} " +
                "pos=${p?.currentPosition} buffered=${p?.bufferedPosition} hasPlayed=${tune.hasPlayed} " +
                "isLiveContent=$isLiveContent buffering=${_buffering.value} reconnect=${tune.retryCount}/$MAX_RECONNECTS"
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            logStateChange(playbackState)
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = State.LOADING; _buffering.value = true
                    // After it has played, a long buffer == a dropped feed → reconnect (live streams don't
                    // resume on their own here). Before first play, leave initial load alone.
                    if (tune.hasPlayed && !tune.gaveUp) {
                        LiveDiagnosticsLog.event("stallWatchdog armed (${STALL_MS}ms)")
                        mainHandler.removeCallbacks(stallWatchdog); mainHandler.postDelayed(stallWatchdog, STALL_MS)
                        // …and the watchdog above can only fire if this state LASTS. A stream that bounces
                        // straight back to READY re-arms it forever instead — see [noteRebufferFlap].
                        if (!tune.reconnectPending) noteRebufferFlap()
                    }
                }
                Player.STATE_READY -> {
                    val resumed = tune.hasPlayed // a READY after first play == recovered from a buffer/stall
                _state.value = State.PLAYING; _buffering.value = false
                tune.hasPlayed = true; mainHandler.removeCallbacks(stallWatchdog)
                updateAudioOnlyClassification()
                if (activeIsHls && !tune.playlistLogged) { tune.playlistLogged = true; logHlsPlaylist("ready") }
                    // Recovery is measured, not assumed: arm the ladder reset and let it fire only if this
                    // READY actually holds (see [healthyReset]).
                    mainHandler.removeCallbacks(healthyReset); mainHandler.postDelayed(healthyReset, HEALTHY_MS)
                    if (resumed) LiveDiagnosticsLog.event("playing — READY, spinner cleared, stallWatchdog cancelled")
                    // (re)start the silent-freeze poll now that we're actually playing. Reset the frame
                    // baseline so the freeze window is measured from this READY (a healthy stream renders its
                    // first frame well within the grace window; one that never does trips the watchdog).
                    frameCounter.set(0); tune.lastFrameCount = 0; tune.everRendered = false; tune.lastProgressPos = -1L; tune.lastProgressWallMs = 0L; tune.frozenChecks = 0
                    tune.readySinceMs = android.os.SystemClock.elapsedRealtime(); tune.noVideoTriggered = false
                    mainHandler.removeCallbacks(progressWatchdog); mainHandler.postDelayed(progressWatchdog, PROGRESS_CHECK_MS)
                    mainHandler.removeCallbacks(openWatchdog) // it opened — the pre-roll was satisfiable
                    ensureFpsMeasurement()
                }
                Player.STATE_ENDED -> {
                    // A live HLS feed shouldn't legitimately "end" — this is a stall/hiccup (e.g. a stray
                    // EXT-X-ENDLIST from a provider glitch, or a momentarily empty playlist), not a real
                    // terminal state. Only react once we've actually been playing; before that, leave it
                    // alone (mirrors the pre-fix behavior so a channel that never opens still falls through
                    // to onPlayerError / the VM's mpv fallback instead of looping reconnects forever).
                    mainHandler.removeCallbacks(stallWatchdog)
                    when {
                        !tune.hasPlayed -> {
                            LiveDiagnosticsLog.event("STATE_ENDED before first play — no action")
                            _buffering.value = false
                        }
                        tune.reconnectPending || tune.gaveUp -> _buffering.value = true
                        else -> {
                            LiveDiagnosticsLog.event("STATE_ENDED mid-live — treating as stall, reconnecting")
                            _buffering.value = true
                            reconnect("ended mid-live")
                        }
                    }
                }
                Player.STATE_IDLE -> {
                    mainHandler.removeCallbacks(stallWatchdog)
                    when {
                        tune.stoppingIntentionally -> {
                            LiveDiagnosticsLog.event("STATE_IDLE — clean cancellation (stop/release/back)")
                            tune.stoppingIntentionally = false
                            _buffering.value = false
                        }
                        // A pending provider back-off is a wait, not a stop: ExoPlayer goes IDLE the moment
                        // the 429 becomes fatal, and clearing the spinner here would leave the countdown
                        // standing on a dead-looking screen.
                        tune.reconnectPending || tune.gaveUp || _providerBackOff.value != null -> _buffering.value = true
                        tune.hasPlayed -> {
                            // Unexpected IDLE while we still intend to be on a live channel — same
                            // dead-end this fix targets, just via STATE_IDLE instead of STATE_ENDED.
                            LiveDiagnosticsLog.event("STATE_IDLE unexpected mid-live — treating as stall, reconnecting")
                            _buffering.value = true
                            reconnect("idle mid-live")
                        }
                        else -> {
                            LiveDiagnosticsLog.event("STATE_IDLE before first play — no action")
                            _buffering.value = false
                        }
                    }
                }
                else -> { _buffering.value = false; mainHandler.removeCallbacks(stallWatchdog) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                _videoHeight.value = videoSize.height
                _videoRes.value = "${videoSize.height}p"
                if (videoSize.height > 1080) sawUhd = true // mark UHD → full decoder release on leave
            }
            if (videoSize.width > 0 && videoSize.height > 0) {
                // Aspect for zoom/letterbox sizing (PAR-corrected), + native pixel size for Original (1:1).
                _videoAspect.value =
                    (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                _videoSize.value = videoSize.width to videoSize.height
            }
            updateStreamChips()
            ensureFpsMeasurement()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            rebuildTracks(tracks); updateStreamChips(); ensureFpsMeasurement()
        }
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { _cues.value = cueGroup.cues }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(LiveDiagnosticsLog.TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            LiveDiagnosticsLog.event("player_error code=${error.errorCodeName} hasPlayed=${tune.hasPlayed}")
            // mid-stream drop → reconnect, unless a reconnect from the SAME failed prepare is already
            // in flight (ExoPlayer often fires this alongside a STATE_IDLE for one physical failure) or
            // we've already exhausted retries and are waiting on the user/a fresh play().
            if (tune.hasPlayed && !tune.reconnectPending && !tune.gaveUp) {
                val hlsHttpFailure = activeIsHls && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                reconnect("error ${error.errorCodeName}", fastHlsHttpRecovery = hlsHttpFailure)
                return
            }
            if (tune.hasPlayed) return
            // Some Xtream panels advertise a `.ts` endpoint but HTTP-redirect it to an `.m3u8` manifest.
            // Content type selection happened before that redirect, so the progressive extractor sees
            // `#EXTM3U` and reports an unsupported container. Retry the SAME URL through HlsMediaSource;
            // OkHttp follows the redirect again, now with the correct manifest/segment parser.
            if (!tune.redirectedHlsRetryDone && !activeIsHls && tune.responseWasHls &&
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            ) {
                retryRedirectedStreamAsHls()
                return
            }
            // Refused only because the previous engine's session hasn't been released yet — wait it out
            // once instead of failing the channel or handing it back to mpv (see [noteSessionLimit]).
            if (tune.sessionLimitSeen && !tune.sessionLimitRetryDone) { retryAfterSessionRelease(); return }
            // Refused with a deadline rather than a verdict: the panel answered 429 and said how many
            // seconds until this channel is free again. Sit out its own countdown and ask again for the
            // identical stream (see [maybeBackOffForProvider]).
            if (maybeBackOffForProvider(error)) return
            // Refused on *who is asking* rather than on what was asked for: some panels blocklist player
            // User-Agents by name. Retry once under a neutral identity before conceding the channel to
            // mpv, which sends the very same default UA and can only reproduce this.
            if (!tune.uaRetryDone && !uaIsCustom && currentUa != HttpClient.FALLBACK_USER_AGENT &&
                httpStatusOf(error)?.let { LiveStreamQuirks.isIdentityRefusal(it) } == true
            ) {
                retryWithFallbackUserAgent()
                return
            }
            // Every hardware decoder on the device is taken (Multiview's real ceiling). A rebuild
            // cannot conjure one, and mpv would fail the same way, so this one says what it is and
            // stops. Checked before the rebuild retry, which is for a decoder that *died*.
            if (isDecoderExhausted(error)) {
                LiveDiagnosticsLog.event("decoder exhausted — no MediaCodec instance available")
                _error.value = PlaybackFailure.DecoderExhausted
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(DECODER_EXHAUSTED_REASON), exoSpec(), DECODER_EXHAUSTED_REASON)
                return
            }
            // A hardware decoder that died before the first frame is usually recoverable on a FRESH
            // MediaCodec, so rebuild and try once more before conceding the channel to mpv (see
            // [rebuildDecoderAndRetry]).
            if (!tune.decoderRetryDone && isDecoderFailure(error)) { rebuildDecoderAndRetry(error); return }
            // The endpoint we were given is the wrong shape for this channel — try its sibling before
            // conceding (see [retryAlternateFormat]).
            if (!tune.altFormatRetryDone && isFormatFailure(error)) { retryAlternateFormat(); return }
            // Never opened → a stream ExoPlayer can't handle; the VM falls back to mpv on this ERROR.
            _state.value = State.ERROR
            _isPlaying.value = false
            _buffering.value = false
            val raw = tune.lastCodecError ?: diagnostics.recentError()
                ?: error.errorCodeName + ((error.cause?.message ?: error.message)?.let { ": $it" } ?: "")
            _error.value = PlayerErrors.visibleFailure(raw, currentUrl, PlaybackFailure.Channel)
            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), raw)
        }
    }

    /** Attach the preview SurfaceView's surface, or null when it's destroyed. */
    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    /** Enable/disable Media3's own Surface.setFrameRate mechanism. This is separate from the window-level
     *  [FrameRateController], so both must follow the same user setting. */
    fun setAutoFrameRateEnabled(enabled: Boolean) {
        val turnedOn = enabled && !autoFrameRateEnabled
        autoFrameRateEnabled = enabled
        player?.setVideoChangeFrameRateStrategy(
            if (enabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
        // Switching AFR on mid-channel has to start the fps measurement it needs (F14) — otherwise it only
        // takes effect on the next tune, and only if "Measured stream stats" happens to be on.
        if (turnedOn) ensureFpsMeasurement()
    }

    /** Detach [s] only if it's still the surface in use. A surface-generation bump swaps one SurfaceView
     *  for another, and the outgoing view's `surfaceDestroyed` can land after the incoming view's
     *  `surfaceCreated` — a plain `setSurface(null)` would then throw away the good new surface. */
    fun detachSurface(s: Surface) {
        if (surface !== s) return
        setSurface(null)
    }

    /** Start (or switch to) [url] as a muted/unmuted preview. Never throws — a stream ExoPlayer can't set
     *  up just falls back to the channel logo (the full mpv player can still play it). [meta] populates the
     *  full-screen HUD title when this preview is promoted. [userAgent] is the per-source custom UA. */
    /** Bumped whenever the video output surface must be thrown away and rebuilt; [ExoPreviewSurface]
     *  keys its SurfaceView on this, so a new value means a brand-new [Surface]. */
    private val _surfaceGeneration = MutableStateFlow(0)
    val surfaceGeneration: StateFlow<Int> = _surfaceGeneration

    /** Force the preview SurfaceView to be destroyed and recreated, so the next codec is configured
     *  against a pristine native window.
     *
     *  Some hardware decoders — measured on Realtek (`OMX.realtek.video.decoder`) — can only ever run
     *  ONE 4K instance per Surface. Releasing a 4K codec leaves the native window unusable
     *  (`freeAllBuffers: N buffers were freed while being dequeued!`), and every later codec configured
     *  against it dies ~1s after start with `ERROR(0x80001000)` → `IllegalStateException` out of
     *  `native_dequeueOutputBuffer`, which the live engine reports as a decode failure and falls back to
     *  mpv. Waiting longer does not help (a failing tune had a 885ms gap, a succeeding one 857ms) and
     *  neither does a fresh ExoPlayer/codec — only a fresh Surface does. That is exactly why toggling to
     *  mpv and back "fixed" such a channel: the engine swap recreates the SurfaceView. */
    private fun recreateSurface() {
        _surfaceGeneration.value++
    }

    /** Fully release the ExoPlayer instance (and its MediaCodec) — used when leaving a UHD channel so the
     *  4K hardware decoder is handed back cleanly instead of parked/reused, and recreate the surface with
     *  it (see [recreateSurface]). The next [play] lazily rebuilds via `player ?: build()`; it may run
     *  before the replacement surface arrives, which is fine — [setSurface] attaches it a frame later. */
    fun releaseDecoderForUhd() {
        if (!sawUhd) return // only pay the rebuild when leaving a genuine UHD stream
        sawUhd = false
        if (player == null) return
        android.util.Log.i(LiveDiagnosticsLog.TAG, "releaseDecoderForUhd(): releasing the 4K decoder + surface")
        LiveDiagnosticsLog.event("UHD channel left — full decoder release+rebuild")
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        recreateSurface()
    }

    /** [prerollSecsOverride] = the tuned channel's playlist "Pre-buffer" override in
     *  seconds; null follows the global setting. */
    fun play(
        url: String,
        muted: Boolean,
        meta: MediaMeta = MediaMeta(),
        userAgent: String? = null,
        prerollSecsOverride: Int? = null,
        /** This playlist's Live latency override; null follows the global setting. */
        liveBufferOverride: LiveBuffer.Override? = null,
        /** Per-channel HTTP headers serialized as `Key: Value` per line (M3U, F16); null for none. */
        httpHeaders: String? = null,
        /** Widevine/ClearKey licence details for this channel (#115); null for an unprotected stream. */
        drmConfig: String? = null,
    ) {
        LiveDiagnosticsLog.event("play() url=${HttpClient.redactUrl(url)} muted=$muted")
        // THE reset. Everything a new channel must not inherit from the previous one lives in
        // [TuneState], so forgetting it is one assignment that cannot be partially done.
        tune = TuneState(playStartedMs = android.os.SystemClock.elapsedRealtime())
        // Read BEFORE the player is (re)built below — the load control is fixed at construction.
        prerollOverrideSecs = prerollSecsOverride
        this.liveBufferOverride = liveBufferOverride
        // Remember the request identity for the recovery paths (see [tunedUserAgent]).
        tunedUserAgent = userAgent; tunedPrerollSecs = prerollSecsOverride; tunedHttpHeaders = httpHeaders
        tunedLiveBufferOverride = liveBufferOverride
        tunedDrmConfig = drmConfig
        currentDrm = tv.own.owntv.core.drm.DrmConfig.decode(drmConfig)
        currentHeaders = StreamHeaders.decode(httpHeaders)
        // A channel's own User-Agent is more specific than the playlist-wide one, so it wins (F16).
        val configuredUa = StreamHeaders.userAgentOf(currentHeaders) ?: userAgent?.takeIf { it.isNotBlank() }
        uaIsCustom = configuredUa != null
        // A panel already caught refusing the default identity starts on the fallback one, so only the
        // channel that discovered the block ever pays for the retry (see [LiveStreamQuirks], quirk 3).
        currentUa = configuredUa
            ?: HttpClient.FALLBACK_USER_AGENT.takeIf { LiveStreamQuirks.blocksDefaultUserAgent(url) }
            ?: HttpClient.DEFAULT_USER_AGENT
        diagnostics.start(); diagnostics.markLoad()
        this.muted = muted
        currentUrl = url
        refusedSegments.clear(); _segmentsRefused.value = false
        // A new tune supersedes any wait the previous channel was serving (the user zapped away).
        cancelProviderBackOff()
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        mainHandler.removeCallbacks(audioOnlyConfirmation)
        _audioCount.value = 0
        _subCount.value = 0
        _subtitleOn.value = false; _cues.value = emptyList(); _audioUnsupported.value = false
        _noVideoDetected.value = false
        _audioOnlyMedia.value = false // re-decided from this stream's own track list
        _videoHeight.value = null; _videoAspect.value = null; _videoSize.value = null; _streamChips.value = emptyList(); _videoFps.value = null
        _videoRes.value = null
        _error.value = null
        _errorInfo.value = null
        frameCounter.set(0)
        throughputTracker.reset(); fpsSample.resetAll(); dropsBaseline = currentDroppedFrames(player)
        audioWatchdog.reset()
        _currentMeta.value = meta
        _zoomMode.value = defaultZoom // start new content at the user's default zoom, same as mpv/VOD
        // A different channel starts at normal volume. Re-opening the SAME one does not: a retry, a
        // decoder rebuild or a screensaver restore is the same channel continuing, and dropping the
        // boost there made a quiet channel go quiet again every time the stream hiccuped.
        val sameChannelReopen = url == lastTunedUrl
        lastTunedUrl = url
        _volume.value = when {
            muted -> 0
            sameChannelReopen -> _volume.value.coerceAtLeast(defaultVolume)
            else -> defaultVolume
        }
        applyRememberedPrefs(meta.contentKey ?: url)
        _state.value = State.LOADING
        _buffering.value = true
        runCatching {
            // A LoadControl is fixed when the player is constructed, and this engine keeps ONE player alive
            // across tunes — so a setting changed while nothing was playing, or a per-playlist override that
            // differs from the last channel's, would otherwise never take effect (the "Pre-buffer
            // does nothing" report). Drop the player whenever the numbers it was built with no longer match.
            val wanted = LiveBuffer.loadControlFor(effectiveLiveBufferSecs(), effectivePrerollSecs())
            if (player != null && builtLoadControl != wanted) {
                LiveDiagnosticsLog.event("load_control stale (was=$builtLoadControl want=$wanted) — rebuilding player")
                player?.run { removeListener(listener); release() }
                player = null
                videoRenderer = null
            }
            val p = player ?: build().also { player = it }
            // May be null right after a surface-generation bump — setSurface attaches it a frame later.
            surface?.let { p.setVideoSurface(it) }
            // Assume video until the tracks arrive, so a muted preview never leaks a frame of audio while
            // the stream is still being sniffed; rebuildTracks() relaxes this for audio-only streams.
            tune.hasVideoTrack = true
            applyMute(force = true)
            applyMaxVideoHeight() // survives a player rebuild, exactly as the mute and language prefs do
            applyLanguagePrefs() // survives a player rebuild, and seeds a player built before the setting arrived
            setVideoTrackDisabled(_audioOnly.value) // survives a player rebuild while Audio Mode is on (F19c)
            // An open that buffers but never starts would otherwise hold the spinner forever — see
            // [openWatchdog]. Armed for every tune, pre-roll or not: branch (1) doesn't need one.
            reprepare(p, url)
        }.onFailure {
            android.util.Log.w(LiveDiagnosticsLog.TAG, "preview play() failed for ${HttpClient.redactUrl(url)}", it)
            LiveDiagnosticsLog.event("play() failed: ${it.message}")
            _state.value = State.ERROR
            val raw = tune.lastCodecError ?: diagnostics.recentError() ?: it.message
            _error.value = PlayerErrors.visibleFailure(raw, url, PlaybackFailure.Channel)
            _errorInfo.value = raw?.let { r -> ErrorInfo(PlayerErrors.reasonFor(r), exoSpec(), r) }
        }
    }

    /**
     * Notified when the audio watchdog has forced this session to stereo, with a message to show the
     * user. Set by whoever owns the UI; a null callback means the fallback still happens silently
     * (getting sound back matters more than announcing it).
     */
    var onAudioFallback: (() -> Unit)? = null

    /**
     * Rebuild the player so a changed audio configuration takes effect on the channel that is playing.
     *
     * A rebuild, not a reload: an ExoPlayer's audio sink capabilities are fixed when the renderers are
     * constructed, so re-preparing the same player would keep the sink that just failed. Live has no
     * position to preserve, so this is just "open the same channel again".
     */
    private fun rebuildForSettingChange() {
        val url = currentUrl ?: return
        val meta = _currentMeta.value
        val ua = currentUa
        val wasMuted = muted
        val preroll = prerollOverrideSecs
        // Re-encoded rather than kept as a map so the reopened channel goes down exactly the same path
        // as a fresh tune (including the per-channel UA precedence).
        val headers = StreamHeaders.encode(currentHeaders)
        audioWatchdog.reset()
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(progressWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        play(url, wasMuted, meta, ua, preroll, tunedLiveBufferOverride, headers, tunedDrmConfig)
    }

    /**
     * Cap the video this engine will select, or null for no cap.
     *
     * Multiview's real ceiling is the device's hardware decoders, not the provider: four 1080p tiles
     * ask more of cheap TV silicon than it has. Asking a tile for a lower HLS variant is one of only
     * two mitigations D5 leaves open (the other is failing that tile honestly), so this exists to be
     * set per tile — harder on the tiles that do not have the sound.
     *
     * Applied immediately and re-applied after every player rebuild.
     */
    fun setMaxVideoHeight(height: Int?) {
        if (maxVideoHeight == height) return
        maxVideoHeight = height
        applyMaxVideoHeight()
    }

    private fun applyMaxVideoHeight() {
        val p = player ?: return
        val height = maxVideoHeight
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .apply {
                if (height == null) clearVideoSizeConstraints() else setMaxVideoSize(Int.MAX_VALUE, height)
            }
            .build()
    }

    fun setMuted(m: Boolean) {
        muted = m
        _volume.value = if (m) 0 else 100
        applyMute()
    }

    /** Push [muted] onto the player: volume, plus the audio-track deselect that also silences a
     *  passthrough (AC3/E-AC3/DTS 5.1) bitstream. [force] re-sends the track parameters even when the
     *  desired state is unchanged — needed right after a (re)built player, whose parameters are fresh. */
    private fun applyMute(force: Boolean = false) {
        val p = player ?: return
        p.volume = if (muted) 0f else 1f
        val disable = muted && tune.hasVideoTrack
        if (!force && disable == audioTrackDisabled) return
        audioTrackDisabled = disable
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disable)
            .build()
        applyVolumeBoost(_volume.value) // re-binds the >100% effect after a player rebuild / mute change
    }

    // Snapshot of the live channel taken when the app backgrounds (screensaver / Home), so it can be restored
    // on return — otherwise onStop frees the stream and a paused live channel never resumes (even on Play).
    /**
     * A URL alone is not a channel. The tune carries a User-Agent, per-channel request headers and the
     * playlist's pre-buffer override, and a channel that needs a Referer or a custom UA to open needs
     * them on the way back too — restoring with the URL only 403s a channel that had just been playing.
     * The values are the ones the tune came in with (see [tunedUserAgent]), so the restore goes down
     * exactly the same path as a fresh tune, including the per-channel UA precedence.
     */
    private data class LiveRestore(
        val url: String,
        val muted: Boolean,
        val meta: MediaMeta,
        val userAgent: String?,
        val prerollSecs: Int?,
        val liveBufferOverride: LiveBuffer.Override?,
        val httpHeaders: String?,
        val drmConfig: String?,
    )
    @Volatile private var backgroundRestore: LiveRestore? = null

    /** Backgrounded (screensaver / Home): remember what's playing, then free the stream. Paired with
     *  [onAppForegrounded]. */
    fun onAppBackgrounded() {
        currentUrl?.let {
            backgroundRestore = LiveRestore(
                it, muted, _currentMeta.value, tunedUserAgent, tunedPrerollSecs, tunedLiveBufferOverride,
                tunedHttpHeaders, tunedDrmConfig,
            )
        }
        stop()
    }

    /** Foregrounded: re-tune the live channel that was freed while backgrounded (at the live edge), so it
     *  resumes instead of sitting on a dead/empty stream. No-op if something is already playing. */
    fun onAppForegrounded() {
        val r = backgroundRestore ?: return
        backgroundRestore = null
        if (currentUrl != null) return
        play(
            r.url, muted = r.muted, meta = r.meta, userAgent = r.userAgent, prerollSecsOverride = r.prerollSecs,
            liveBufferOverride = r.liveBufferOverride, httpHeaders = r.httpHeaders, drmConfig = r.drmConfig,
        )
    }

    /** Drop any pending restore (e.g. on profile switch — don't bring back the previous user's channel). */
    fun discardBackgroundRestore() { backgroundRestore = null }

    /** Stop playback and free the decoder/connection (e.g. before mpv takes over for fullscreen). Keeps the
     *  ExoPlayer instance alive for the next preview. */
    fun stop() {
        LiveDiagnosticsLog.event("stop() — intentional")
        tune.stoppingIntentionally = true
        currentUrl = null
        tune.hasPlayed = false; tune.retryCount = 0; tune.reconnectPending = false; tune.gaveUp = false; tune.decoderRetryDone = false
        cancelProviderBackOff(); tune.providerBackOffs = 0
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        frameCounter.set(0); tune.lastFrameCount = 0; tune.everRendered = false; tune.lastProgressPos = -1L; tune.frozenChecks = 0
        tune.audioTrackList = emptyList(); tune.audioSelections = emptyList(); _audioCount.value = 0
        tune.textTrackList = emptyList(); tune.textSelections = emptyList(); _subCount.value = 0
        _subtitleOn.value = false; _cues.value = emptyList(); _audioUnsupported.value = false
        _noVideoDetected.value = false; tune.noVideoTriggered = false; tune.readySinceMs = 0L
        _audioOnlyMedia.value = false // re-decided from this stream's own track list
        mainHandler.removeCallbacks(audioOnlyConfirmation)
        tune.hasAudioTrack = false
        tune.hasVideoTrack = true
        _videoHeight.value = null; _videoAspect.value = null; _videoSize.value = null; _streamChips.value = emptyList(); _videoFps.value = null
        _videoRes.value = null // else the next channel's HUD opens showing the previous one's resolution badge
        _state.value = State.IDLE
        player?.run { stop(); clearMediaItems() }
        releaseHttpConnections()
        // Leaving a UHD channel (back / exit fullscreen / background): fully release the 4K decoder.
        releaseDecoderForUhd()
    }

    /**
     * Drop the playback pool's now-idle sockets instead of letting OkHttp hold them for its default
     * 5 idle minutes.
     *
     * A panel that allows one session per account (see [LiveStreamQuirks.isSessionLimit]) refuses the
     * *second* client, so after a handoff a pooled ExoPlayer connection locks mpv out of the channel the
     * user just asked for — mpv/FFmpeg has its own HTTP stack and cannot reuse it. Eviction only closes
     * connections no call is using; anything in flight is untouched.
     *
     * This clears [StreamingHttpClient]'s own pool, not the app-wide one, so EPG/metadata/image
     * connections keep their keep-alive across a zap (F28).
     */
    private fun releaseHttpConnections() {
        // Off the main thread: closing sockets is quick but still I/O, and stop() runs on a UI transition.
        // Its own thread rather than a shared executor, and named so it is identifiable in a trace: this
        // must not queue behind other work, because the whole point is to free the provider's session
        // before the next engine asks for the same channel.
        Thread({
            runCatching { streamingHttp.evictAll() }
                .onFailure { LiveDiagnosticsLog.event("connection pool evict failed: ${it.javaClass.simpleName}") }
        }, "owntv-http-evict").start()
    }

    fun release() {
        LiveDiagnosticsLog.event("release() — intentional")
        releaseLoudness()
        tune.stoppingIntentionally = true
        cancelProviderBackOff(); tune.providerBackOffs = 0
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(progressWatchdog)
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(healthyReset)
        mainHandler.removeCallbacks(audioOnlyConfirmation)
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        surface = null
        currentUrl = null
        sawUhd = false
        _state.value = State.IDLE
    }

    /** Live auto-reconnect: re-fetch [currentUrl] from the live edge after a mid-stream error/stall. Backs
     *  off and gives up after [MAX_RECONNECTS] consecutive failures (then the HUD's Retry button takes over).
     *  tune.retryCount is reset to 0 as soon as playback goes healthy again (STATE_READY).
     *
     *  For an expiring-URL source (Stalker, plan §5.4.1) the reconnect must NOT replay the now-dead
     *  resolved URL — a [reconnectUrlProvider] mints a fresh one first (null/absent → replay as-is,
     *  which is correct for M3U/Xtream and direct-URL Stalker portals). */
    private fun reconnect(reason: String, fastHlsHttpRecovery: Boolean = false) {
        mainHandler.removeCallbacks(stallWatchdog); mainHandler.removeCallbacks(progressWatchdog); mainHandler.removeCallbacks(fpsFastRefresh)
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(healthyReset) // this attempt is a failure, not a recovery
        val p = player
        val url = currentUrl
        if (p == null || url == null || tune.retryCount >= MAX_RECONNECTS) {
            LiveDiagnosticsLog.event("reconnect exhausted ($reason) at ${tune.retryCount}/$MAX_RECONNECTS — giving up")
            tune.gaveUp = true
            _state.value = State.ERROR; _isPlaying.value = false; _buffering.value = false
            val raw = tune.lastCodecError ?: diagnostics.recentError() ?: reason
            _error.value = PlayerErrors.visibleFailure(raw, currentUrl, PlaybackFailure.LostConnection)
            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), raw)
            return
        }
        tune.retryCount++
        tune.reconnectPending = true
        _error.value = null; _errorInfo.value = null; _state.value = State.LOADING; _buffering.value = true
        LiveDiagnosticsLog.event("reconnect attempt ${tune.retryCount}/$MAX_RECONNECTS reason=$reason")
        // A brief HTTP failure on HLS reconnects fast (segments are small and the next one is seconds
        // away), but it does NOT get its retry count forgiven here: only [healthyReset] — sustained
        // playback — clears the ladder. Forgiving on a bare READY let a feed that died 10 s later loop
        // forever without ever reaching the honest "Lost connection" end state.
        val delayMs = if (fastHlsHttpRecovery) hlsHttpReconnectDelayMs(tune.retryCount) else reconnectDelayMs(tune.retryCount)
        // Resolve a fresh URL off-main (Stalker create_link is a network call) before the delayed reload.
        val provider = reconnectUrlProvider
        scope.launch {
            val fresh = if (provider != null) {
                withContext(Dispatchers.IO) {
                    runCatching { provider.freshUrl() }
                        .onFailure { LiveDiagnosticsLog.event("reconnect fresh-url failed: ${it.message}") }
                        .getOrNull()
                }
            } else null
            // Coalesce the backoff delay with the resolve: whichever is later wins, but the resolve must
            // complete before we reload. Post the reload so it lands on the main thread's Looper after delay.
            mainHandler.postDelayed({
                if (currentUrl != url) { tune.reconnectPending = false; return@postDelayed } // superseded (zapped / stopped)
                tune.reconnectPending = false
                val loadUrl = fresh ?: url // null provider/result → replay the (still-valid) stored URL
                if (fresh != null && fresh != url) {
                    currentUrl = fresh // adopt the refreshed URL so a later reconnect compares against it
                    LiveDiagnosticsLog.event("reconnect re-resolved expiring URL (${HttpClient.redactUrl(fresh)})")
                }
                runCatching {
                    reprepare(p, loadUrl) // fresh fetch (live edge)
                }.onFailure { _state.value = State.ERROR; _error.value = PlaybackFailure.LostConnection }
            }, delayMs)
        }
    }

    /**
     * Whether [error] is the video hardware decoder giving up rather than a stream/network problem.
     * Capability mismatches (`…EXCEEDS_CAPABILITIES`) are deliberately NOT included — a decoder that
     * genuinely can't handle the format will fail identically on a rebuild, so retrying only delays mpv.
     */
    /**
     * Whether [error] is "this device has no decoder instance left" rather than "this decoder broke".
     *
     * Android reports it as a decoder-init failure whose diagnostic text names the resource, and the
     * exact words differ per vendor — hence a phrase list rather than an error code. Getting it wrong
     * in either direction is survivable: a missed one falls through to the rebuild retry as before,
     * and a false positive shows an honest sentence about a decoder that was, in fact, unavailable.
     */
    private fun isDecoderExhausted(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        ) {
            return false
        }
        val text = generateSequence(error.cause) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .mapNotNull { cause ->
                (cause as? android.media.MediaCodec.CodecException)?.diagnosticInfo ?: cause.message
            }
            .joinToString(" ")
            .lowercase()
        return DECODER_EXHAUSTED_PHRASES.any { it in text }
    }

    private fun isDecoderFailure(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

    /**
     * A live HLS segment came back 403/404/410.
     *
     * The traced panel signs every segment URL with a short-lived token and then answers **all** of them
     * `403 "Invalid token 2"` while the playlist itself keeps returning 200 — not one segment of the
     * eight in the window ever succeeded. Media3 can only re-issue the exact URL it resolved from the
     * playlist snapshot, so there is no offset, buffer size or retry count that rescues this: an earlier
     * attempt at moving the playhead 40/60/90 s back into the window failed at every rung, because
     * sitting further back makes the token *more* stale, not less.
     *
     * So the moment [LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF] *distinct* segments have been refused,
     * stop: flag it for the ViewModel, which hands the channel to mpv — FFmpeg re-reads the playlist and
     * fetches with a fresh token, which is exactly why the same channel plays there. The panel is
     * remembered so its next channel opens on mpv without the dead spinner first.
     */
    private fun noteSegmentRefusal(segmentUri: String, status: Int) {
        val url = currentUrl ?: return
        if (!refusedSegments.add(segmentUri)) return // same segment retried — not new evidence
        if (refusedSegments.size == 1) logHlsPlaylist("segment HTTP $status")
        LiveDiagnosticsLog.event(
            "segment refused (HTTP $status) ${refusedSegments.size}/${LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF} " +
                "distinct segments on this load",
        )
        if (refusedSegments.size < LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF) return
        if (_segmentsRefused.value) return // already handed over
        LiveStreamQuirks.rememberSegmentRefusal(url)
        LiveDiagnosticsLog.event(
            "provider refuses its own signed segment URLs — ExoPlayer cannot re-sign them; handing this " +
                "panel to mpv",
        )
        _segmentsRefused.value = true
        // The user sees a channel that "just works after a pause" — this is the line that explains why,
        // and it is the one piece of evidence a provider-specific report needs (F26).
        PlaybackErrorLog.event(
            context, "ExoPlayer", live = true,
            reason = PlayerFailureReason.MPV_HANDOFF,
            detail = "provider refused $status on ${refusedSegments.size} signed segment URLs",
        )
    }

    /**
     * The panel refused us because its one allowed session is still held — almost always by mpv, which
     * this engine just took over from.
     *
     * The stream is fine and the URL is right; we are simply the second client. The socket the other
     * engine held has to finish closing and the panel has to notice, which takes longer than the handoff
     * delay, so the answer is to wait and ask again once rather than to fail the channel or bounce it
     * back to mpv (which would hand the session straight back and make the toggle useless).
     */
    private fun noteSessionLimit(uri: String) {
        val url = currentUrl ?: return
        LiveStreamQuirks.rememberSessionLimit(url)
        if (tune.sessionLimitSeen) return // one log line per load is enough; the retry is already armed
        tune.sessionLimitSeen = true
        LiveDiagnosticsLog.event(
            "provider refused with HTTP 458 (account session still in use) uri=${HttpClient.redactUrl(uri)}",
        )
        PlaybackErrorLog.event(
            context, "ExoPlayer", live = true,
            reason = PlayerFailureReason.ONE_SESSION_PROVIDER,
            detail = "HTTP 458 while the previous engine's session was still open — waiting and retrying once",
        )
    }

    /**
     * Give the previous engine's session time to actually die, then try the same channel once more.
     * Only for [noteSessionLimit] — a retry helps nothing when the URL or the stream is the problem.
     */
    private fun retryAfterSessionRelease() {
        val p = player ?: return
        val url = currentUrl ?: return
        tune.sessionLimitRetryDone = true
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("waiting ${SESSION_RELEASE_MS}ms for the provider to free the account session, then retrying")
        mainHandler.postDelayed({
            if (currentUrl != url) return@postDelayed
            runCatching {
                reprepare(p, url)
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                _error.value = PlaybackFailure.Channel
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message.orEmpty()), exoSpec(), it.message)
            }
        }, SESSION_RELEASE_MS)
    }

    /**
     * The panel refused this tune with `429` **and told us when to come back** (`Retry-After: 13`).
     *
     * That is not a verdict on the channel: it is the account still holding the stream the user just left,
     * counted down in seconds. Nothing about the request is wrong, so nothing about it changes — same
     * engine, same format, same URL, same identity, same headers. The channel keeps its spinner, shows the
     * panel's own words with a live countdown, and re-asks the instant the panel said to; a further 429
     * simply restarts the countdown with the newer value. Pressing Retry twice by hand — which is what the
     * user had to do — is exactly this, done manually and with worse timing.
     *
     * Bounded by [MAX_PROVIDER_BACKOFFS] so a panel whose slot never frees still ends on the honest error
     * screen instead of re-asking for the rest of the evening.
     */
    private fun maybeBackOffForProvider(error: PlaybackException): Boolean {
        if (httpStatusOf(error) != HTTP_TOO_MANY_REQUESTS) return false
        val secs = providerRetryAfterSecs ?: return false // no deadline named → nothing to wait for
        val url = currentUrl ?: return false
        if (player == null) return false
        if (tune.providerBackOffs >= MAX_PROVIDER_BACKOFFS) {
            LiveDiagnosticsLog.event("provider still refusing after ${tune.providerBackOffs} waits — letting the failure through")
            return false
        }
        tune.providerBackOffs++
        providerRetryAfterSecs = null // the next refusal brings its own deadline
        _isPlaying.value = false
        _error.value = null; _errorInfo.value = null
        _state.value = State.LOADING; _buffering.value = true
        // The open watchdog is still polling this load's empty buffer; left standing, its "pre-buffer
        // unreachable" branch would reopen the stream in the middle of the countdown.
        mainHandler.removeCallbacks(openWatchdog)
        mainHandler.removeCallbacks(backOffTick)
        _providerBackOff.value = ProviderBackOff(HTTP_TOO_MANY_REQUESTS, providerBackOffMessage(url), secs)
        LiveDiagnosticsLog.event(
            "provider asked for ${secs}s (HTTP $HTTP_TOO_MANY_REQUESTS Retry-After) — waiting, then retrying the " +
                "same URL on the same engine (${tune.providerBackOffs}/$MAX_PROVIDER_BACKOFFS)",
        )
        // Once per tune: the user-visible log should say the channel was queued, not spam a line a second.
        if (tune.providerBackOffs == 1) {
            PlaybackErrorLog.event(
                context, "ExoPlayer", live = true,
                reason = PlayerFailureReason.ONE_SESSION_PROVIDER,
                detail = "HTTP $HTTP_TOO_MANY_REQUESTS with Retry-After ${secs}s — waiting it out and retrying automatically",
            )
        }
        mainHandler.postDelayed(backOffTick, 1_000L)
        return true
    }

    /** The panel's own explanation of the refusal, shortened to its first sentence — the countdown line
     *  has to stay readable across a room, and "Channel limit has been reached." already says it. */
    private fun providerBackOffMessage(url: String): String? {
        val full = LiveStreamQuirks.providerMessage(url, HTTP_TOO_MANY_REQUESTS) ?: return null
        val end = full.indexOf(". ")
        return if (end >= MIN_PROVIDER_SENTENCE) full.substring(0, end + 1) else full
    }

    /** One tick of the visible countdown; the last one performs the retry. */
    private val backOffTick = object : Runnable {
        override fun run() {
            val pending = _providerBackOff.value ?: return
            val left = pending.secondsLeft - 1
            if (left > 0) {
                _providerBackOff.value = pending.copy(secondsLeft = left)
                mainHandler.postDelayed(this, 1_000L)
                return
            }
            _providerBackOff.value = null
            retryAfterProviderBackOff()
        }
    }

    /** Re-ask for the channel the panel deferred. Deliberately identical to the load that was refused. */
    private fun retryAfterProviderBackOff() {
        val p = player ?: return
        val url = currentUrl ?: return
        LiveDiagnosticsLog.event("provider back-off elapsed — retrying the same URL on the same engine")
        runCatching {
            reprepare(p, url)
        }.onFailure {
            _state.value = State.ERROR
            _buffering.value = false
            _error.value = PlaybackFailure.Channel
            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message.orEmpty()), exoSpec(), it.message)
        }
    }

    /** Drop a pending provider wait — a new tune, a stop, a release or the user zapping away supersedes it. */
    private fun cancelProviderBackOff() {
        mainHandler.removeCallbacks(backOffTick)
        _providerBackOff.value = null
        providerRetryAfterSecs = null
    }

    /**
     * Dump the shape of the live playlist Media3 is actually working from — the one piece of evidence
     * that separates "the provider won't serve its newest segments" from an app-side URL/identity bug.
     * Read from the in-memory snapshot ([Player.getCurrentManifest]); costs no extra request. Segment
     * hosts are kept (they are the point), paths are truncated and credential-redacted.
     */
    private fun logHlsPlaylist(reason: String) {
        if (!LiveDiagnosticsLog.enabled) return
        val p = player ?: return
        val manifest = p.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest ?: return
        val playlist = manifest.mediaPlaylist
        val base = playlist.baseUri
        val hosts = playlist.segments
            .map { LiveStreamQuirks.hostKey(androidx.media3.common.util.UriUtil.resolveToUri(base, it.url).toString()) }
        val tail = playlist.segments.takeLast(3).mapIndexed { i, seg ->
            val abs = androidx.media3.common.util.UriUtil.resolveToUri(base, seg.url).toString()
            val idx = playlist.mediaSequence + playlist.segments.size - minOf(3, playlist.segments.size) + i
            "$idx@${LiveStreamQuirks.hostKey(abs)}${HttpClient.redactUrl(abs.substringAfter("://").substringAfter('/')).takeLast(28)}"
        }
        hlsShape()?.let { LiveDiagnosticsLog.event("hls_shape $it") }
        LiveDiagnosticsLog.event(
            "hls_playlist ($reason) mediaSeq=${playlist.mediaSequence} segs=${playlist.segments.size} " +
                "targetDurSec=${playlist.targetDurationUs / 1_000_000.0} windowSec=${playlist.durationUs / 1_000_000.0} " +
                "pdt=${playlist.hasProgramDateTime} startOffsetUs=${playlist.startOffsetUs} " +
                "liveOffsetMs=${p.currentLiveOffset.takeUnless { it == C.TIME_UNSET } ?: -1} " +
                "hosts=${hosts.groupingBy { it }.eachCount()} newest=$tail",
        )
    }

    /**
     * How the live HLS presentation is put together, or null if this isn't HLS. Decides whether a stuck
     * open can even *be* a shared-timestamp-adjuster deadlock: that needs a separate audio or subtitle
     * rendition, which loads on its own thread and waits to be aligned with the primary one. A single
     * playlist of muxed audio+video has one loader and cannot deadlock that way, which would point the
     * finger at the renderers (no usable keyframe, a decoder emitting nothing) instead.
     */
    private fun hlsShape(): String? {
        val manifest = player?.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest ?: return null
        val mv = manifest.multivariantPlaylist
        return "variants=${mv.variants.size} audios=${mv.audios.size} subs=${mv.subtitles.size} " +
            "videos=${mv.videos.size} muxedAudio=${mv.muxedAudioFormat != null} " +
            "muxedCaptions=${mv.muxedCaptionFormats?.size ?: 0} " +
            "discontinuitySeq=${manifest.mediaPlaylist.discontinuitySequence}"
    }

    /**
     * Whether this failure is about the *shape* of the stream, i.e. the only kind a `.ts`⇄`.m3u8` swap
     * could possibly fix.
     *
     * The guard matters more than the retry. A refusal — 403, 429, a session limit — is the panel
     * answering the *account*, and the identical answer waits at every other URL on it, so swapping the
     * extension there just invents a URL that 404s. Traced on a panel that returns 429 "Channel limit
     * has been reached": the ladder read it as a format problem, chased an invented `.ts` endpoint for
     * ~45 s and ended on a screen blaming the channel, while the original URL worked the moment the
     * account's other stream closed.
     */
    private fun isFormatFailure(error: PlaybackException): Boolean {
        val http = httpStatusOf(error)
        if (http != null && LiveStreamQuirks.isRequestRefusal(http)) return false
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            -> true
            // The endpoint simply isn't there / isn't served in this form at this address.
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> http == 404 || http == 415
            else -> false
        }
    }

    /**
     * Try the channel's other endpoint form once: `…/ch.m3u8` ⇄ `…/ch.ts`, query kept intact.
     *
     * mpv has had this rung for a long time; ExoPlayer had none, so a panel that publishes one form in
     * the playlist and serves the other could only be rescued by handing the whole channel over — a
     * visible engine swap for what is one character of URL. Once per load, and only for a genuine
     * format failure ([isFormatFailure]); if the sibling fails too, the normal ERROR path runs and the
     * fallback ladder continues exactly as before.
     */
    private fun retryAlternateFormat() {
        val p = player ?: return
        val url = currentUrl ?: return
        tune.altFormatRetryDone = true
        val alt = LiveStreamQuirks.alternateFormatUrl(url)?.takeIf { it != url } ?: return
        // A channel already known to have no HLS sibling shouldn't be asked for one again this session.
        if (LiveStreamQuirks.isExplicitHlsUrl(alt) && LiveStreamQuirks.lacksHlsVariant(url)) return
        currentUrl = alt
        tune.forceHlsForCurrentLoad = false
        tune.responseWasHls = false
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("stream didn't open — trying the ${alt.substringBefore('?').substringAfterLast('.')} form of this channel")
        android.util.Log.w(LiveDiagnosticsLog.TAG, "trying alternate format: ${HttpClient.redactUrl(alt)}")
        mainHandler.post {
            if (currentUrl != alt) return@post
            runCatching {
                reprepare(p, alt)
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                val raw = it.message.orEmpty()
                _error.value = PlayerErrors.visibleFailure(raw, alt, PlaybackFailure.Channel)
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), it.message)
            }
        }
    }

    /**
     * Try the same channel once more under [HttpClient.FALLBACK_USER_AGENT].
     *
     * For the WAF that refuses the default player identity outright (quirk 3 in [LiveStreamQuirks]).
     * Worth answering here rather than leaving it to the fallback ladder: mpv sends the same default UA,
     * so without this the channel walks the entire ladder and dies at the far end looking exactly like a
     * dead provider. The lesson is remembered panel-wide, so the rest of the playlist opens first time.
     */
    private fun retryWithFallbackUserAgent() {
        val p = player ?: return
        val url = currentUrl ?: return
        tune.uaRetryDone = true
        currentUa = HttpClient.FALLBACK_USER_AGENT
        LiveStreamQuirks.rememberBlocksDefaultUserAgent(url)
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("provider refused the default User-Agent — retrying as ${HttpClient.FALLBACK_USER_AGENT}")
        android.util.Log.w(LiveDiagnosticsLog.TAG, "default User-Agent refused — retrying once as ${HttpClient.FALLBACK_USER_AGENT}")
        mainHandler.post {
            if (currentUrl != url) return@post
            runCatching {
                reprepare(p, url)
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                val raw = it.message.orEmpty()
                _error.value = PlayerErrors.visibleFailure(raw, url, PlaybackFailure.Channel)
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), it.message)
            }
        }
    }

    private fun retryRedirectedStreamAsHls() {
        val p = player ?: return
        val url = currentUrl ?: return
        tune.redirectedHlsRetryDone = true
        tune.forceHlsForCurrentLoad = true
        // Panel-wide lesson, not a per-channel one: every other channel here — and mpv, if we hand over —
        // now starts as HLS instead of repeating this failure.
        LiveStreamQuirks.rememberHlsRedirect(url)
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        LiveDiagnosticsLog.event("redirected .ts response is HLS — retrying with HlsMediaSource")
        mainHandler.post {
            if (currentUrl != url) return@post
            runCatching {
                reprepare(p, url)
            }.onFailure {
                _state.value = State.ERROR
                _buffering.value = false
                val raw = it.message.orEmpty()
                _error.value = PlayerErrors.visibleFailure(raw, url, PlaybackFailure.Channel)
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), exoSpec(), it.message)
            }
        }
    }

    /**
     * One-shot recovery from a decoder that died **before the first frame**.
     *
     * Observed on Realtek TVs with 4K HEVC raw-TS: the codec is created, `format_supported=YES`, and
     * ~1.5s later `MediaCodec.dequeueOutputBuffer` throws IllegalStateException — `Decoder failed:
     * OMX.realtek.video.decoder`. The MediaCodec is then permanently wedged, but a NEW one plays the
     * very same stream: that is exactly what the HUD's compatibility-mode toggle used to achieve by
     * hand (mpv, then back to ExoPlayer on a freshly built player). ExoPlayer's own retry can't fix it
     * because `prepare()` reuses the wedged codec, so the player instance itself has to go.
     *
     * Once per load ([TuneState.decoderRetryDone]): if the rebuild fails too, the normal ERROR path runs and the
     * VM hands the channel to mpv as before — this only costs a genuinely undecodable channel one extra
     * attempt before the fallback.
     */
    private fun rebuildDecoderAndRetry(error: PlaybackException) {
        val url = currentUrl ?: return
        tune.decoderRetryDone = true
        LiveDiagnosticsLog.event("decoder failed before first frame (${error.errorCodeName}) — rebuilding the decoder and retrying once")
        android.util.Log.w(LiveDiagnosticsLog.TAG, "decoder failure before first frame — rebuild + retry once")
        _state.value = State.LOADING; _buffering.value = true
        _error.value = null; _errorInfo.value = null
        // Drop the whole player: removeListener first so this release doesn't come back as STATE_IDLE.
        player?.run { removeListener(listener); release() }
        player = null
        videoRenderer = null
        sawUhd = false
        // A fresh codec alone does NOT rescue this — the dead native window has to go too, or the retry
        // reproduces the identical failure. See [recreateSurface].
        recreateSurface()
        // Let the OMX component actually tear down before the replacement asks for it — the same
        // reason the mpv→ExoPlayer swap in LiveViewModel waits before re-tuning.
        mainHandler.postDelayed({
            if (currentUrl != url) return@postDelayed // zapped away / stopped while we waited
            runCatching {
                val p = build().also { player = it }
                surface?.let { p.setVideoSurface(it) }
                applyMute(force = true)
                // A rebuilt player is a blank player: it knows nothing of the user's audio/subtitle
                // language preferences, and nothing of Audio Mode being on. Without these two the retry
                // came back in the wrong language, and with the video track re-enabled behind an
                // Audio Mode session that had deliberately released the surface — exactly what a fresh
                // tune re-applies at the same point in play().
                applyLanguagePrefs()
                setVideoTrackDisabled(_audioOnly.value)
                reprepare(p, url)
            }.onFailure {
                LiveDiagnosticsLog.event("decoder rebuild failed: ${it.message}")
                _state.value = State.ERROR
                _error.value = PlaybackFailure.Channel
                _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(it.message ?: ""), exoSpec(), it.message ?: "")
            }
        }, DECODER_REBUILD_DELAY_MS)
    }

    // --- Audio Mode (Audio Mode plan §5): keep audio playing, release the video surface ---
    private val _audioOnly = MutableStateFlow(false)
    override val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _audioOnlyMedia = MutableStateFlow(false)
    /** This channel carries no video track — a radio station. See [PlaybackEngine.audioOnlyMedia]. */
    override val audioOnlyMedia: StateFlow<Boolean> = _audioOnlyMedia.asStateFlow()
    override fun enterAudioOnly() {
        if (_audioOnly.value) return
        _audioOnly.value = true
        player?.clearVideoSurface() // audio keeps playing without a surface; [surface] kept for return
        setVideoTrackDisabled(true)
    }
    override fun exitAudioOnly() {
        if (!_audioOnly.value) return
        _audioOnly.value = false
        setVideoTrackDisabled(false)
        surface?.let { player?.setVideoSurface(it) }
    }

    /**
     * Dropping the surface alone only stops the *drawing*: ExoPlayer keeps decoding every video frame into
     * a dummy buffer, so Audio Mode still burns the decoder, the CPU and the bandwidth it was meant to save.
     * Disabling the track releases the decoder outright and skips the video samples (F19c).
     */
    private fun setVideoTrackDisabled(disabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    // --- PlaybackEngine controls (full-screen HUD) ---
    override fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun setZoomMode(mode: ZoomMode) { _zoomMode.value = mode } // ExoPreviewSurface observes this + videoAspect/Size and sizes the surface (see Modifier.videoZoom)

    override fun adjustVolume(delta: Int) {
        // ExoPlayer itself can't amplify past unity (and a gain audio-processor broke the audio sink), so
        // 100–150% rides on the platform LoudnessEnhancer instead — same range as mpv, so a quiet channel
        // can be lifted without leaving Live TV (F19a).
        val v = (_volume.value + delta).coerceIn(0, MAX_VOLUME)
        _volume.value = v
        muted = v == 0
        applyMute() // re-enables/deselects the audio track when crossing 0 (passthrough-safe mute)
        player?.volume = v.coerceAtMost(100) / 100f
        applyVolumeBoost(v)
    }

    // --- Per-item zoom / volume the user asked us to remember (playback_prefs, DB v32) ---

    /** Apply this channel's remembered zoom/volume over the defaults just set by the tune. The read
     *  can't hold up the tune, so a late answer is dropped if the user has already zapped away. */
    private fun applyRememberedPrefs(key: String) {
        val tunedUrl = lastTunedUrl
        scope.launch {
            val row = playbackPrefs.prefsFor(key) ?: return@launch
            if (tunedUrl != lastTunedUrl) return@launch
            row.zoomMode?.let { name ->
                runCatching { ZoomMode.valueOf(name) }.getOrNull()?.let { _zoomMode.value = it }
            }
            // Never un-mute the browse preview pane by restoring a level the user set in fullscreen.
            if (!muted) row.volumeBoost?.let { adjustVolume(it - _volume.value) }
        }
    }

    override fun setZoomModeByUser(mode: ZoomMode) {
        setZoomMode(mode)
        val key = _currentMeta.value.contentKey ?: lastTunedUrl ?: return
        scope.launch { playbackPrefs.rememberZoom(key, mode.name) }
    }

    override fun adjustVolumeByUser(delta: Int) {
        adjustVolume(delta)
        val key = _currentMeta.value.contentKey ?: lastTunedUrl ?: return
        val level = _volume.value
        scope.launch { playbackPrefs.rememberVolume(key, level) }
    }

    // --- Volume boost above 100% (F19a). Shared with the VOD ExoPlayer engine — see [VolumeBoost]. ---
    private val boost = VolumeBoost { LiveDiagnosticsLog.event(it) }

    /** Aim the boost effect at [percent]; also re-attaches after a player rebuild (new session id). */
    private fun applyVolumeBoost(percent: Int) {
        boost.apply(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET, percent)
    }

    private fun releaseLoudness() = boost.release()

    override fun toggleMute() = setMuted(!muted)
    override fun retry() {
        val url = currentUrl ?: return
        // Replay the identity this channel was tuned with — HUD Retry used to re-open with the URL only,
        // so a channel needing a Referer/UA played on first open and 403'd the moment you pressed Retry.
        val ua = tunedUserAgent
        val preroll = tunedPrerollSecs
        val latency = tunedLiveBufferOverride
        val headers = tunedHttpHeaders
        val drm = tunedDrmConfig
        val provider = reconnectUrlProvider
        if (provider == null) { play(url, muted, _currentMeta.value, ua, preroll, latency, headers, drm); return }
        // Expiring-URL source (Stalker): re-resolve before retrying, then reload on the main thread.
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { provider.freshUrl() }
                    .onFailure { LiveDiagnosticsLog.event("retry fresh-url failed: ${it.message}") }
                    .getOrNull()
            }
            play(fresh ?: url, muted, _currentMeta.value, ua, preroll, latency, headers, drm)
        }
    }
    override fun selectAudio(id: Int) {
        val p = player ?: return
        val sel = tune.audioSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .build()
        tune.audioTrackList = tune.audioTrackList.map { it.copy(selected = it.mpvId == id) }
        // Same defect, same fix as the VOD engine: a bitstreamed Dolby/DTS track re-selected in place
        // leaves the TV's decoder producing broken sound, with nothing in the sink reporting a fault.
        // Re-priming from the current position rebuilds the output once, the way tuning in does.
        //
        // Guarded on seekability, which VOD does not need: plenty of channels are unseekable streams
        // where a seek is not a cheap in-buffer re-prime but a full reconnect — the very thing the
        // reconnect watchdog exists to avoid. On those the plain override stands, exactly as before,
        // so this can only ever improve a channel and never destabilise one.
        if (audioWatchdog.passthrough && p.isCurrentMediaItemSeekable) {
            LiveDiagnosticsLog.event("passthrough audio: re-priming the output after the track change")
            runCatching { p.seekTo(p.currentPosition) }
        }
    }

    override fun selectSubtitle(id: Int) {
        val p = player ?: return
        val sel = tune.textSelections.firstOrNull { it.id == id } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .build()
        _subtitleOn.value = true // mount the SubtitleView overlay
        tune.textTrackList = tune.textTrackList.map { it.copy(selected = it.mpvId == id) }
    }

    override fun disableSubtitles() {
        player?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
        _subtitleOn.value = false
        _cues.value = emptyList()
        tune.textTrackList = tune.textTrackList.map { it.copy(selected = false) }
    }

    override fun audioTracks(): List<TrackOption> = tune.audioTrackList
    override fun textTracks(): List<TrackOption> = tune.textTrackList

    /** Build the audio + subtitle track lists from the active stream so the HUD menus can switch language /
     *  subtitles (multi-track live channels, or a VOD file imported via M3U). Mirrors [ExoSubtitleEngine]. */
    private fun rebuildTracks(tracks: androidx.media3.common.Tracks) {
        // A preferred subtitle language makes Media3 select a matching text track on its own. The cue
        // overlay is mounted only while [subtitleOn], so without this the track would be decoded and never
        // drawn — and the HUD would report "off" while a track really is selected. Only ever turns the
        // overlay ON, and only for users who set the preference, so nobody else's live TV changes.
        if (prefSubLang.isNotBlank() && !_subtitleOn.value) {
            val autoSelected = tracks.groups.any { g ->
                g.type == androidx.media3.common.C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) }
            }
            if (autoSelected) _subtitleOn.value = true
        }
        val audio = ArrayList<TrackOption>(); val aSel = ArrayList<AudioSel>(); var aId = 0
        val text = ArrayList<TrackOption>(); val tSel = ArrayList<TextSel>(); var tId = 0
        for (group in tracks.groups) {
            when (group.type) {
                androidx.media3.common.C.TRACK_TYPE_AUDIO -> for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
                    audio.add(
                        TrackOption(
                            label = f.label.orEmpty(),
                            mpvId = aId,
                            selected = group.isTrackSelected(i),
                            lang = lang,
                            typeIndex = aId,
                            labelKind = TrackLabelKind.AUDIO,
                        ),
                    )
                    aSel.add(AudioSel(aId, group.mediaTrackGroup, i)); aId++
                }
                androidx.media3.common.C.TRACK_TYPE_TEXT -> for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
                    text.add(
                        TrackOption(
                            label = f.label.orEmpty(),
                            mpvId = tId,
                            selected = _subtitleOn.value && group.isTrackSelected(i),
                            lang = lang,
                            typeIndex = tId,
                            labelKind = TrackLabelKind.SUBTITLE,
                        ),
                    )
                    tSel.add(TextSel(tId, group.mediaTrackGroup, i)); tId++
                }
            }
        }
        // Audio-only (radio) streams must keep their audio renderer even when muted — deselecting it would
        // leave nothing to render and the progress watchdog would read that as a dead feed.
        tune.hasVideoTrack = tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        tune.hasAudioTrack = audio.isNotEmpty()
        // A radio channel in a TV playlist is the commonest audio-only case of all. Say so on screen —
        // Audio Mode excepted, where the app is the one that turned the picture off.
        updateAudioOnlyClassification()
        applyMute()
        tune.audioTrackList = audio; tune.audioSelections = aSel; _audioCount.value = audio.size
        tune.textTrackList = text; tune.textSelections = tSel; _subCount.value = text.size
        if (tv.own.owntv.core.CoreBuildInfo.debug) {
            LiveDiagnosticsLog.event(
                "tracks: audio=${audio.size} text=${text.size}" +
                    text.joinToString(prefix = " [", postfix = "]") { it.label },
            )
        }
        // Audio exists but ExoPlayer can decode none of it → the VM will route this stream to mpv.
        val anySupportedAudio = tracks.groups.any { g ->
            g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.isTrackSupported(it) }
        }
        _audioUnsupported.value = audio.isNotEmpty() && !anySupportedAudio
    }

    /**
     * Track discovery is incremental for some providers: audio can be announced before video.
     * Confirm a stable, playing audio-only stream instead of flashing the radio badge on every tune.
     */
    private fun updateAudioOnlyClassification() {
        mainHandler.removeCallbacks(audioOnlyConfirmation)
        _audioOnlyMedia.value = false
        if (tune.hasAudioTrack && !tune.hasVideoTrack && !_audioOnly.value &&
            player?.playbackState == Player.STATE_READY
        ) {
            mainHandler.postDelayed(audioOnlyConfirmation, AUDIO_ONLY_CONFIRM_MS)
        }
    }

    // Effective User-Agent for the current stream; updated per play() call.
    // null = no source UA configured, use DEFAULT_USER_AGENT.
    private var currentUa: String = HttpClient.DEFAULT_USER_AGENT
    /** Per-channel HTTP headers for the tuned channel (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`, F16).
     *  Empty for providers that carry none, which is the usual case. */
    private var currentHeaders: Map<String, String> = emptyMap()
    private var dataSourceForUa: String = ""
    private var dataSourceForHeaders: Map<String, String> = emptyMap()
    private var cachedHttpDataSource: OkHttpDataSource.Factory? = null
    private var cachedDefaultFactory: DefaultMediaSourceFactory? = null
    private var cachedHlsCcFactory: HlsMediaSource.Factory? = null
    /** Debug-build HTTP probe for provider-side failures. It persists redacted metadata, a
     *  media-signature classification, and — for FAILED responses only — a short scrubbed text prefix of
     *  the error body, which is usually the panel telling us exactly why it refused ("token expired",
     *  "max connections"). Successful bodies are never read. Credentials never appear: URLs go through
     *  [HttpClient.redactUrl], body text additionally has this request's own user/pass/token strings
     *  masked ([textPrefix]), and Authorization/Cookie are logged as presence flags, never values. */
    private val diagnosticHttpClient by lazy {
        streamingHttp.client.newBuilder()
            .addInterceptor { chain ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val request = chain.request()
                try {
                    val response = chain.proceed(request)
                    val finalUrl = response.request.url.toString()
                    if (isHlsResponse(finalUrl, response.header("Content-Type"))) {
                        tune.responseWasHls = true
                        // The panel — not just this URL — serves HLS behind a `.ts` endpoint. mpv needs to
                        // know that too, or FFmpeg reconnects to the manifest's EOF forever.
                        if (!request.url.toString().substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            LiveStreamQuirks.rememberHlsRedirect(request.url.toString())
                        }
                    }
                    // A failed body is peeked even with diagnostics off — it carries the one sentence the
                    // error screen can actually show the user ("Channel limit has been reached…"), and a
                    // user hitting that wall has no reason to have turned logging on first.
                    val requested = request.url.toString()
                    val failed = !response.isSuccessful
                    val prefix = if (failed || LiveDiagnosticsLog.enabled) {
                        runCatching { response.peekBody(564).bytes() }.getOrDefault(byteArrayOf())
                    } else {
                        byteArrayOf()
                    }
                    val failureText = if (failed) textPrefix(prefix, requested) else ""
                    if (failed) {
                        noteProviderMessage(requested, response.code, response.header("Content-Type"), failureText)
                        // The header that says WHEN to come back is only ever on the response — by the time
                        // the failure reaches [onPlayerError] there is nothing left to read it from.
                        if (response.code == HTTP_TOO_MANY_REQUESTS) {
                            retryAfterSecs(response.header("Retry-After"))?.let { providerRetryAfterSecs = it }
                        }
                    }
                    if (LiveDiagnosticsLog.enabled) {
                        // A redirect is invisible in the final URL alone, and it is exactly what decides
                        // whether a segment came from the panel's origin or its CDN.
                        val via = if (requested != finalUrl) {
                            " requested=${HttpClient.redactUrl(requested)}"
                        } else {
                            ""
                        }
                        // For an error the body IS the diagnosis ("token expired", "max connections", a
                        // hotlink-protection page…). Metadata-only for success responses, as before.
                        val body = if (failed) " body=\"$failureText\"" else ""
                        LiveDiagnosticsLog.event(
                            "http_response role=${requestRole(requested)} code=${response.code} " +
                                "type=${response.header("Content-Type").orEmpty()} " +
                                "length=${response.body.contentLength()} signature=${mediaSignature(prefix)} " +
                                "server=${response.header("Server").orEmpty()} xcache=${response.header("X-Cache").orEmpty()} " +
                                "age=${response.header("Age").orEmpty()} retryAfter=${response.header("Retry-After").orEmpty()} " +
                                "setCookie=${response.headers("Set-Cookie").size} " +
                                "reqCookie=${request.header("Cookie") != null} reqAuth=${request.header("Authorization") != null} " +
                                "reqRange=${request.header("Range") != null} ua=${request.header("User-Agent").orEmpty()} " +
                                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                                "url=${HttpClient.redactUrl(finalUrl)}$via$body",
                        )
                    }
                    response
                } catch (t: Throwable) {
                    if (LiveDiagnosticsLog.enabled) {
                        LiveDiagnosticsLog.event(
                            "http_failure type=${t.javaClass.simpleName} message=${HttpClient.redactUrl(t.message.orEmpty())} " +
                                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                                "url=${HttpClient.redactUrl(request.url.toString())}",
                        )
                    }
                    throw t
                }
            }
            .build()
    }

    /**
     * Keep a refusal body that reads like a message to a human, so the error screen can quote it.
     *
     * Deliberately picky about what qualifies — a wrong quote is worse than none:
     * - **HTML is skipped.** That body is a WAF challenge or a hosting landing page ("Just a moment…
     *   Enable JavaScript and cookies to continue"), written for a browser, not for this user.
     * - **Binary is skipped.** A refused *segment* often still carries media bytes, which [textPrefix]
     *   would hand back as punctuation soup; a body has to be mostly letters and spaces to count.
     * - **Very short bodies are skipped**, since "0" or "error" explains nothing the status didn't.
     */
    private fun noteProviderMessage(url: String, code: Int, contentType: String?, text: String) {
        if (contentType?.contains("html", ignoreCase = true) == true) return
        val clean = text.trim()
        if (clean.length < 12) return
        if (clean.count { it.isLetter() || it.isWhitespace() } < clean.length * 3 / 4) return
        LiveStreamQuirks.rememberProviderMessage(url, code, clean)
    }

    /**
     * A failed response's body, reduced to one short printable line for the log: markup stripped,
     * whitespace collapsed, capped, then scrubbed of anything that could identify the account. Scrubbing
     * is done against [requestUrl]'s **own** credentials — the Xtream `/live/<user>/<pass>/` segments and
     * signed query values — so even a panel that echoes the username back in an error page cannot leak it.
     */
    private fun textPrefix(bytes: ByteArray, requestUrl: String): String {
        val text = bytes.decodeToString()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .filter { it.code in 32..126 }
            .trim()
            .take(160)
        return HttpClient.redactUrl(secretsIn(requestUrl).fold(text) { acc, secret -> acc.replace(secret, "***") })
            .replace('"', '\'')
    }

    /** Credential-bearing substrings of [url] — path segments and signed query values — never logged. */
    private fun secretsIn(url: String): List<String> {
        val path = url.substringBefore('?')
        val segments = Regex("(?i)/(?:live|movie|series|vod|timeshift)/([^/]+)/([^/]+)/")
            .find(path)?.groupValues?.drop(1).orEmpty()
        val queryValues = url.substringAfter('?', "").split('&')
            .mapNotNull { it.substringAfter('=', "").takeIf { v -> v.length >= 6 } }
        return (segments + queryValues).filter { it.length >= 3 }
    }

    /** What this request was for — the one thing a bare URL in the log doesn't say. */
    private fun requestRole(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "playlist"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4") -> "segment"
            path.endsWith(".key") -> "key"
            else -> "stream"
        }
    }

    private fun mediaSignature(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        fun has(vararg expected: Int): Boolean =
            bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 0xff) == expected[it] }
        val textStart = bytes.take(32).map { it.toInt().toChar() }.joinToString("").trimStart().lowercase()
        return when {
            // Three sync bytes distinguish a real transport stream from an error page beginning with 'G'.
            bytes.size > 376 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte() -> "mpeg-ts"
            has(0x1a, 0x45, 0xdf, 0xa3) -> "matroska"
            bytes.size >= 8 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> "mp4"
            has(0x00, 0x00, 0x01, 0xba) -> "mpeg-ps"
            has(0x49, 0x44, 0x33) -> "id3/audio"
            textStart.startsWith("#extm3u") -> "hls-manifest"
            textStart.startsWith("<!doctype") || textStart.startsWith("<html") -> "html"
            textStart.startsWith("{") || textStart.startsWith("[") -> "json"
            else -> "unknown"
        }
    }

    /**
     * Stock policy everywhere except a live media segment the provider outright refuses (403/404/410).
     * Media3 can only re-issue the identical segment URL, and the traced panel answers 403 to it for as
     * long as the playlist snapshot lives — the default ladder therefore spends ~8 s hammering a URL
     * that will never succeed, drains the buffer and turns a recoverable hiccup into a dead channel.
     * One short retry (a genuine blip), then fatal so [maybeBackOffFromLiveEdge]/the reconnect ladder
     * can act. Manifests and every other data type keep the stock behaviour.
     */
    private val edgeRefusalPolicy =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                val status = httpStatusOf(loadErrorInfo.exception)
                val isSegment = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA
                if (isSegment && status != null && LiveStreamQuirks.isEdgeRefusal(status)) {
                    return edgeRefusalRetryDelayMs(loadErrorInfo.errorCount)
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }

    private fun httpDataSourceFor(ua: String): OkHttpDataSource.Factory {
        // Keyed on the headers as well as the UA: the three cached factories bake the data source in,
        // so a channel with its own Referer must not reuse the previous channel's factory (F16).
        if (ua != dataSourceForUa || currentHeaders != dataSourceForHeaders || cachedHttpDataSource == null) {
            cachedHttpDataSource = OkHttpDataSource.Factory(diagnosticHttpClient).setUserAgent(ua)
                .setTransferListener(throughputTracker)
                .setDefaultRequestProperties(
                    currentHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) },
                )
            // Raw MPEG-TS (typical Xtream live ".ts"): providers rarely declare caption descriptors in
            // the PMT, so the stock TS extractor never exposes the embedded CEA-608 track (#57).
            // FLAG_OVERRIDE_CAPTION_DESCRIPTORS makes it expose the standard CC1 track regardless; the
            // flag only affects TS — every other format sniffs exactly as before. Passed into the same
            // DefaultMediaSourceFactory that has always handled non-HLS live, so routing is unchanged.
            // The flag alone is NOT enough: DefaultExtractorsFactory passes an empty subtitle-format
            // list to DefaultTsPayloadReaderFactory, and with the override flag that empty list is
            // returned verbatim (= zero CC tracks, even declared ones). The CEA-608 CC1 format must be
            // supplied explicitly via setTsSubtitleFormats.
            val cc1 = androidx.media3.common.Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608)
                .setAccessibilityChannel(1) // CC1 — the standard primary caption channel
                .build()
            cachedDefaultFactory = DefaultMediaSourceFactory(
                cachedHttpDataSource!!,
                androidx.media3.extractor.DefaultExtractorsFactory()
                    .setTsExtractorFlags(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS,
                    )
                    .setTsSubtitleFormats(listOf(cc1)),
            )
            cachedHlsCcFactory = HlsMediaSource.Factory(cachedHttpDataSource!!)
                .setExtractorFactory(DefaultHlsExtractorFactory(0, true))
                // Media3 defaults this to zero, which it documents as an *infinite* timeout: a rendition
                // whose chunk can't be timestamp-aligned with the primary one parks its loading thread in
                // an unbounded wait(), so that track never produces a sample and the player sits in
                // BUFFERING forever with no error to react to. Bound it — a TimeoutException surfaces as a
                // normal load error, which the retry ladder (TS retry, then mpv) can actually act on.
                .setTimestampAdjusterInitializationTimeoutMs(HLS_TIMESTAMP_ALIGN_TIMEOUT_MS)
                .setLoadErrorHandlingPolicy(edgeRefusalPolicy)
            dataSourceForUa = ua
            dataSourceForHeaders = currentHeaders
        }
        return cachedHttpDataSource!!
    }

    /** HLS → caption-aware factory; everything else (raw MPEG-TS, etc.) → default. */
    private fun mediaSourceFor(url: String): MediaSource {
        httpDataSourceFor(currentUa) // ensure factories match current UA
        // Live latency (#72): a target live-edge offset for live streams (HLS/DASH). Ignored by
        // progressive/raw-TS sources, so it can only help where it applies. Unset (Balanced) keeps
        // Media3's own default: nothing in the app overrides the user's live latency any more. An earlier
        // attempt to auto-widen it away from a 403-ing live edge was tested and did nothing — the traced
        // panel refuses EVERY segment in its window, not just the newest (see [noteSegmentRefusal]).
        //
        // DO NOT "fix" the LOW = 2 s case by flooring the offset or by adding a playback-speed band.
        // That looks obviously right — a 2 s target is structurally unreachable on a standard playlist
        // (~6 s segments, ≥3-segment hold-back) — and it is wrong. Checked against the Media3 source
        // (`HlsMediaSource.createTimelineForLive` / `updateLiveConfiguration`, release branch):
        //
        //  * The offset we set is NOT raised to the playlist's hold-back. It is used verbatim, clamped
        //    only by `Util.constrainValue(targetLiveOffsetUs, liveEdgeOffsetUs,
        //    playlist.durationUs + liveEdgeOffsetUs)` — i.e. against the window's own bounds only.
        //  * BUT `updateLiveConfiguration` sets `disableSpeedAdjustment` when both playback speeds are
        //    unset AND the playlist carries no `holdBackUs`/`partHoldBackUs`. A plain (non-LL) HLS
        //    playlist has neither and we set neither speed — so Media3 pins playback speed to 1.0 and
        //    never chases the unreachable target. The case the "fix" worries about is already safe.
        //  * Calling `setMinPlaybackSpeed`/`setMaxPlaybackSpeed` to bound the chase would set both
        //    speeds and therefore ENABLE speed adjustment on exactly those playlists, turning a pinned
        //    1.0× into a live-edge chase. The fix would create the bug on the majority of streams.
        //
        // On a genuine LL-HLS playlist (which does publish hold-back) Media3's own defaults already
        // bound the adjustment. Both stream classes are correct as-is; leave this alone unless you have
        // re-read those two methods in the Media3 version we actually ship.
        val targetOffsetSecs = effectiveLiveBufferSecs()
        val item = MediaItem.Builder().setUri(url).apply {
            targetOffsetSecs?.let {
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(it * 1000L).build())
            }
            // #115 — a protected channel. multiSession is mandatory here: a live stream rotates its
            // content key, so a single session plays for a few minutes and then stops with a licence
            // error. DefaultMediaSourceFactory builds the DrmSessionManager from this.
            currentDrm?.let { setDrmConfiguration(it.toMediaDrmConfiguration(multiSession = true)) }
        }.build()
        val uri = item.localConfiguration?.uri ?: run {
            activeIsHls = false
            return cachedDefaultFactory!!.createMediaSource(item)
        }
        // A panel already caught redirecting `.ts` → manifest goes straight to the HLS factory: without
        // this every channel on it repeats the container-unsupported failure + retry before recovering.
        val knownHlsHost = LiveStreamQuirks.isKnownHlsHost(url)
        val isHls = tune.forceHlsForCurrentLoad || knownHlsHost || Util.inferContentType(uri) == C.CONTENT_TYPE_HLS
        activeIsHls = isHls
        LiveDiagnosticsLog.event(
            "media_source inferred=${if (isHls) "hls" else "progressive"} knownHlsHost=$knownHlsHost " +
                "targetOffsetSec=${targetOffsetSecs ?: -1} " +
                "url=${HttpClient.redactUrl(url)}",
        )
        return if (isHls) cachedHlsCcFactory!!.createMediaSource(item)
        else cachedDefaultFactory!!.createMediaSource(item)
    }

    private fun build(): ExoPlayer {
        // Tuned for raw MPEG-TS live (Xtream `.ts`, no HLS manifest): ONE long-lived HTTP response, where
        // ExoPlayer stops reading the socket once the buffer is full and resumes only after it drains back
        // to minBufferMs. Provider restreamers/proxies cull a connection that sits idle that long, and EOF
        // on a duration-less source surfaces as STATE_ENDED/IO error — a reconnect (visible glitch) every
        // few seconds on a channel that is otherwise healthy. HLS never hit this: each segment is its own
        // short request, so a pause between segments costs nothing.
        //
        // DefaultLoadControl (prioritizeTimeOverSizeThresholds = false, the default) resolves to:
        //     isLoading = !targetBytesReached && (buffered < min || (buffered < max && isLoading))
        // so the socket's idle window is (max − min) in wall-clock time, NOT the buffer depth. Hence a
        // NARROW window — buffering deeper would only park the socket longer, and a deep buffer cannot
        // hide the cull anyway (the EOF still forces a re-prepare).
        //
        // The byte cap does the same job for high-bitrate streams: on a ~25 Mbps UHD TS, TARGET_BUFFER_BYTES
        // is reached at under MIN_BUFFER_MS of media, so loading resumes on every drain — effectively a
        // continuous read. It also bounds what a 4K channel pins on the app heap.
        //
        // The start thresholds stay tiny (1s to first play, 2s after a rebuffer): they, not the buffer
        // depth, are what tuning and preview scrolling cost.
        val budget = playerBudget ?: PlayerBudget.of(context).also { playerBudget = it }
        //
        // F06/F07: those numbers used to be hardcoded, which is why the **Live latency** setting did
        // nothing at all for raw-TS live — it only ever set a `MediaItem.LiveConfiguration` offset, and
        // Media3 honours that for HLS/DASH only. The preset now drives the depth (keeping the 2 s idle
        // window at every setting), and the new "Pre-buffer" drives the start thresholds.
        // Balanced + Off reproduces the previous constants exactly.
        val lc = LiveBuffer.loadControlFor(effectiveLiveBufferSecs(), effectivePrerollSecs()).also { builtLoadControl = it }
        val defaultBytes = if (budget.lowSpec) LOW_RAM_TARGET_BYTES else TARGET_BUFFER_BYTES
        LiveDiagnosticsLog.event(
            "load_control min=${lc.minBufferMs} max=${lc.maxBufferMs} start=${lc.bufferForPlaybackMs} " +
                "restart=${lc.bufferForPlaybackAfterRebufferMs} preroll=${effectivePrerollSecs()}s " +
                "latencySec=${liveBufferSecs ?: -1}",
        )
        // Also to Logcat unconditionally: the diagnostics log is off in a release build, and these numbers
        // are the only way to tell "the setting didn't apply" from "the buffer filled that fast".
        android.util.Log.i(
            LiveDiagnosticsLog.TAG,
            "live_buffer preroll=${effectivePrerollSecs()}s start=${lc.bufferForPlaybackMs}ms " +
                "min=${lc.minBufferMs}ms max=${lc.maxBufferMs}ms latency=${liveBufferSecs ?: -1}",
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                lc.minBufferMs,
                lc.maxBufferMs,
                lc.bufferForPlaybackMs,
                lc.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(LiveBuffer.targetBufferBytes(effectiveLiveBufferSecs(), effectivePrerollSecs(), defaultBytes))
            .build()
        // Decode-path config is shared with the other ExoPlayer engines — see [ownTVRenderers].
        // The audio sink is pinned to stereo PCM when the user asked for "Stereo only" or when the
        // session latch has tripped. This is the half of the surround setting that never existed:
        // the old boolean only reached mpv, while Live TV's default engine is this one, so a TV that
        // mis-plays Dolby got exactly the same treatment however the switch was set.
        val renderers = ownTVRenderers(
            context,
            forceStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode),
            softwareFirst = !hwDecodingEnabled,
        )
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFor(currentUa)))
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Media3's default ONLY_IF_SEAMLESS still issues Surface.setFrameRate() requests. Some
                // vendor stacks advertise a seamless switch but visibly re-handshake HDMI, so Off must
                // explicitly disable this second AFR mechanism as well as FrameRateController.
                setVideoChangeFrameRateStrategy(
                    if (autoFrameRateEnabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                )
                addListener(listener); addAnalyticsListener(analytics); addAnalyticsListener(audioWatchdog)
                // Wire the per-frame tick to the video renderer so the health watchdog can tell a frozen
                // PICTURE (clock still running — invisible to a position-only check) from real playback.
                // Best-effort: if the renderer isn't found / doesn't accept the message, the position and
                // error/stall watchdogs still cover total freezes, so this can't make things worse.
                videoRenderer = (0 until rendererCount).map { getRenderer(it) }
                    .firstOrNull { it.trackType == C.TRACK_TYPE_VIDEO }
                if (videoRenderer == null) {
                    android.util.Log.w(LiveDiagnosticsLog.TAG, "frame hook NOT wired — no video renderer found; picture-freeze detection falls back to the no-progress backstop")
                }
                videoRenderer?.let { r ->
                    runCatching {
                        createMessage(r)
                            .setType(MediaCodecVideoRenderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER)
                            .setPayload(frameListener)
                            .send()
                    }.onFailure {
                        android.util.Log.w(LiveDiagnosticsLog.TAG, "frame hook send FAILED (${it.message}) — picture-freeze detection falls back to the no-progress backstop")
                    }
                }
            }
    }

    companion object {
        private const val MAX_VOLUME = VolumeBoost.MAX_VOLUME // same ceiling as mpv; 100–150 comes from LoudnessEnhancer
        private const val MAX_RECONNECTS = 8        // ~consecutive failures before giving up (HUD Retry then)
        /** Playback must hold this long before the reconnect ladder is considered recovered. */
        internal const val HEALTHY_MS = 60_000L

        /**
         * The reconnect backoff ladder, in milliseconds. The old rule — `1500 * n` capped at 4 s — hammered
         * a dead feed eight times inside ~26 s and gave up, so a router reboot or a provider restart that
         * takes a minute always ended in "Lost connection". These steps span the ladder over ~35 s and
         * then hold at the last one, which comfortably outlives a typical blip.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 10_000L, 15_000L)

        /**
         * Delay before reconnect attempt [attempt] (1-based, as [TuneState.retryCount] is post-increment). Attempts
         * past the ladder repeat its final step. Pure, so the schedule is unit-testable.
         */
        internal fun reconnectDelayMs(attempt: Int): Long =
            RECONNECT_DELAYS_MS[(attempt - 1).coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]

        /** HLS already performs request-level retries. Once a forbidden segment becomes fatal, fetch a
         * fresh manifest promptly instead of adding the generic outage ladder's 3–15 second UI freeze. */
        internal fun hlsHttpReconnectDelayMs(attempt: Int): Long =
            reconnectDelayMs(attempt).coerceAtMost(HLS_HTTP_RECONNECT_MAX_MS)

        internal fun isHlsResponse(url: String, contentType: String?): Boolean {
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            return type == "application/x-mpegurl" || type == "application/vnd.apple.mpegurl" ||
                url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        }

        /** A segment URL a live playlist has already refused does not become valid by asking again at
         *  the same URL — with a signed, expiring token it provably never can. One quick retry covers a
         *  genuine blip, then let it go fatal so the mpv handoff can act instead of burning ~8 s on the
         *  stock ladder while the buffer drains. */
        internal fun edgeRefusalRetryDelayMs(errorCount: Int): Long =
            if (errorCount <= 1) EDGE_REFUSAL_RETRY_MS else C.TIME_UNSET

        private const val HLS_HTTP_RECONNECT_MAX_MS = 1_500L
        internal const val EDGE_REFUSAL_RETRY_MS = 500L

        /** "Too many requests" — a panel deferring the channel, not refusing it (see
         *  [maybeBackOffForProvider]). */
        internal const val HTTP_TOO_MANY_REQUESTS = 429

        /** Ceiling on a `Retry-After` we will actually sit through. Past a minute a countdown reads as a
         *  hang, and the honest error screen (with its Retry button) serves the user better. */
        internal const val MAX_RETRY_AFTER_SECS = 60

        /** Automatic 429 waits per tune, so a panel whose slot never frees can't keep the channel spinning
         *  indefinitely. Five covers the real case — one stream released a few seconds late — many times over. */
        internal const val MAX_PROVIDER_BACKOFFS = 5

        /** Below this a provider's "first sentence" is a fragment ("Sorry.", "Error."), not an explanation,
         *  so the whole message is kept instead. */
        private const val MIN_PROVIDER_SENTENCE = 12

        /** How far down a cause chain to read when deciding a decoder-init failure's real reason. */
        private const val MAX_CAUSE_DEPTH = 6

        /** Vendor wordings for "there is no decoder instance left on this device". */
        private val DECODER_EXHAUSTED_PHRASES = listOf(
            "no more instances",
            "insufficient resource",
            "insufficientresources",
            "error 0xfffffff4",
            "reclaim",
        )

        /** The diagnostics line behind [PlaybackFailure.DecoderExhausted]; never shown to the user. */
        internal const val DECODER_EXHAUSTED_REASON = "decoder exhausted: no MediaCodec instance available"

        /**
         * Seconds named by a numeric `Retry-After`, or null when the header is absent, an HTTP-date, or
         * nonsense. `0` means "ask again now", which is one tick of the countdown here. Pure, so the rule
         * is unit-testable.
         */
        internal fun retryAfterSecs(header: String?): Int? =
            header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.coerceIn(1, MAX_RETRY_AFTER_SECS)

        /** How long to wait for a single-session panel to notice the other engine's socket is gone.
         *
         *  MEASURED on the traced panel: the handoff's own ~500 ms was never enough, and the refusal
         *  persisted for the whole time mpv stayed connected — so this covers the release, not a poll.
         *  Unlike the other handoff waits this one is not about local hardware at all; it is about how
         *  long a *provider* takes to free a session, which is why it is four times the longest of them. */
        internal const val SESSION_RELEASE_MS = 2_000L

        // --- LoadControl (see [build]) ----------------------------------------------------------
        /** Resume reading the socket once the buffer drains to this. */
        private const val MIN_BUFFER_MS = 8_000
        /** Stop reading at this. Only [MAX_BUFFER_MS] − [MIN_BUFFER_MS] above the resume point, so a raw-TS
         *  socket is never parked long enough for a provider to cull it. */
        private const val MAX_BUFFER_MS = 10_000
        /** Binds below [MIN_BUFFER_MS] on a UHD stream (≈7s at 25 Mbps) → a continuous read there, and a
         *  hard bound on what a 4K channel pins on the app heap. */
        private const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024
        /** TV-class/low-RAM devices: still above ExoPlayer's ~13 MB video default. */
        private const val LOW_RAM_TARGET_BYTES = 16 * 1024 * 1024

        /** Grace for the old MediaCodec to tear down before its replacement is built (see [rebuildDecoderAndRetry]).
         *
         *  ESTIMATED, and deliberately the same beat as `OwnTVPlayer.SURFACE_HANDOFF_MS` — it is the
         *  same physical event (one decoder releasing before another claims it), just within a single
         *  engine rather than across two. If the measured value there ever moves, this should follow. */
        private const val DECODER_REBUILD_DELAY_MS = 500L

        private const val STALL_MS = 12_000L        // buffering this long after playing == a dropped feed
        private const val PROGRESS_CHECK_MS = 2_500L // poll interval for the silent-freeze watchdog
        private const val FROZEN_LIMIT = 3          // picture frozen this many polls (~7.5s) == a dropped feed
        private const val FREEZE_TIMEOUT_MS = 8_000L // zero forward progress this long while READY == dead feed

        /**
         * How long this engine takes to decide that a stream which HAS played is gone, and to have made
         * its first reconnect attempt.
         *
         * The buffering stall dominates the two freeze checks (12s against 8s and 3 × 2.5s), and the
         * first reconnect follows 1.5s after the verdict — so 13.5s in, the engine has already declared
         * the feed dead once and tried again.
         *
         * Public because Live TV's handoff deadline is DERIVED from it rather than guessed: those two
         * numbers drifted to 30s against 12s, so the ViewModel sat waiting through two of the engine's
         * own reconnects before offering the channel to the other player.
         */
        val DEATH_VERDICT_MS: Long =
            maxOf(STALL_MS, FREEZE_TIMEOUT_MS, FROZEN_LIMIT * PROGRESS_CHECK_MS) + reconnectDelayMs(1)
        // Video track present, zero frames rendered this long == "audio plays, no picture". Measured
        // from STATE_READY, not from the load, so it is a later starting point than the 12 s the
        // load-armed engines use — see [NoFrameWatchdog] for the full comparison.
        private const val NO_VIDEO_TIMEOUT_MS = 8_000L
        private const val AUDIO_ONLY_CONFIRM_MS = 5_000L // allow late video-track discovery before showing radio badge
        // Re-buffer flap (see [noteRebufferFlap]): this many re-buffers inside the window while the
        // position crawls == the stream is oscillating, not playing. The traced case managed ~8 per
        // second, so it trips about two seconds in; a merely choppy channel re-buffers a handful of
        // times a minute and clears the window long before reaching the limit.
        private const val FLAP_WINDOW_MS = 6_000L
        private const val FLAP_LIMIT = 12
        private const val FLAP_MIN_PROGRESS_MS = 3_000L // real playback inside the window == choppy, not flapping
        // "Buffered but not playing" (see [openWatchdog] branch 1). Four consecutive polls == four seconds
        // of a satisfied load control with no frame, which no healthy channel produces: once enough is
        // buffered to start, ExoPlayer starts within a poll or two even on a slow decoder handshake.
        private const val OPEN_STUCK_POLLS = 4
        /** How long an HLS rendition may wait to be timestamp-aligned with the primary one before the
         *  load fails instead of hanging (Media3's default here is "wait forever"). Generous enough for a
         *  slow first segment on a 4K feed, short enough that the TS/mpv fallbacks still feel prompt. */
        private const val HLS_TIMESTAMP_ALIGN_TIMEOUT_MS = 8_000L
        // Pre-roll reachability (see [openWatchdog]). One poll a second, and three flat polls in a row
        // means the buffer has hit the stream's ceiling — a live source that is still filling adds roughly
        // a second of media per second even when it is pinned to the live edge, so 400 ms of growth is a
        // deliberately generous "still moving". The grace is the backstop for a stream that dribbles.
        private const val PREROLL_POLL_MS = 1_000L
        private const val PREROLL_MIN_GROWTH_MS = 400L
        private const val PREROLL_STUCK_POLLS = 3
        private const val PREROLL_OPEN_GRACE_MS = 5_000L
        private const val FPS_BASELINE_MS = 500L
        private const val FPS_TICK_MS = 1_000L
        private const val FPS_MAX_ATTEMPTS = 5
    }
}

/**
 * Everything about **one tune** that must be forgotten when the next channel starts.
 *
 * This exists because the alternative had already been written by hand: a 40-line prologue in
 * [LivePreviewEngine.play] clearing three dozen separate fields, where a field added later and not
 * added to that list would silently bleed from one channel into the next — a stale error, a stale
 * resolution badge, a retry budget the new channel had not spent. Grouping them means the reset is a
 * single assignment (`tune = TuneState()`) and cannot be partially forgotten.
 *
 * **Membership is the whole design.** A field belongs here if a NEW channel must not see the previous
 * channel's value. Fields that legitimately survive a tune stay in the engine, where being absent from
 * this class documents that they are deliberate: the request identity (`tunedUserAgent` and friends,
 * re-assigned unconditionally on every tune), `lastTunedUrl` (whose whole job is to be compared with
 * the *next* url), the learned per-provider quirks (session-scoped by design), the StateFlows the UI
 * collects (they must keep their identity, so they are reset by value in [LivePreviewEngine.play]),
 * and the helper objects with their own reset (`throughputTracker`, `fpsSample`, `audioWatchdog`).
 *
 * Mutable `var`s rather than an immutable snapshot, deliberately: these are written from the playback
 * thread, the frame-release thread and the main thread as they always were, and copy-on-write would
 * turn every one of those writes into a lost-update race. The six that were `@Volatile` before keep
 * that guarantee individually.
 */
/** One selectable audio / text track, as the Media3 track group plus the index inside it. */
internal data class AudioSel(val id: Int, val group: androidx.media3.common.TrackGroup, val trackIndex: Int)
internal data class TextSel(val id: Int, val group: androidx.media3.common.TrackGroup, val trackIndex: Int)

internal data class TuneState(
    /** Set just before our own stop()/release() touches the player, so the STATE_IDLE that follows is
     *  recognized as a clean, self-caused cancellation rather than an unexpected mid-live drop. */
    var stoppingIntentionally: Boolean = false,
    /** Programmatic codec/audio errors (more reliable than logcat for ExoPlayer, and survives the
     *  Android 14+ own-logcat lockdown). `MediaCodec.CodecException.diagnosticInfo` carries the exact
     *  code (e.g. 0x80001000); AudioSink errors name the audio failure. Preferred when present. */
    @field:Volatile var lastCodecError: String? = null,
    /** e.g. "OMX.realtek.video.decoder", for the spec line. */
    @field:Volatile var lastVideoDecoder: String? = null,
    /** Whether that decoder is hardware, per `DecoderNames` — null until one initialises, or when the
     *  name can't be classified. NOT the same thing as the Hardware decoding setting: renderer decoder
     *  fallback can quietly land a channel on software while the setting still reads on. */
    @field:Volatile var lastVideoDecoderHardware: Boolean? = null,
    var hasPlayed: Boolean = false,
    var retryCount: Int = 0,
    /** One decoder rebuild+retry per load — see [LivePreviewEngine.rebuildDecoderAndRetry]. */
    var decoderRetryDone: Boolean = false,
    /** A single failed prepare() fires both onPlayerError AND the STATE_IDLE that follows it — without
     *  this guard each one called reconnect() independently and burned two [retryCount] slots for one
     *  real failure. Set true while a reconnect's delayed re-prepare is scheduled/running; cleared right
     *  before that prepare() call so the NEXT genuine failure is free to trigger its own reconnect. */
    var reconnectPending: Boolean = false,
    /** Set true once [retryCount] is exhausted and the terminal error has been surfaced; stops the stall
     *  watchdog re-arming and stops error/IDLE calling reconnect() again until a fresh play()/retry(). */
    var gaveUp: Boolean = false,
    // --- pre-roll that the stream can't satisfy ---
    var flapWindowStartMs: Long = 0L,
    var flapCount: Int = 0,
    var flapWindowStartPos: Long = 0L,
    /** A no-pre-roll reopen is queued; ignore further flapping until it lands. */
    var prerollRetunePending: Boolean = false,
    /** The top-level request ended at an HLS manifest even though the submitted URL looked like raw TS. */
    @field:Volatile var responseWasHls: Boolean = false,
    var forceHlsForCurrentLoad: Boolean = false,
    var redirectedHlsRetryDone: Boolean = false,
    /** The playlist shape is logged once per prepare (and again whenever we back off). */
    var playlistLogged: Boolean = false,
    /** This load was refused because the account's one session is still held (HTTP 458), and whether the
     *  single wait-and-retry that answers it has already been spent. */
    var sessionLimitSeen: Boolean = false,
    var sessionLimitRetryDone: Boolean = false,
    /** Whether this load has already spent its one retry under `HttpClient.FALLBACK_USER_AGENT`. */
    var uaRetryDone: Boolean = false,
    /** Whether this load has already tried the channel's `.ts`⇄`.m3u8` sibling. */
    var altFormatRetryDone: Boolean = false,
    /** Automatic 429 waits already spent on this tune. */
    var providerBackOffs: Int = 0,
    // Audio/text tracks enumerated from the active stream (multi-language live, or a VOD file via M3U).
    var audioTrackList: List<TrackOption> = emptyList(),
    var audioSelections: List<AudioSel> = emptyList(),
    var textTrackList: List<TrackOption> = emptyList(),
    var textSelections: List<TextSel> = emptyList(),
    var noVideoTriggered: Boolean = false,
    var readySinceMs: Long = 0L,
    var hasAudioTrack: Boolean = false,
    /** Assumed true until this stream's own track list says otherwise — an audio-only channel is the
     *  exception, and assuming "no video" would arm the no-picture watchdog against every stream. */
    var hasVideoTrack: Boolean = true,
    var lastFrameCount: Int = 0,
    /** Latched true once ANY rendered frame has been seen this load. Frame-based freeze detection only
     *  fires AFTER this — so if the per-frame hook silently failed to register (or a stream renders no
     *  video at all), healthy playback never false-triggers a reconnect; the position check covers it. */
    var everRendered: Boolean = false,
    var lastProgressPos: Long = -1L,
    /** `SystemClock.elapsedRealtime()` of the last forward position move. */
    var lastProgressWallMs: Long = 0L,
    var frozenChecks: Int = 0,
    /** elapsedRealtime of the last play() call, and whether its first frame has been timed. */
    @field:Volatile var playStartedMs: Long = 0L,
    @field:Volatile var firstFrameLogged: Boolean = false,
)
