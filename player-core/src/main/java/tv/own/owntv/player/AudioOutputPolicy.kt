package tv.own.owntv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import tv.own.owntv.core.player.SurroundMode

/**
 * Session-wide state for the audio-output safety net, shared by **every** engine (mpv, the Live
 * preview/fullscreen ExoPlayer, the VOD ExoPlayer).
 *
 * Why global and not per-engine: the fault being guarded against lives in the device's audio HAL or
 * in the HDMI/ARC sink, not in a stream. Once one engine has proved that this TV cannot actually
 * play what it claims to support, every other engine must inherit that lesson immediately —
 * otherwise the user zaps to the next channel, lands on the other engine, and loses sound again.
 *
 * In-memory only, deliberately. A latch is a statement about "this output, right now" (an HDMI
 * handshake, a soundbar that woke up in the wrong mode); persisting it would silently downgrade a
 * genuinely capable receiver forever. Changing the setting also clears it — that is the user asking
 * for a fresh attempt.
 */
object AudioOutputPolicy {

    /** Never advance audio for this long while playing with an audio track = the output is dead. */
    const val NO_AUDIO_GRACE_MS = 6_000L

    /** Underruns within [UNDERRUN_WINDOW_MS] that mean the sink is starving, not hiccuping. */
    const val UNDERRUN_LIMIT = 4
    const val UNDERRUN_WINDOW_MS = 10_000L

    @Volatile private var latched = false
    @Volatile var latchReason: String? = null
        private set

    /** True once the output has been caught failing; forces stereo PCM everywhere for this session. */
    val stereoLatched: Boolean get() = latched

    /**
     * True when this mode + the current latch permit anything other than plain stereo PCM.
     * [SurroundMode.STEREO] and a tripped latch are the same answer for different reasons.
     */
    fun allowsMultichannel(mode: SurroundMode): Boolean = mode != SurroundMode.STEREO && !latched

    /** Trip the latch. Idempotent — the first reason is the interesting one. */
    fun latchStereo(reason: String) {
        if (latched) return
        latched = true
        latchReason = reason
        android.util.Log.w("AudioOutputPolicy", "forcing stereo for this session: $reason")
    }

    /** The user changed the surround setting: give the output another chance. */
    fun clearLatch() {
        latched = false
        latchReason = null
    }
}

/**
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 *
 * When [forceStereo] is set the audio sink is built with [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES]
 * — Media3's "minimum capabilities supported by all devices": 16-bit stereo PCM, **no encoded
 * passthrough**. `MediaCodecAudioRenderer` asks the sink what it supports before choosing between
 * passthrough and in-app decoding, so capping the sink is what actually removes the bitstream path;
 * a track-selection constraint alone would not (the selector still picks a 5.1 track when it is the
 * only one).
 *
 * The no-argument [DefaultAudioSink.Builder] is deprecated precisely *because* passing a `Context`
 * makes the sink query the real device capabilities and ignore anything set here — which is the one
 * thing we must not let it do. If a future Media3 removes it, the `runCatching` falls back to the
 * stock sink and we lose the guarantee rather than the playback.
 *
 * Every sink carries an [AudioDynamicsProcessor] (night mode / volume levelling, P14), which passes
 * sound through untouched while both are off. With [passthrough] false the sink refuses encoded
 * Dolby/DTS that the device can decode ([DecodedOnlyAudioSink]), so it is decoded here — multichannel
 * PCM still, when the output takes it.
 */
@UnstableApi
class OwnTVRenderersFactory(
    context: Context,
    private val forceStereo: Boolean,
    /** A/V-sync offset applied to whichever sink is built — see [AudioDelayClock]. Null = none. */
    private val audioDelay: AudioDelayClock? = null,
    /** N8 — false: decode Dolby/DTS in the app instead of bitstreaming it. */
    private val passthrough: Boolean = true,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        var sink = buildBaseAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams) ?: return null
        if (!passthrough && !forceStereo) sink = DecodedOnlyAudioSink(sink)
        return if (audioDelay != null) DelayedClockAudioSink(sink, audioDelay) else sink
    }

    private fun buildBaseAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        // What DefaultRenderersFactory builds itself, plus the processor.
        fun deviceSink(): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(AudioDynamicsProcessor()))
            .build()
        if (!forceStereo) return deviceSink()
        return runCatching<AudioSink?> {
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(AudioDynamicsProcessor()))
                .build()
        }.getOrElse {
            android.util.Log.w("AudioOutputPolicy", "stereo-only sink unavailable, using device capabilities", it)
            deviceSink()
        }
    }
}

