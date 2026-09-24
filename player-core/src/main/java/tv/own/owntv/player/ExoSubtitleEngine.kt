package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.drm.toMediaDrmConfiguration
import tv.own.owntv.core.network.StreamHeaders
import java.util.Locale
import tv.own.owntv.core.player.PlayerBudget
import tv.own.owntv.core.player.SurroundMode

/**
 * ExoPlayer (Media3) for VOD, in three roles: a VOD with an **image-based** subtitle (PGS/VOBSUB/DVB)
 * selected — the case mpv's direct path can't handle — the VOD engine fallback below, and the primary
 * engine when the user chose ExoPlayer for Movies/Series. ExoPlayer keeps the video on the same zero-copy
 * decoder→SurfaceView path *and* renders the bitmap subtitle on its own UI layer ([Cue]s → SubtitleView),
 * which mpv can't do without GL-compositing the whole 4K frame (the heavy path we removed).
 *
 * It is a **handoff**, not a sidecar: mpv is stopped first, so the provider only ever sees one connection
 * (IPTV panels routinely cap VOD at one). [OwnTVPlayer] owns this and mirrors its state into the same
 * StateFlows the HUD already observes, so the player UI is unchanged. All methods run on the main thread.
 *
 * It also serves as the **VOD engine fallback**: when mpv terminally fails to play a VOD (demuxer reject,
 * decoder stall, retry budget exhausted), [OwnTVPlayer] retries the same item here once ([start] with
 * `fallback = true`) before surfacing an error — some devices/streams play on ExoPlayer's MediaCodec
 * path where mpv's can't. In fallback mode no subtitle is auto-selected and errors are worded as
 * engine failures rather than subtitle failures.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoSubtitleEngine(
    private val context: Context,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    private val budget: PlayerBudget,
    private val callbacks: Callbacks,
    /** [OwnTVPlayer]'s A/V-sync offset, so a film on this engine honours it like mpv does. */
    private val audioDelay: AudioDelayClock,
) {
    /** Hooks back into [OwnTVPlayer]'s StateFlows (all fire on the main thread). */
    interface Callbacks {
        fun onPlayingChanged(playing: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onVideoSize(width: Int, height: Int)
        fun onPositionDuration(positionMs: Long, durationMs: Long, bufferedMs: Long)
        fun onFirstFrame()
        fun onCues(cues: List<Cue>)
        fun onAudioTracks(tracks: List<TrackOption>)
        /** Text/image subtitle tracks from the active file — [OwnTVPlayer] shows these in the HUD while
         *  this engine owns playback as a VOD engine (mpv never probed the file, so its list is empty). */
        fun onTextTracks(tracks: List<TrackOption>)
        /** Whether any subtitle track (text or image) is selected — however it came to be: a pick in the
         *  HUD, the image-subtitle handoff, remembered tracks, or ExoPlayer's own default/forced choice. */
        fun onSubtitleSelected(selected: Boolean) {}
        fun onVideoFps(fps: Float)
        /** This file declares no video track at all (music-only VOD). The HUD says so on screen — sound
         *  over black is otherwise indistinguishable from a broken player. */
        fun onAudioOnlyMedia(audioOnly: Boolean) {}
        fun onError(failure: PlaybackFailure)
        /** Playback reached the end of the file (drives VOD auto-play-next while this engine is active). */
        fun onEnded()
    }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var pendingSubLang: String? = null
    private var pendingSubTypeIndex: Int = -1
    private var subtitleApplied = false
    // Side-loaded external subtitle files (OpenSubtitles/local — subtitle plan §6.5/§10). ExoPlayer
    // can't attach a subtitle to a playing item, so each add/restore re-prepares the same URL with
    // SubtitleConfigurations at the current position (the plan's "seamless re-prepare").
    private data class ExternalSubCfg(
        val path: String,
        val title: String,
        val lang: String?,
        val source: ExternalSubtitleSource,
    )
    private var currentUrl: String? = null
    private val externalSubs = ArrayList<ExternalSubCfg>()
    // Label of a just-added external sub to select once its track appears in onTracksChanged.
    private var pendingExternalLabel: String? = null
    // Subtitle-timing offset for the active external sub (§8): applied by side-loading a
    // timestamp-shifted copy of that file on the next (re-)prepare.
    private var subDelayMs = 0
    private var delayLabel: String? = null
    // X2: shifted copies, keyed by source path + offset. Generating one reads, re-times and rewrites
    // the whole subtitle file — on a big ASS that is tens to hundreds of milliseconds, and
    // buildMediaItem runs on the main thread on every (re-)prepare, so it used to freeze the HUD on
    // every delay nudge. The work now happens on IO once per (file, offset) and the re-prepare waits
    // for it; returning to a previous offset is a map lookup.
    private val shiftedSubs = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
    private val shiftScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private var shiftJob: kotlinx.coroutines.Job? = null

    private fun shiftKey(path: String, offsetMs: Int) = "$path|$offsetMs"
    // Engine-fallback playback (mpv terminally failed this VOD): no arbitrary subtitle; a configured
    // preferred language is still honoured. Errors remain engine-worded.
    private var fallbackMode = false
    // First-frame watchdog: this handoff only exists to show an image subtitle over otherwise-healthy
    // video, so if ExoPlayer never renders a frame (a format/decoder combo mpv handled fine but this
    // renderer can't), fall back rather than leaving the user on audio with a blank screen.
    private var firstFrameSeen = false
    // X1: whether this file declares a video track at all. Radio stations filed under Movies, music
    // VOD and audio-only catch-up recordings have none — nothing is broken there and the watchdog
    // below must stay quiet. Assumed true until onTracksChanged says otherwise, so a file whose
    // tracks never arrive is still covered. Mirrors LivePreviewEngine's `hasVideo` gate.
    private var hasVideoTrack = true
    // Audio Mode: the video track is deselected, so no frame can ever arrive — see [setVideoTrackDisabled].
    @Volatile private var audioOnly = false
    private val noVideoWatchdog = NoFrameWatchdog {
        if (!firstFrameSeen && hasVideoTrack && !audioOnly) {
            android.util.Log.w(
                TAG,
                "no video frame after ${noVideoTimeoutMs()}ms — falling back " +
                    "(decodedDrops=${currentDroppedFrames(player) - dropsBaseline} format=${player?.videoFormat?.sampleMimeType})",
            )
            // One software retry before giving up on this engine. "Audio plays, no frame ever renders"
            // is precisely what a hardware decoder does when it accepts a format it can't actually
            // handle — and a software decoder usually can. For a catch-up archive it is also the
            // mid-GOP signature (a software decoder picks up at the next keyframe), so the panel is
            // remembered and the session's remaining archives open in software straight away.
            val url = currentUrl
            if (softwareRungAvailable() && url != null) {
                if (isArchiveItem) LiveStreamQuirks.rememberArchiveNeedsSoftware(url)
                android.util.Log.w(TAG, "software rescue: no frame on the hardware decoder, restarting in software decode")
                onSoftwareRescue?.invoke(url, isArchiveItem)
                return@NoFrameWatchdog
            }
            callbacks.onError(PlaybackFailure.AudioNoVideo)
        }
    }

    /**
     * Whether this item can still be retried on a software decoder in THIS engine.
     *
     * Already on software → the rung is spent (and `start` sets `softwarePreferred` for the retry, so
     * this is also what stops it looping). Above 1080p → refused, exactly as mpv's
     * `canTrySoftwareRescue` refuses it: software-decoding 4K on TV silicon is a slideshow, not a
     * rescue, and the time is better spent handing the item to the other engine. An unknown height is
     * allowed — by the time a decoder fails the format is normally known, and the no-frame watchdog
     * still catches a bad guess.
     */
    private fun softwareRungAvailable(): Boolean {
        if (softwarePreferred) return false
        val height = player?.videoFormat?.height ?: 0
        if (height > 1080) {
            android.util.Log.i(TAG, "software rescue declined: ${height}p is above the software-decode ceiling")
            return false
        }
        return true
    }

    /** How long to wait for the first frame. A catch-up archive gets far longer: it decodes in SOFTWARE
     *  and starts mid-GOP, so the decoder must chew through inter-frames until the next keyframe before
     *  it can render anything — on a low-spec box that can comfortably exceed the normal 8 s budget. */
    private fun noVideoTimeoutMs(): Long =
        if (softwarePreferred) NO_VIDEO_TIMEOUT_SOFTWARE_MS else NO_VIDEO_TIMEOUT_MS
    // Maps the audio-track id the HUD selects (== its ordinal in the list we publish) → the ExoPlayer
    // track group + index to override. Rebuilt whenever the track list changes.
    private var audioSelections: List<AudioSel> = emptyList()

    private data class AudioSel(val id: Int, val group: TrackGroup, val trackIndex: Int)

    val isActive: Boolean get() = player != null

    private val throughputTracker = ThroughputTracker()
    private val fpsSample = FpsSample()
    private var dropsBaseline = 0
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            // Which decoder actually won the selector, and how long it took to come up. The name is the
            // only reliable way to tell a software decoder (c2.android.* / OMX.google.*) from the vendor
            // hardware one, and mid-GOP archives hinge on getting the former.
            // `softwarePreferred` is what this engine ASKED for; [DecoderNames] is what it got. Decoder
            // fallback can put a software decoder behind a hardware request without reporting anything.
            val hardware = DecoderNames.isHardware(decoderName)
            android.util.Log.i(
                TAG,
                "video decoder: $decoderName (init ${initializationDurationMs}ms, " +
                    "${when (hardware) { true -> "hardware"; false -> "software"; null -> "kind unknown" }}" +
                    ", requested=${if (softwarePreferred) "software" else "hardware"})",
            )
            dropsBaseline = currentDroppedFrames(player) // a new decoder session may start its own counters
        }

        override fun onVideoInputFormatChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            android.util.Log.i(TAG, "video input format: ${format.sampleMimeType} ${format.width}x${format.height} fps=${format.frameRate}")
        }
    }

    /** Declared fps if present, else a live measurement. */
    fun currentFps(): Float? {
        val p = player ?: return null
        return p.videoFormat?.frameRate?.takeIf { it > 0 } ?: fpsSample.sample(p)
    }

    /** Declared bitrate in Mbps for the top-bar chip, or null when the provider didn't set one (raw
     *  MPEG-TS). Declared-only by design: measuring live throughput on every playback drags 4K, so the
     *  debug overlay is the sole place a measured value is shown (and only while it's open). */
    fun currentBitrateMbps(): Double? =
        player?.videoFormat?.bitrate?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            callbacks.onPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            callbacks.onBuffering(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) emitPositionDuration()
            if (playbackState == Player.STATE_ENDED) callbacks.onEnded()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                callbacks.onVideoSize(videoSize.width, videoSize.height)
            }
            // Report the video frame rate so the display can switch to match it (kills 24fps-on-60Hz judder).
            player?.videoFormat?.frameRate?.let { if (it > 0f) callbacks.onVideoFps(it) }
        }

        override fun onRenderedFirstFrame() {
            firstFrameSeen = true
            noVideoWatchdog.disarm()
            callbacks.onFirstFrame()
        }

        override fun onCues(cueGroup: CueGroup) {
            callbacks.onCues(cueGroup.cues)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateVideoTrackPresence(tracks)
            rebuildAudioTracks(tracks)
            rebuildTextTracks(tracks)
            applyPendingSubtitle(tracks)
            callbacks.onSubtitleSelected(tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            // A decode-class failure gets this engine's software rung before playback leaves it. The
            // old behaviour handed EVERY Exo error straight to mpv, so a file the software decoder
            // could play was thrown at the other engine to discover that — the ladder is now
            // Exo hardware → Exo software → mpv hardware → mpv copy → mpv software.
            // Network / source / DRM errors are NOT retried here: a different decoder cannot fix them,
            // and mpv (with its own retry ladder and different HTTP stack) is the better next step.
            val url = currentUrl
            // N18 — a film whose connection dropped after it had started is reopened where it stopped,
            // on this engine, within Settings → Reconnect attempts: the same budget and the same
            // "a minute of playback earns it back" rule as mpv. Before, the drop went straight to mpv
            // or, with "ExoPlayer only", to an error. `prepare()` retries the same item at the same
            // position. A catch-up archive is exempt, as on mpv: without Range support it cannot reopen
            // at an offset. Only when the budget is spent does the path below run, unchanged.
            val p = player
            val pos = p?.currentPosition ?: 0L
            val next = if (error.errorCode in NETWORK_DROP_CODES && !isArchiveItem && pos > 0L) {
                FilmNetwork.nextReconnect(reconnectsUsed, reconnectAtMs, pos, filmReconnects)
            } else {
                null
            }
            if (p != null && url != null && next != null) {
                reconnectsUsed = next
                reconnectAtMs = pos
                android.util.Log.w(TAG, "film connection dropped at ${pos}ms — reopening in place ($next/$filmReconnects)")
                p.prepare()
                return
            }
            if (error.errorCode in DECODE_ERROR_CODES && softwareRungAvailable() && url != null) {
                android.util.Log.w(TAG, "software rescue: ${error.errorCodeName} on the hardware decoder, restarting in software decode")
                onSoftwareRescue?.invoke(url, isArchiveItem)
                return
            }
            callbacks.onError(friendlyFailure(error))
        }
    }

    /** Build (if needed) and start playback of [url] at [positionMs] on [surface], selecting the image
     *  subtitle identified by [subLang]/[subTypeIndex] (the track the user picked in mpv's list).
     *  [fallback] = engine-fallback playback after a terminal mpv failure: no subtitle is auto-selected
     *  (pass subLang = null, subTypeIndex = -1) and errors are worded as engine failures. */
    fun start(
        url: String, positionMs: Long, surface: Surface, subLang: String?, subTypeIndex: Int,
        fallback: Boolean = false,
        // External subtitle files to side-load from the first prepare (engine toggle carry-over, §10);
        // [selectExternalLabel] names the one to select, or null to attach them all unselected.
        sideloadSubs: List<OwnTVPlayer.ExternalSub> = emptyList(),
        selectExternalLabel: String? = null,
        /** Decode this item on a software decoder — see [ownTVRenderers]. */
        preferSoftware: Boolean = false,
        /** This item is a catch-up / live-rewind archive: mid-GOP, so a blank picture on the hardware
         *  decoder is rescued in software instead of erroring — see [noVideoWatchdog]. */
        isArchive: Boolean = false,
    ) {
        // "Hardware decoding = Off" used to reach mpv only, so a user who turned it off to work around a
        // broken vendor decoder still got that decoder the moment playback landed on ExoPlayer. The
        // selector puts software first and keeps hardware as a backstop, so this can only add a route.
        softwarePreferred = preferSoftware || !hwDecodingEnabled
        isArchiveItem = isArchive
        this.surface = surface
        fallbackMode = fallback
        pendingSubLang = subLang
        pendingSubTypeIndex = subTypeIndex
        subtitleApplied = false
        firstFrameSeen = false
        hasVideoTrack = true
        throughputTracker.reset(); fpsSample.resetAll(); dropsBaseline = currentDroppedFrames(player)
        audioWatchdog.reset()
        noVideoWatchdog.disarm()
        // Nothing to wait for while Audio Mode is on — the video track is deselected on purpose.
        if (!audioOnly) noVideoWatchdog.arm(noVideoTimeoutMs())

        // Applied per item, before prepare: the factory is created once but each load builds a fresh
        // data source from it, so a changed UA/header set takes effect without rebuilding the player.
        applyRequestHeaders()

        currentUrl = url
        reconnectsUsed = 0
        reconnectAtMs = 0L
        externalSubs.clear()
        sideloadSubs.forEach { externalSubs.add(ExternalSubCfg(it.path, it.title, it.lang, it.source)) }
        pendingExternalLabel = selectExternalLabel
        subDelayMs = 0
        delayLabel = null

        // The renderer factory (and so the decoder selector) is baked in at construction: if this item
        // wants the other decode path than the cached player was built for, drop and rebuild it.
        // The audio sink's capabilities are baked in at construction too, so the same rule applies: a
        // cached player built before the session latched to stereo would keep the sink that failed.
        val wantStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode)
        val wantNetwork = filmBufferSecs to filmTimeoutSecs
        if (player != null && (builtForSoftware != softwarePreferred || builtForStereo != wantStereo || builtForNetwork != wantNetwork)) {
            android.util.Log.i(TAG, "rebuilding ExoPlayer for ${if (softwarePreferred) "software" else "hardware"} decode, ${if (wantStereo) "stereo" else "device"} audio")
            player?.release()
            boost.release() // bound to the outgoing player's audio session
            player = null
        }
        val p = player ?: build().also { player = it; builtForSoftware = softwarePreferred; builtForStereo = wantStereo; builtForNetwork = wantNetwork }
        // Both are plain setters, so a cached player picks up a setting changed since it was built —
        // no rebuild needed for either (unlike the renderer factory and the audio sink above).
        p.setVideoChangeFrameRateStrategy(
            if (autoFrameRateEnabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
        )
        applyLanguagePrefs()
        setVideoTrackDisabled(audioOnly) // survives a player rebuild while Audio Mode is on
        p.setVideoSurface(surface)
        // Start a resumed item at its target in the initial prepare. Preparing at zero and seeking
        // immediately afterwards tears down a just-created passthrough sink on some TCL/Realtek TVs;
        // the replacement E-AC3 output can then stay silent until the watchdog demotes the session.
        p.setMediaItem(buildMediaItem(url), positionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
    }

    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        // #115 — a protected item. Single-session: a film's content key does not rotate, unlike a live
        // channel's, so there is nothing to renew mid-playback.
        drmConfig?.let { builder.setDrmConfiguration(it.toMediaDrmConfiguration(multiSession = false)) }
        // v43 — naming the mime type is what makes this player's DefaultMediaSourceFactory build a
        // DashMediaSource instead of sniffing an XML manifest with the progressive extractor. Only for
        // a container we can actually route: `ism` has no Media3 module here, so it is left to infer.
        when (manifestType) {
            tv.own.owntv.core.player.ManifestType.MPD ->
                builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
            tv.own.owntv.core.player.ManifestType.HLS ->
                builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            tv.own.owntv.core.player.ManifestType.ISM, null -> Unit
        }
        if (externalSubs.isNotEmpty()) {
            builder.setSubtitleConfigurations(externalSubs.map { s ->
                // Timing offset for the active external sub: side-load a timestamp-shifted copy (§8).
                // X2 — never generate it here (main thread); setSubtitleDelayMs prepares the copy off
                // the main thread and only re-prepares once it is in the cache. The original is the
                // fallback, which is also what shiftedCopy itself returns on a parse/IO failure.
                val file = if (s.title == delayLabel && subDelayMs != 0) {
                    shiftedSubs[shiftKey(s.path, subDelayMs)] ?: java.io.File(s.path)
                } else java.io.File(s.path)
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setMimeType(subtitleMime(s.path))
                    .setLabel(s.title)
                    .apply { s.lang?.let { setLanguage(it) } }
                    .build()
            })
        }
        return builder.build()
    }

    private fun subtitleMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    }

    /** Attach + select an external subtitle file (plan §6.5): re-prepare the same URL with the sub
     *  side-loaded, at the same position, and select it by label once its track appears. */
    fun addExternalSubtitle(
        path: String,
        title: String,
        lang: String?,
        source: ExternalSubtitleSource = ExternalSubtitleSource.OPENSUBTITLES,
    ) {
        val p = player ?: return
        val url = currentUrl ?: return
        if (externalSubs.none { it.path == path }) externalSubs.add(ExternalSubCfg(path, title, lang, source))
        pendingExternalLabel = title
        subtitleApplied = false
        reprepareKeepingPosition(p, url)
    }

    /** Re-attach previously downloaded subtitles WITHOUT changing the current selection (plan §9 —
     *  they show in the Subtitles list; the user re-picks). No-op when nothing new to attach. */
    fun restoreExternalSubtitles(subs: List<OwnTVPlayer.ExternalSub>) {
        val p = player ?: return
        val url = currentUrl ?: return
        var added = false
        subs.forEach { s ->
            if (externalSubs.none { it.path == s.path }) {
                externalSubs.add(ExternalSubCfg(s.path, s.title, s.lang, s.source)); added = true
            }
        }
        if (!added) return
        // Capture the currently-selected text track (by ordinal) so applyPendingSubtitle re-applies it
        // after the re-prepare — a TrackSelectionOverride holds TrackGroup instances, which the new
        // prepare replaces, so without this the default selector could auto-pick a sub the user never chose.
        var idx = 0; var selIdx = -1; var selLang: String? = null
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) { selIdx = idx; selLang = group.getTrackFormat(i).language }
                idx++
            }
        }
        pendingSubTypeIndex = selIdx // -1 + null lang → applyPendingSubtitle keeps text OFF
        pendingSubLang = if (selIdx >= 0) selLang else null
        pendingExternalLabel = null
        subtitleApplied = false
        reprepareKeepingPosition(p, url)
    }

    /** Apply a subtitle-timing offset to the active external sub [activeLabel] (§8): re-prepare with a
     *  shifted copy of its file at the same position, then re-select it. The caller debounces. */
    fun setSubtitleDelayMs(ms: Int, activeLabel: String) {
        val p = player ?: return
        val url = currentUrl ?: return
        if (subDelayMs == ms && delayLabel == activeLabel) return
        val source = externalSubs.firstOrNull { it.title == activeLabel }?.path
        subDelayMs = ms
        delayLabel = activeLabel
        pendingExternalLabel = activeLabel // re-select after the re-prepare
        subtitleApplied = false
        // Nothing to generate (offset cleared, unknown label) or already generated: re-prepare now.
        if (source == null || ms == 0 || shiftedSubs.containsKey(shiftKey(source, ms))) {
            reprepareKeepingPosition(p, url)
            return
        }
        // X2 — build the shifted copy on IO, then re-prepare. A newer offset arriving meanwhile
        // cancels this one, and the staleness check stops a late result from re-preparing over it.
        shiftJob?.cancel()
        shiftJob = shiftScope.launch {
            val shifted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                SubtitleShift.shiftedCopy(context, java.io.File(source), ms)
            }
            shiftedSubs[shiftKey(source, ms)] = shifted
            if (subDelayMs != ms || delayLabel != activeLabel) return@launch
            val current = player ?: return@launch
            reprepareKeepingPosition(current, currentUrl ?: return@launch)
        }
    }

    private fun reprepareKeepingPosition(p: ExoPlayer, url: String) {
        val pos = p.currentPosition.coerceAtLeast(0)
        val wasPlaying = p.playWhenReady
        p.setMediaItem(buildMediaItem(url), pos)
        p.prepare()
        p.playWhenReady = wasPlaying
    }

    /** Decode path requested for the item being started, and the one the built [player] actually holds —
     *  the renderer factory is fixed at construction, so a change forces a rebuild in [start]. */
    private var softwarePreferred = false
    private var isArchiveItem = false
    /** Mirrors Settings → Video player → Hardware decoding, pushed in by [OwnTVPlayer]. */
    @Volatile var hwDecodingEnabled = true
    private var builtForSoftware = false

    /** The per-source User-Agent and the per-channel headers of the item mpv handed over, pushed in by
     *  [OwnTVPlayer] (F16). Without them a stream that only opens with a custom UA/Referer on mpv would
     *  fail the moment the engine fallback took over. */
    @Volatile var userAgent: String? = null
    @Volatile var httpHeaders: Map<String, String> = emptyMap()
    /** This item's Widevine/ClearKey licence details (#115), pushed in by [OwnTVPlayer]; null for the
     *  unprotected majority. Only this engine can honour it — mpv has no CDM to license the stream. */
    @Volatile var drmConfig: tv.own.owntv.core.drm.DrmConfig? = null
    /** The container this item DECLARED (v43), pushed in by [OwnTVPlayer]; null = infer from the URL,
     *  which is what every item did before. A protected film published at an extensionless DASH URL
     *  needs this for exactly the reason a live channel does. */
    @Volatile var manifestType: tv.own.owntv.core.player.ManifestType? = null
    private var httpFactory: OkHttpDataSource.Factory? = null

    private fun applyRequestHeaders() {
        val factory = httpFactory ?: return
        val perChannelUa = StreamHeaders.userAgentOf(httpHeaders)
        factory.setUserAgent(perChannelUa ?: userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_USER_AGENT)
        // Always set — an empty map clears the previous item's headers rather than leaking them.
        factory.setDefaultRequestProperties(httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
    }
    /** Whether the cached player's audio sink was pinned to stereo PCM. See the rebuild check in `start`. */
    private var builtForStereo = false

    /** The (buffer, timeout) the cached player was built with. See the rebuild check in `start`. */
    private var builtForNetwork = 0 to 0

    private fun build(): ExoPlayer {
        // OkHttp for the stream itself, wrapped in DefaultDataSource so file:// URIs (side-loaded
        // external subtitle files in app storage) route to FileDataSource — the bare OkHttp factory
        // can't open them, and Media3 swallows a side-loaded subtitle's load failure silently (the
        // track lists but never produces cues). The TransferListener stays on the inner OkHttp factory
        // so local subtitle-file bytes don't inflate the measured network bitrate.
        // N18 — a chosen timeout replaces the shared client's (connect 15 s, read 20 s) for films only;
        // newBuilder() keeps its pool, proxy, DNS and interceptors.
        val client = filmTimeoutSecs.takeIf { it > 0 }?.let { secs ->
            streamingHttp.client.newBuilder()
                .connectTimeout(secs.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(secs.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } ?: streamingHttp.client
        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(HttpClient.DEFAULT_USER_AGENT)
            .setTransferListener(throughputTracker)
        httpFactory = http
        applyRequestHeaders()
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        // Match mpv's buffering depth so stability doesn't drop after the handoff (Dev refinement #3).
        val tierBufferMs = (budget.cacheSecs.toIntOrNull() ?: 30) * 1000
        // N18 — the film buffer the user chose, or the tier's. The byte target below stays the TIER's:
        // it scales with the duration it is given, and a longer buffer must never mean more memory.
        val maxBufferMs = FilmNetwork.bufferSecs(filmBufferSecs, budget) * 1000
        val minBufferMs = (maxBufferMs / 2).coerceIn(15_000, maxBufferMs)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 2_500, 5_000)
            // Durations alone are not a memory bound: at 120 s (the top RAM tier) a 4K stream can ask the
            // allocator for hundreds of MB before the duration target is met, and a TV app dies long
            // before that. Media3's own default is derived from the duration, so scale with the same
            // tier instead of leaving it uncapped.
            .setTargetBufferBytes(targetBufferBytes(tierBufferMs))
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            // Settings → Video player → Preferred audio / subtitle language. These reached mpv only
            // (alang/slang), so an image-subtitle handoff or an ExoPlayer-preferred VOD silently ignored
            // them. An explicit subtitle pick still wins: applyPendingSubtitle sets an override.
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(prefAudioLang.takeIf { it.isNotBlank() })
                .setPreferredTextLanguage(prefSubLang.takeIf { it.isNotBlank() })
                .build()
        }
        // Software decoding is a rescue, not the default. A catch-up archive starts mid-GOP and SOME
        // TV-class hardware decoders can't recover from that — the Realtek OMX decoder accepts the
        // format, plays the audio, then never emits a video frame ("setPortMode ... DynamicANWBuffer
        // failed", "BAD CODEC: stride 1920 -> 64") — so [noVideoWatchdog] catches that case and restarts
        // the item here with softwarePreferred set, which resyncs at the next keyframe.
        // Stereo pinning mirrors the live engine: "Stereo only", or a session latch tripped by ANY engine,
        // means this player must not be given a sink that can bitstream Dolby/DTS.
        val renderers = ownTVRenderers(
            context,
            forceStereo = !AudioOutputPolicy.allowsMultichannel(surroundMode),
            softwareFirst = softwarePreferred,
            audioDelay = audioDelay,
        )
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                // Media3's default ONLY_IF_SEAMLESS still issues Surface.setFrameRate() requests, and this
                // engine plays whole movies — exactly where a 24 fps file on a 60 Hz panel judders. mpv and
                // the live engine already follow the setting; this one used to ignore it in both directions.
                setVideoChangeFrameRateStrategy(
                    if (autoFrameRateEnabled) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                )
                addListener(listener); addAnalyticsListener(analytics); addAnalyticsListener(audioWatchdog)
            }
    }

    /**
     * Byte cap to match the duration target. 24 MB (16 MB low-RAM) covers the 30 s base tier; higher RAM
     * tiers ask for proportionally more, bounded at 3x so a 4K file can never pin an unreasonable slice of
     * the app heap. Mirrors the live engine's cap, which exists for the same reason.
     */
    private fun targetBufferBytes(maxBufferMs: Int): Int {
        val base = (if (budget.lowSpec) LOW_RAM_TARGET_BYTES else TARGET_BUFFER_BYTES).toLong()
        val scaled = base * maxBufferMs / BASE_BUFFER_MS
        return scaled.coerceIn(base, base * 3).toInt()
    }

    /**
     * Audio Mode: stop decoding video entirely, without touching audio or position.
     *
     * Clearing the surface on its own left the video decoder running full speed into nothing — the whole
     * point of Audio Mode is that it costs less than watching. Deselecting the track type stops the
     * decoder outright, mirroring mpv's `vid=no` and the live engine's own Audio Mode.
     *
     * It also cancels the first-frame watchdog: with video deselected no frame can ever arrive, and the
     * watchdog would read that as "this device can't render the picture" and start a software rescue —
     * or surface a decode error — over a stream that is playing perfectly.
     */
    fun setVideoTrackDisabled(disabled: Boolean) {
        audioOnly = disabled
        if (disabled) noVideoWatchdog.disarm()
        // Re-enabling the video track needs a real output surface FIRST. Leaving Audio Mode from the
        // now-playing bar re-enables video while the full-screen SurfaceView is still unmounted, so the
        // renderer would create its decoder against a PlaceholderSurface and then be re-pointed at the
        // real one with MediaCodec.setOutputSurface — which several TV chipsets survive only by
        // rendering at a few frames a second (mpv is unaffected: `vid=auto` re-decodes into the surface
        // it already holds). So defer the re-enable to [setSurface]; `audioOnly` above already records
        // the intent, and nothing decodes in the meantime.
        if (!disabled && surface == null) return
        applyVideoTrackDisabled(disabled)
    }

    private fun applyVideoTrackDisabled(disabled: Boolean) {
        val p = player ?: return
        android.util.Log.i(TAG, "audio mode: video track ${if (disabled) "off" else "back on"}")
        runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
                .build()
        }
    }

    /** Re-point ExoPlayer at a (re)created surface, or null to release it (surfaceDestroyed). */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) {
            player?.clearVideoSurface()
            return
        }
        player?.setVideoSurface(surface)
        // Surface first, then the track: this is the deferred half of [setVideoTrackDisabled], so the
        // decoder is created against the real surface instead of a placeholder it has to be moved off.
        if (!audioOnly) applyVideoTrackDisabled(false)
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekTo(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0)); emitPositionDuration() }

    /** A D-pad step. Snaps to the nearest keyframe, as mpv's relative seeks do: an exact seek decodes
     *  from the previous keyframe up to the target, a multi-second freeze per press on 4K HEVC. Put
     *  back to EXACT straight after — the player applies both in order — so a resume, a chosen point or
     *  the passthrough re-prime (which must not jump) never inherits the snap. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
        p.setSeekParameters(SeekParameters.EXACT)
        emitPositionDuration()
    }

    fun setSpeed(speed: Double) { player?.setPlaybackSpeed(speed.toFloat()) }

    /** Set output volume. ExoPlayer's own gain is 0–1 and cannot amplify, so 100–150% rides on the
     *  platform LoudnessEnhancer — the same range mpv boosts in software, so the HUD's volume means the
     *  same thing whichever engine owns the item (it used to silently stop at 100% here). */
    fun setVolume(percent: Int) {
        player?.volume = (percent / 100f).coerceIn(0f, 1f)
        boost.apply(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET, percent)
    }

    private val boost = VolumeBoost { android.util.Log.i(TAG, it) }

    /** Called on a ~0.5s tick by [OwnTVPlayer] while active, so the HUD scrubber advances. */
    fun emitPositionDuration() {
        val p = player ?: return
        val dur = p.duration.let { if (it == C.TIME_UNSET) 0L else it }
        callbacks.onPositionDuration(p.currentPosition.coerceAtLeast(0), dur.coerceAtLeast(0), p.bufferedPosition.coerceAtLeast(0))
        // The audio-output safety net rides the tick that is already running. On a hit the whole session
        // latches to stereo and the owner restarts this item — the sink's capabilities are decided at
        // construction, so nothing short of a rebuild can undo a bad choice.
        audioWatchdog.poll(p.isPlaying)?.let { reason ->
            android.util.Log.w("ExoSubtitleEngine", "audio watchdog: $reason — forcing stereo for this session")
            AudioOutputPolicy.latchStereo("exo/vod: $reason")
            PlaybackErrorLog.event(context, "ExoPlayer", live = false, reason = PlayerFailureReason.STEREO_FALLBACK, detail = reason)
            onAudioFallback?.invoke()
        }
    }

    /** The user's Auto / Stereo only / Surround choice, pushed in by [OwnTVPlayer]; read at build time. */
    @Volatile var surroundMode: SurroundMode = SurroundMode.AUTO

    /** Settings → Video player → Auto frame rate, pushed in by [OwnTVPlayer]; read at build time. */
    @Volatile var autoFrameRateEnabled = false

    /** N18 — films' buffer and network timeout (0 = Auto), pushed in by [OwnTVPlayer]. Both are baked
     *  into the player at build, so a change rebuilds it on the next [start] — see [FilmNetwork]. */
    @Volatile var filmBufferSecs: Int = 0
    @Volatile var filmTimeoutSecs: Int = 0

    /** N18 — Settings → Reconnect attempts, pushed in by [OwnTVPlayer]; read at each drop. */
    @Volatile var filmReconnects: Int = 1

    /** In-place reopens this item has used, and the position of the last — reset on every [start]. */
    private var reconnectsUsed = 0
    private var reconnectAtMs = 0L

    /** Settings → Video player → Preferred audio / subtitle language (ISO code, blank = no preference).
     *  Pushed in by [OwnTVPlayer]; applied at build time and in place from [start]. */
    @Volatile var prefAudioLang: String = ""

    @Volatile var prefSubLang: String = ""

    /** Push the language preferences onto a player that was built before they last changed. */
    private fun applyLanguagePrefs() {
        val p = player ?: return
        runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(prefAudioLang.takeIf { it.isNotBlank() })
                .setPreferredTextLanguage(prefSubLang.takeIf { it.isNotBlank() })
                .build()
        }
    }

    /** Fired once when the audio watchdog forces stereo; the owner shows the message and restarts. */
    var onAudioFallback: (() -> Unit)? = null

    /** Fired when this item failed on the hardware decoder and the software rung is still available;
     *  the owner restarts it here with `preferSoftware`. [fromStart] when the item must restart at 0
     *  rather than resume (a catch-up archive has no Range support, so re-opening at an offset fails).
     *
     *  This is ExoPlayer's own software rung, the mirror of mpv's [OwnTVPlayer] rescue ladder: without
     *  it a file that only a software decoder can handle left the preferred engine immediately, even
     *  though the very next thing tried (mpv in software) proved software decoding was the answer. */
    var onSoftwareRescue: ((url: String, fromStart: Boolean) -> Unit)? = null

    private val audioWatchdog = AudioWatchdog()

    /** Select an audio track by the id we published in [Callbacks.onAudioTracks]. */
    fun selectAudio(id: Int) {
        val p = player ?: return
        val sel = audioSelections.firstOrNull { it.id == id } ?: return
        // Logged because a track switch is otherwise invisible in a log: the sink/decoder churn it
        // causes shows up as bare AudioTrack/DefaultAudioSink lines with nothing marking what asked
        // for them. Format details identify a switch that changes encoding, channel count or rate —
        // the three things that force the sink to be torn down and re-created rather than reused.
        val f = runCatching { sel.group.getFormat(sel.trackIndex) }.getOrNull()
        android.util.Log.i(
            TAG,
            "audio track -> id=$id lang=${f?.language} " +
                "codec=${f?.sampleMimeType} ch=${f?.channelCount} rate=${f?.sampleRate}",
        )
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(sel.group, listOf(sel.trackIndex)))
            .build()
        // Bitstreamed audio (Dolby/DTS decoded by the TV) does not survive being re-selected in place.
        // Changing the override tears the output down and rebuilds it three times inside 40ms, and the
        // TV's decoder never recovers: sound breaks up continuously afterwards while ExoPlayer reports
        // a perfectly healthy sink — no underruns, no errors, position advancing — so the AUTO stereo
        // watchdog cannot see it either. Picking the very same track at startup plays flawlessly, which
        // is what identifies the in-place switch, not the track or passthrough itself, as the fault.
        //
        // Seeking to where we already are re-primes the sink once, from a stopped state, the way a
        // fresh start does. It costs a brief re-buffer, so it is spent only on the path that needs it —
        // when the app decodes the audio itself the plain override above is already correct.
        if (audioWatchdog.passthrough) {
            android.util.Log.i(TAG, "passthrough audio: re-priming the output after the track change")
            runCatching { p.seekTo(p.currentPosition) }
        }
    }

    /** X1: audio-only media is a valid state, not a device fault — cancel the no-video watchdog for
     *  it. Only a file that *declares* a video track and never renders a frame is a real problem. */
    private fun updateVideoTrackPresence(tracks: Tracks) {
        if (tracks.groups.isEmpty()) return // nothing known yet — keep the watchdog armed
        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        hasVideoTrack = hasVideo
        // Audio Mode disables the video track deliberately; that must not read as "this file has no video".
        callbacks.onAudioOnlyMedia(!hasVideo && !audioOnly)
        if (!hasVideo) {
            android.util.Log.i(TAG, "no video track in this file — audio-only, no-video watchdog disarmed")
            noVideoWatchdog.disarm()
        }
    }

    private fun rebuildAudioTracks(tracks: Tracks) {
        val out = ArrayList<TrackOption>()
        val sels = ArrayList<AudioSel>()
        var id = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                out.add(
                    TrackOption(
                        label = format.label.orEmpty(),
                        mpvId = id,
                        selected = group.isTrackSelected(i),
                        lang = format.language,
                        typeIndex = id,
                        labelKind = TrackLabelKind.AUDIO,
                    ),
                )
                sels.add(AudioSel(id, group.mediaTrackGroup, i))
                id++
            }
        }
        audioSelections = sels
        callbacks.onAudioTracks(out)
    }

    /** Enumerate the file's text/image subtitle tracks for the HUD menu. [TrackOption.mpvId] and
     *  [TrackOption.typeIndex] are both the ordinal among text tracks — [selectTextTrack] selects by it. */
    private fun rebuildTextTracks(tracks: Tracks) {
        val out = ArrayList<TrackOption>()
        var id = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType
                val image = mime == androidx.media3.common.MimeTypes.APPLICATION_PGS ||
                    mime == androidx.media3.common.MimeTypes.APPLICATION_VOBSUB ||
                    mime == androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS
                // Side-loaded external subs keep their raw configured label, which already carries their
                // source in its prefix (see SubtitleTrackLabel). The raw value remains an engine identity
                // for selection and engine-toggle carry-over, never a translated sentence.
                out.add(
                    TrackOption(
                        label = format.label.orEmpty(),
                        mpvId = id,
                        selected = group.isTrackSelected(i),
                        image = image,
                        lang = format.language,
                        typeIndex = id,
                        labelKind = TrackLabelKind.SUBTITLE,
                    ),
                )
                id++
            }
        }
        callbacks.onTextTracks(out)
    }

    /** Force-select the image subtitle track that matches the one the user picked in mpv's list:
     *  prefer the same ordinal among text tracks, then fall back to language. */
    private fun applyPendingSubtitle(tracks: Tracks) {
        if (subtitleApplied) return
        val p = player ?: return
        // A just-side-loaded external subtitle: select it by its label (set on the SubtitleConfiguration).
        pendingExternalLabel?.let { label ->
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    if (group.getTrackFormat(i).label == label) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(i)))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        pendingExternalLabel = null
                        subtitleApplied = true
                        return
                    }
                }
            }
            return // its track hasn't appeared in this update yet — wait for the next onTracksChanged
        }
        // Flatten text tracks in declaration order so the mpv sub ordinal lines up with ExoPlayer's.
        data class TextTrack(val group: TrackGroup, val index: Int, val lang: String?)
        val textTracks = ArrayList<TextTrack>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                textTracks.add(TextTrack(group.mediaTrackGroup, i, group.getTrackFormat(i).language))
            }
        }
        if (textTracks.isEmpty()) return
        // A fresh item has no manual carry-over. Honour the configured language explicitly:
        // this handoff path used to disable the text renderer after Media3 selected it.
        // Blank preference or no matching track leaves subtitles off rather than choosing randomly.
        if (pendingSubTypeIndex < 0 && pendingSubLang == null) {
            val preferred = prefSubLang.takeIf { it.isNotBlank() }?.let { wanted ->
                textTracks.firstOrNull { subtitleLanguageMatches(wanted, it.lang) }
            }
            val builder = p.trackSelectionParameters.buildUpon()
            if (preferred == null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                builder
                    .setOverrideForType(TrackSelectionOverride(preferred.group, listOf(preferred.index)))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            p.trackSelectionParameters = builder.build()
            subtitleApplied = true
            return
        }
        // The ordinal is a cross-engine guess — mpv's track order need not survive into ExoPlayer's — so
        // it only stands when the track it lands on also carries the language the user picked. Failing
        // that, match by language; and force a track only when there is exactly one it could be. The old
        // blind ".first()" tail handed the user an arbitrary subtitle in a language they never asked for,
        // with nothing on screen to say so.
        val target = textTracks.getOrNull(pendingSubTypeIndex)
            ?.takeIf { pendingSubLang == null || it.lang.equals(pendingSubLang, ignoreCase = true) }
            ?: pendingSubLang?.let { lang -> textTracks.firstOrNull { it.lang.equals(lang, ignoreCase = true) } }
            ?: textTracks.singleOrNull()
        if (target == null) {
            android.util.Log.w(TAG, "no text track matches the picked subtitle (index=$pendingSubTypeIndex lang=$pendingSubLang) — leaving subtitles off")
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleApplied = true
            return
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.group, listOf(target.index)))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        subtitleApplied = true
    }

    /** HUD subtitle pick while fallback playback is active: select the text/image subtitle by its
     *  ordinal among this file's text tracks (mpv's typeIndex lines up with ExoPlayer's order). */
    fun selectTextTrack(typeIndex: Int, lang: String?) {
        pendingSubTypeIndex = typeIndex
        pendingSubLang = lang
        subtitleApplied = false
        player?.let { applyPendingSubtitle(it.currentTracks) }
    }

    /** HUD "subtitles off" while fallback playback is active. */
    fun disableTextTracks() {
        pendingSubTypeIndex = -1
        pendingSubLang = null
        subtitleApplied = true
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun setBitrateTrackingEnabled(enabled: Boolean) = throughputTracker.setEnabled(enabled)

    /** Technical readout for the stream-info overlay while this engine owns playback (main thread only —
     *  the overlay polls from composition). Mirrors [LivePreviewEngine.streamInfo]'s format. */
    fun streamInfo(): List<StreamInfoRow> {
        val p = player ?: return emptyList()
        val out = ArrayList<StreamInfoRow>()
        formatRow(p)?.let { out += it }
        p.videoFormat?.let { f ->
            out += StreamInfoRow(
                StreamInfoLabel.VIDEO,
                StreamInfoValue.Video(
                    codec = f.sampleMimeType?.substringAfterLast('/')?.let { mimeName(it) },
                    width = f.width.takeIf { it > 0 },
                    height = f.height.takeIf { it > 0 },
                    fps = currentFps()?.toDouble(),
                ),
            )
            when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> StreamHdrMode.HDR10_PQ
                C.COLOR_TRANSFER_HLG -> StreamHdrMode.HLG
                else -> null
            }?.let { out += StreamInfoRow(StreamInfoLabel.HDR, StreamInfoValue.Hdr(it)) }
            out += bitrateRow(f, throughputTracker)
        }
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
        bufferRow(p, dropsBaseline)?.let { out += it }
        // Same readout as the live engine: who is decoding the audio, and whether the safety net fired.
        out += StreamInfoRow(
            StreamInfoLabel.AUDIO_OUTPUT,
            StreamInfoValue.AudioOutput(
                kind = if (audioWatchdog.passthrough) AudioOutputKind.PASSTHROUGH else AudioOutputKind.DECODED_IN_APP,
                multichannelAllowed = AudioOutputPolicy.allowsMultichannel(surroundMode),
                fallbackReason = AudioOutputPolicy.latchReason,
            ),
        )
        return out
    }

    /** The Format row. Live ExoPlayer can state its route because it *chose* it; VOD hands the URL to
     *  Media3 and has to read back what the extractor or manifest actually resolved. Null when nothing
     *  identifies the container — an absent row beats a wrong one. */
    private fun formatRow(p: ExoPlayer): StreamInfoRow? {
        val cfg = p.currentMediaItem?.localConfiguration
        val label = StreamFormatLabels.resolve(
            containerMimeType = p.videoFormat?.containerMimeType ?: p.audioFormat?.containerMimeType,
            requestedMimeType = cfg?.mimeType,
            path = cfg?.uri?.path,
        ) ?: return null
        return StreamInfoRow(StreamInfoLabel.FORMAT, StreamInfoValue.Format(label))
    }

    private fun mimeName(m: String) = when (m.lowercase()) {
        "hevc" -> "HEVC"; "avc" -> "H.264"; "av01" -> "AV1"; "x-vnd.on2.vp9", "vp9" -> "VP9"
        "mp4v-es" -> "MPEG-4"; "mpeg2" -> "MPEG-2"; else -> m.uppercase()
    }

    private fun friendlyFailure(error: PlaybackException): PlaybackFailure = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
            if (fallbackMode) PlaybackFailure.ExoDecode(error.errorCodeName)
            else PlaybackFailure.ImageFormat
        else ->
            if (fallbackMode) PlaybackFailure.ExoPlay(error.errorCodeName)
            else PlaybackFailure.ImageShow
    }

    /** Stop and free the ExoPlayer (the handoff back to mpv, or stop()). Keeps the instance? No —
     *  fully release so we never hold a second decoder/connection while mpv plays. */
    fun stop() {
        surface = null
        noVideoWatchdog.disarm()
        callbacks.onCues(emptyList())
        boost.release() // the effect is bound to this player's audio session
        player?.let { p ->
            p.removeListener(listener)
            p.clearVideoSurface()
            p.release()
        }
        player = null
        audioSelections = emptyList()
        fallbackMode = false
        currentUrl = null
        externalSubs.clear()
        pendingExternalLabel = null
        subDelayMs = 0
        delayLabel = null
        // X2 — the shifted copies only make sense for the session that generated them; drop them so
        // they don't accumulate in cacheDir. The name guard keeps a fallback result (which is the
        // user's own subtitle file) safe from deletion.
        shiftJob?.cancel()
        shiftJob = null
        shiftedSubs.values.forEach { f -> runCatching { if (f.name.startsWith("subshift_")) f.delete() } }
        shiftedSubs.clear()
    }

    fun release() = stop()

    private companion object {
        const val TAG = "ExoSubtitleEngine"
        /** Byte caps for [targetBufferBytes]; [BASE_BUFFER_MS] is the lowest RAM tier's duration target. */
        const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024
        const val LOW_RAM_TARGET_BYTES = 16 * 1024 * 1024
        const val BASE_BUFFER_MS = 30_000
        /** Armed at load, so it covers the open as well as the decode — see [NoFrameWatchdog] for why
         *  this matches the hero preview's budget rather than the live engine's 8 s. */
        const val NO_VIDEO_TIMEOUT_MS = 12_000L
        /** Mid-GOP + software decode needs a much longer first-frame budget — see [noVideoTimeoutMs]. */
        const val NO_VIDEO_TIMEOUT_SOFTWARE_MS = 25_000L

        /** Failures a different (software) decoder can plausibly fix — see the software rescue in
         *  `onPlayerError`. Everything else (network, source, DRM, renderer/timeout) goes to mpv. */
        /** A connection that failed or went silent — what a reopen can cure, unlike a refusal or bad data. */
        val NETWORK_DROP_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        val DECODE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