/**
 * N8 — a sink that will not take encoded audio the device has a decoder for, so the renderer decodes it
 * instead of bitstreaming it. A format with no decoder on this device is still passed through: sound
 * from the receiver beats silence (and on the live engine, "no decoder" is what hands a channel to mpv).
 */
@UnstableApi
class DecodedOnlyAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean =
        if (mustDecode(format)) false else super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (mustDecode(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    private fun mustDecode(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        return decodable.getOrPut(mime) {
            runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrDefault(false)
        }
    }

    private companion object {
        val decodable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
}

/**
 * Watches an ExoPlayer's audio output and reports the two failures a user experiences as "picture
 * but no sound" and "sound keeps cutting out".
 *
 * This is a listener plus a [poll] that the owning engine calls from the health tick it already
 * runs — it deliberately owns no timer of its own, so it cannot keep an engine alive after release.
 *
 * Detection, in order of confidence:
 *
 *  1. **`onAudioSinkError`** — the sink told us outright. Immediate. Excludes
 *     `UnexpectedDiscontinuityException`, which reports a gap in the *stream's* timestamps and is
 *     self-healing; see [onAudioSinkError].
 *  2. **Armed but never advancing.** `onAudioInputFormatChanged` proves an audio track was selected
 *     and handed to a decoder; `onAudioPositionAdvancing` fires when the AudioTrack's playback head
 *     actually starts moving, i.e. when sound genuinely leaves the device. If the first happens and
 *     the second does not within [AudioOutputPolicy.NO_AUDIO_GRACE_MS] of *playing* time, the output
 *     accepted the format and produced silence — the exact RMK62 signature. Playing time, not wall
 *     clock: a channel that spends eight seconds buffering has not failed at anything.
 *  3. **Repeated underruns.** One is a hiccup; [AudioOutputPolicy.UNDERRUN_LIMIT] inside
 *     [AudioOutputPolicy.UNDERRUN_WINDOW_MS] is a sink that cannot keep up with the format it said
 *     it could play.
 *
 * A hit calls back once per player instance; the owner is expected to latch stereo and rebuild.
 */
@UnstableApi
class AudioWatchdog : AnalyticsListener {

    /** Set when a failure is detected; consumed by [poll]. Cleared on [reset]. */
    @Volatile private var pendingReason: String? = null
    @Volatile private var fired = false

    @Volatile private var armed = false
    @Volatile private var advancing = false
    /** Accumulated *playing* milliseconds since the audio format was accepted. */
    @Volatile private var playingSinceArmMs = 0L
    @Volatile private var lastTickMs = 0L

    private val underrunTimes = ArrayDeque<Long>()

    /** The audio format currently feeding the sink, for the stream-info readout. */
    @Volatile var audioFormat: Format? = null
        private set
    /** True when the renderer chose passthrough (the TV/receiver decodes) rather than in-app decode. */
    @Volatile var passthrough = false
        private set
    /** Whether a decoder was initialised for the current audio format. See [onAudioPositionAdvancing]. */
    @Volatile private var decoderInitialized = false

    /** Call when a new load starts. */
    fun reset() {
        pendingReason = null; fired = false
        armed = false; advancing = false
        playingSinceArmMs = 0L; lastTickMs = 0L
        synchronized(underrunTimes) { underrunTimes.clear() }
        audioFormat = null; passthrough = false; decoderInitialized = false
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        // The format actually reaching the sink, including the one chosen at startup — without this a
        // log only ever showed what the user switched *to*, never what they switched *from*, so a
        // stereo→5.1 change was indistinguishable from a change between two identical formats.
        android.util.Log.i(
            "AudioOutputPolicy",
            "audio format -> ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz " +
                "lang=${format.language} reuse=${decoderReuseEvaluation?.result}",
        )
        audioFormat = format
        armed = true
        advancing = false
        decoderInitialized = false
        playingSinceArmMs = 0L
        lastTickMs = 0L
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderInitialized = true
        // Media3 names the pass-through "decoder" after the encoding it is bitstreaming, and never
        // after a real codec. This catches the path where a codec *is* created; the other path is
        // caught in [onAudioPositionAdvancing].
        passthrough = decoderName.startsWith("audio.raw", ignoreCase = true) ||
            decoderName.startsWith("audio.passthrough", ignoreCase = true)
        // Whether the TV is decoding the bitstream or we are is the single most useful fact about an
        // audio problem, and it was only ever visible in the stream-info overlay, never in a log.
        android.util.Log.i(
            "AudioOutputPolicy",
            "audio decoder: $decoderName (init ${initializationDurationMs}ms, passthrough=$passthrough)",
        )
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        advancing = true
        // The renderer has committed to a path by the time sound actually leaves the device, so this
        // is the safe moment to conclude what it chose. **No decoder at all** means MediaCodec was
        // bypassed and the encoded stream is going straight to the TV — Media3 reports no decoder
        // whatsoever on that path, so the decoder *name* checked above never arrives and passthrough
        // read `false` for every bitstreamed E-AC3/AC3/DTS track. Deferring to here rather than
        // deciding in onAudioInputFormatChanged avoids the window where the decoder simply has not
        // been created yet.
        if (!decoderInitialized && !passthrough) {
            passthrough = true
            android.util.Log.i("AudioOutputPolicy", "audio path: passthrough — the TV is decoding this stream")
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Individual underruns were silent until the limit tripped, which made "sound keeps cutting
        // out" impossible to confirm from a log: below the limit there was no evidence at all, and at
        // the limit only a verdict. One line each is cheap — healthy playback produces none.
        android.util.Log.w(
            "AudioOutputPolicy",
            "audio underrun: buffer=${bufferSize}B/${bufferSizeMs}ms, ${elapsedSinceLastFeedMs}ms since last feed",
        )
        val hit = synchronized(underrunTimes) {
            underrunTimes.addLast(now)
            while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > AudioOutputPolicy.UNDERRUN_WINDOW_MS) {
                underrunTimes.removeFirst()
            }
            underrunTimes.size >= AudioOutputPolicy.UNDERRUN_LIMIT
        }
        if (hit) raise("audio output underran ${AudioOutputPolicy.UNDERRUN_LIMIT}× in ${AudioOutputPolicy.UNDERRUN_WINDOW_MS / 1000}s")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        // A timestamp discontinuity is a statement about the *stream*, not about the output. Media3
        // raises it whenever a buffer's presentation time lands more than 200ms from where the running
        // frame count says it should (DefaultAudioSink), then re-anchors its own clock and carries on
        // playing — a routine event on files whose container timestamps jump. Treating it as sink
        // failure latched a whole session to stereo over one imperfect file, and since forcing a stereo
        // sink cannot repair a gap that lives in the file, the rebuilt player hit the same spot and
        // tripped again: repeated mid-film restarts on a device whose audio was never in trouble.
        // If the output really is dead, the no-advance tier catches it seconds later anyway.
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) {
            android.util.Log.i(
                "AudioOutputPolicy",
                "audio timestamp discontinuity: ${audioSinkError.actualPresentationTimeUs - audioSinkError.expectedPresentationTimeUs}us " +
                    "off expected — the sink resyncs itself, not treating this as an output failure",
            )
            return
        }
        raise("audio sink error: ${audioSinkError.message ?: audioSinkError.javaClass.simpleName}")
    }

    private fun raise(reason: String) {
        if (fired) return
        fired = true
        pendingReason = reason
    }

    /**
     * Advance the "playing time since armed" clock and return a failure reason once, or null.
     * [isPlaying] must be the player's real playing state — paused time must not count.
     */
    fun poll(isPlaying: Boolean): String? {
        pendingReason?.let { pendingReason = null; return it }
        if (!armed || advancing) { lastTickMs = 0L; return null }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isPlaying) { lastTickMs = 0L; return null }
        if (lastTickMs != 0L) playingSinceArmMs += (now - lastTickMs).coerceAtMost(2_000L)
        lastTickMs = now
        if (playingSinceArmMs < AudioOutputPolicy.NO_AUDIO_GRACE_MS) return null
        if (fired) return null
        fired = true
        val what = audioFormat?.let { MimeTypes.normalizeMimeType(it.sampleMimeType ?: "") } ?: "audio"
        return "no sound from the audio output after ${AudioOutputPolicy.NO_AUDIO_GRACE_MS / 1000}s ($what)"
    }

    /** Human-readable audio line for the stream-info overlay, or null when nothing is known yet. */
    fun describe(): String? {
        val f = audioFormat ?: return null
        val codec = f.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: "?"
        val channels = if (f.channelCount != Format.NO_VALUE) "${f.channelCount}ch" else null
        val rate = if (f.sampleRate != Format.NO_VALUE) "${f.sampleRate / 1000}kHz" else null
        val path = if (passthrough) "passthrough" else "decoded"
        return listOfNotNull(codec, channels, rate, path).joinToString(" · ")
    }
}

/** Media3 channel-count cap that matches [mode]; [C.INDEX_UNSET] semantics are not used here. */
fun maxAudioChannelsFor(mode: SurroundMode): Int =
    if (AudioOutputPolicy.allowsMultichannel(mode)) Int.MAX_VALUE else 2
