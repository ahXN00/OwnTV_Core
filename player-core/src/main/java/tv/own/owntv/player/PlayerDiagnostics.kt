package tv.own.owntv.player

import android.os.SystemClock
import androidx.annotation.StringRes
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedDeque
import tv.own.owntv.core.R

/**
 * Tails the app's OWN logcat for the below-the-engine playback failures the player objects can't expose:
 * Android **MediaCodec** (e.g. `Codec reported err 0x80001000`) and **AudioTrack** errors. mpv/ExoPlayer sit
 * on top of MediaCodec, so a hardware decode/audio failure only names itself in the system log — and most
 * users can't run adb. Reading your *own* process's logs needs no permission.
 *
 * Best-effort: if logcat can't be spawned (rare/locked-down devices), it silently no-ops and the player
 * falls back to the engine's own error text. A single daemon reader thread, tag-filtered to stay cheap.
 */
class PlayerDiagnostics {
    private data class Entry(val atMs: Long, val tag: String, val text: String)

    private val ring = ConcurrentLinkedDeque<Entry>()
    @Volatile private var started = false
    @Volatile private var loadStartMs = 0L

    /** Start tailing (idempotent). Call once the player initialises. */
    fun start() {
        if (started) return
        started = true
        // Self-healing: some boxes kill the logcat process mid-session, which would otherwise silently
        // end diagnostics for the rest of the app's life. Restart with backoff; a run that survived a
        // while resets the attempt counter, so only genuinely-blocked devices give up.
        Thread({
            var attempt = 0
            while (attempt < MAX_TAIL_RESTARTS) {
                val ranAt = SystemClock.elapsedRealtime()
                readLoop()
                attempt = if (SystemClock.elapsedRealtime() - ranAt >= STABLE_RUN_MS) 1 else attempt + 1
                SystemClock.sleep(TAIL_RESTART_DELAY_MS)
            }
        }, "owntv-logcat").apply { isDaemon = true; start() }
    }

    /** Mark the start of a new stream load, so [recentError] only reports failures from the CURRENT item. */
    fun markLoad() { loadStartMs = SystemClock.elapsedRealtime() }

    private fun readLoop() {
        runCatching {
            // `-T 1`: start near "now" (don't replay the whole backlog). Tag-filtered to codec/audio errors.
            val cmd = listOf(
                "logcat", "-v", "tag", "-T", "1",
                "MediaCodec:E", "ACodec:E", "OMXClient:E", "CCodec:E", "Codec2Client:E", "C2PlatformStore:E",
                "MediaCodecRenderer:E", "MediaCodecVideoRenderer:E", "MediaCodecAudioRenderer:E",
                "AudioTrack:W", "AudioSink:E", "AudioFlinger:E", "*:S",
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines -> lines.forEach(::record) }
        }
    }

    private fun record(line: String) {
        // `-v tag` format: "E/MediaCodec: Codec reported err 0x80001000, actionCode 0, …"
        val slash = line.indexOf('/')
        if (slash != 1) return
        val colon = line.indexOf(':', slash)
        if (colon < 0) return
        val tag = line.substring(slash + 1, colon).trim()
        val text = line.substring(colon + 1).trim()
        if (text.isEmpty()) return
        ring.addLast(Entry(SystemClock.elapsedRealtime(), tag, text))
        while (ring.size > 80) ring.pollFirst()
    }

    /** The most recent codec/audio error from the current stream (≤15 s old), raw "tag: text"; null if none.
     *  [PlayerErrors.reasonFor] is applied by the consumer so all error sources share one humanization map. */
    fun recentError(): String? {
        // elapsedRealtime is monotonic — the wall clock can jump on NTP sync and skew this window.
        val cutoff = maxOf(loadStartMs, SystemClock.elapsedRealtime() - 15_000)
        val e = ring.descendingIterator().asSequence().firstOrNull { it.atMs >= cutoff } ?: return null
        return "${e.tag}: ${e.text}"
    }

    private companion object {
        const val MAX_TAIL_RESTARTS = 5
        const val TAIL_RESTART_DELAY_MS = 5_000L
        /** A tail that lived this long was healthy — reset the restart budget. */
        const val STABLE_RUN_MS = 60_000L
    }
}

/** Semantic decoder details for the media specification shown by the playback error renderer. */
sealed interface DecoderSpec {
    data class Hardware(val direct: Boolean = false) : DecoderSpec
    data class Software(val gpu: Boolean = false) : DecoderSpec
    data class Named(val value: String, val hardware: Boolean = false, val direct: Boolean = false) : DecoderSpec
}

/** Technical media details. It deliberately carries values, not a locale-resolved sentence. */
data class MediaSpec(
    val codec: String?,
    val resolution: String?,
    val decoder: DecoderSpec?,
)

/** Typed playback failures. Fixed OwnTV wording is resolved by the Compose HUD; raw provider/engine text stays raw. */
sealed interface PlaybackFailure {
    data object Channel : PlaybackFailure
    data object LostConnection : PlaybackFailure
    data object StreamLink : PlaybackFailure
    data object NotStreaming : PlaybackFailure
    data object AudioNoVideo : PlaybackFailure
    data object FileCorrupt : PlaybackFailure
    data object MultipleVideos : PlaybackFailure
    data object DecoderBusy : PlaybackFailure

    /**
     * The device has no video decoder left to give — every hardware instance is already in use. Not
     * the same as [DecoderBusy]: waiting a moment does not help, because something else on screen is
     * holding the decoder. This is what a fourth Multiview tile hits on cheap TV silicon (D5: the
     * tile explains itself, the grid stays up).
     */
    data object DecoderExhausted : PlaybackFailure
    data object NoInternet : PlaybackFailure
    data object Surround : PlaybackFailure
    data object ImageSubtitleAudio : PlaybackFailure
    data object ImageFormat : PlaybackFailure
    data object ImageShow : PlaybackFailure
    data object BothEnginesExoFirst : PlaybackFailure
    data class BothEnginesMpvFirst(val exoError: PlaybackFailure) : PlaybackFailure
    data class ExoDecode(val code: String) : PlaybackFailure
    data class ExoPlay(val code: String) : PlaybackFailure
    data class HardwareFallback(val resolution: String) : PlaybackFailure
    data class HardwareDisabled(val resolution: String) : PlaybackFailure
    data class HardwareFormat(val resolution: String, val codec: String) : PlaybackFailure
    data class StreamUnavailable(val customUserAgentHint: Boolean) : PlaybackFailure
    /**
     * A Cast receiver refused the stream. The receiver decodes it itself — no engine of ours is
     * involved — so a container it does not support (raw MPEG-TS, most of all) or a provider that
     * insists on its own request headers simply cannot be cast, and nothing on this side changes it.
     */
    data object CastUnsupported : PlaybackFailure
    /** Fixed mpv diagnostics produced by OwnTV, not provider text; resolve at the UI boundary. */
    data object MpvOpenDecode : PlaybackFailure
    data object MpvStreamNeverStarted : PlaybackFailure
    /** Genuine unknown engine/provider text; intentionally not translated. */
    data class Raw(val message: String) : PlaybackFailure
}

/**
 * The one place a [PlaybackFailure] becomes words.
 *
 * This mapping existed twice — once in the Compose HUD via `stringResource`, once in the toast renderer
 * via `context.getString` — with the same two dozen cases in a different order. The compiler catches a
 * *missing* case in either copy, but nothing catches the two copies naming *different strings* for the
 * same failure, which is the mistake worth designing out.
 *
 * [resolve] is how the caller turns a string resource into text: the HUD resolves through Compose's
 * locale-aware resources, the toast renderer through a [tv.own.owntv.core.i18n.LocaleStore]-wrapped
 * context, because a process-wide player can outlive an in-session language switch. The nested cases
 * recurse with the same resolver, so a wrapped failure is rendered in the same locale as its wrapper.
 */
fun PlaybackFailure.describe(resolve: (Int, List<Any>) -> String): String {
    fun str(id: Int, vararg args: Any) = resolve(id, args.toList())
    return when (this) {
        PlaybackFailure.Channel -> str(R.string.player_error_channel)
        PlaybackFailure.LostConnection -> str(R.string.player_error_lost_connection)
        PlaybackFailure.StreamLink -> str(R.string.player_error_stream_link)
        PlaybackFailure.NotStreaming -> str(R.string.player_error_not_streaming)
        PlaybackFailure.AudioNoVideo -> str(R.string.player_error_audio_no_video)
        PlaybackFailure.FileCorrupt -> str(R.string.player_error_file_corrupt)
        PlaybackFailure.MultipleVideos -> str(R.string.player_error_multiple_videos)
        PlaybackFailure.DecoderBusy -> str(R.string.player_error_decoder_busy)
        PlaybackFailure.DecoderExhausted -> str(R.string.multiview_decoder_exhausted)
        PlaybackFailure.NoInternet -> str(R.string.player_error_no_internet)
        PlaybackFailure.Surround -> str(R.string.player_error_surround)
        PlaybackFailure.ImageSubtitleAudio -> str(R.string.player_error_image_subtitle_audio)
        PlaybackFailure.ImageFormat -> str(R.string.player_error_image_format)
        PlaybackFailure.ImageShow -> str(R.string.player_error_image_show)
        PlaybackFailure.BothEnginesExoFirst -> str(R.string.player_error_both_engines_exo_first)
        is PlaybackFailure.BothEnginesMpvFirst ->
            str(R.string.player_error_both_engines_mpv_first, exoError.describe(resolve))
        is PlaybackFailure.ExoDecode -> str(R.string.player_error_exo_decode, code)
        is PlaybackFailure.ExoPlay -> str(R.string.player_error_exo_play, code)
        is PlaybackFailure.HardwareFallback -> str(R.string.player_error_hardware_fallback, resolution)
        is PlaybackFailure.HardwareDisabled -> str(R.string.player_error_hardware_disabled, resolution)
        is PlaybackFailure.HardwareFormat -> str(R.string.player_error_hardware_format, resolution, codec)
        is PlaybackFailure.StreamUnavailable -> str(
            R.string.player_error_stream_unavailable,
            if (customUserAgentHint) str(R.string.player_error_custom_user_agent) else "",
        )
        PlaybackFailure.CastUnsupported -> str(R.string.player_error_cast_unsupported)
        PlaybackFailure.MpvOpenDecode -> str(R.string.player_error_mpv_open_decode)
        PlaybackFailure.MpvStreamNeverStarted -> str(R.string.player_error_mpv_stream_never_started)
        is PlaybackFailure.Raw -> message
    }
}

/**
 * A wait the provider itself asked for: it answered `429` with a numeric `Retry-After`, naming the second
 * at which the channel becomes available again. This is not a failure — the engine re-asks by itself when
 * the countdown ends — so it is carried separately from [PlaybackFailure].
 *
 * [message] is the panel's own words (raw provider text, never translated; null when it gave none). The
 * sentence around it and the countdown wording belong to the presentation layer.
 */
data class ProviderBackOff(val httpCode: Int, val message: String?, val secondsLeft: Int)

/** A playback failure broken into semantic reason, technical media details, and raw engine text. */
data class ErrorInfo(val reason: PlayerFailureReason?, val spec: MediaSpec?, val raw: String?)

/**
 * Semantic playback failures. [messageRes] is the translated sentence for each; rendering it — which
 * needs a Context or a Composable — stays with the presentation layer, but the mapping is here so the
 * TV and mobile apps cannot drift into two different explanations of the same failure.
 */
enum class PlayerFailureReason(@StringRes val messageRes: Int) {
    DECODER_BUSY(R.string.player_reason_decoder_busy),
    DECODER_TRANSIENT(R.string.player_reason_decoder_transient),
    UNSUPPORTED_VIDEO(R.string.player_reason_unsupported_video),
    DECODER_MEMORY(R.string.player_reason_decoder_memory),
    DRM(R.string.player_reason_drm),
    HTTP_509(R.string.player_reason_http_509),
    HTTP_403(R.string.player_reason_http_403),
    HTTP_401(R.string.player_reason_http_401),
    HTTP_404(R.string.player_reason_http_404),
    HTTP_400(R.string.player_reason_http_400),
    SSL(R.string.player_reason_ssl),
    FORMAT(R.string.player_reason_format),
    NETWORK(R.string.player_reason_network),
    AUDIO(R.string.player_reason_audio),
    SOFTWARE_FALLBACK(R.string.player_reason_software_fallback),
    COPY_MODE_FALLBACK(R.string.player_reason_copy_mode_fallback),
    ARCHIVE_SOFTWARE_FALLBACK(R.string.player_reason_archive_software_fallback),
    STEREO_FALLBACK(R.string.player_reason_stereo_fallback),
    ONE_SESSION_PROVIDER(R.string.player_reason_one_session_provider),
    MPV_HANDOFF(R.string.player_reason_mpv_handoff),
    LIVE_FALLBACK(R.string.player_reason_live_fallback),
    LIVE_NO_FALLBACK(R.string.player_reason_live_no_fallback),
    STREAM_REPORT(R.string.player_reason_stream_report),
}

/** Maps cryptic playback-failure strings to semantic state. Comparison needles stay English because
 * they are protocol/log inputs, never display text. */
object PlayerErrors {
    private val HTTP_STATUS_RX =
        Regex("""(?:\bhttp(?: error)? |\bresponse code[:= ]+|\bstatus(?: code)?[:= ]+)(\d{3})\b""")
    /** MediaCodec ENOMEM forms: "err -12", "status -12", "error -12" — NOT any "-12" substring
     *  (which matched URLs and timestamps like "-123ms"). MediaCodec usually logs the errno as
     *  unsigned hex instead ("err 0xfffffff4"), so accept that spelling too. */
    private val ENOMEM_RX = Regex("""\b(?:err(?:or)?|status|code)\s*[:=]?\s*(?:-12|0xfffffff4)\b""")

    /** The HTTP status named in a raw error string, or null if it doesn't name one. */
    fun httpStatusIn(raw: String): Int? =
        HTTP_STATUS_RX.find(raw.lowercase())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The HTTP status behind an ExoPlayer load failure, following the cause chain Media3 wraps it in.
     *
     * The counterpart to [httpStatusIn] for the engines that get a real exception rather than a line of
     * log text: mpv only ever hands us a string, Media3 hands us a typed cause chain. Both live here so
     * "what status was this?" has one answer per input shape instead of a copy per engine.
     *
     * Hop-capped because a cause chain can be cyclic.
     */
    fun httpStatusOf(error: Throwable?): Int? {
        var t = error
        var hops = 0
        while (t != null && hops++ < CAUSE_CHAIN_MAX_HOPS) {
            (t as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            t = t.cause
        }
        return null
    }

    private const val CAUSE_CHAIN_MAX_HOPS = 8

    /**
     * The provider's own short refusal text, when one was captured for this status and panel.
     *
     * A panel that answers "Channel limit has been reached. Stop one of your active streams before
     * opening a new channel." has already said the useful thing. Fixed OwnTV wording stays semantic
     * and localized; only genuine provider-authored text is returned raw.
     */
    fun providerMessageFor(raw: String, url: String?): String? {
        val code = url?.let { httpStatusIn(raw) }
        return if (code != null) LiveStreamQuirks.providerMessage(url, code) else null
    }

    /** Prefer genuine provider text, otherwise keep the caller's localized semantic failure. */
    fun visibleFailure(raw: String?, url: String?, fallback: PlaybackFailure): PlaybackFailure {
        val providerText = raw?.let { providerMessageFor(it, url) }
        return providerText?.let(PlaybackFailure::Raw) ?: fallback
    }

    fun classify(raw: String): PlayerFailureReason? {
        val l = raw.lowercase()
        val httpCode = HTTP_STATUS_RX.find(l)?.groupValues?.get(1)
        return when {
            "0x80001000" in l -> PlayerFailureReason.DECODER_BUSY
            "0x80001001" in l -> PlayerFailureReason.DECODER_TRANSIENT
            "0xfffffff3" in l || "0xffffffea" in l || "format_unsupported" in l || "omx_errorformat" in l -> PlayerFailureReason.UNSUPPORTED_VIDEO
            "enomem" in l || "out of memory" in l || "no memory" in l || "insufficient" in l ||
                "0xfffffff4" in l || ENOMEM_RX.containsMatchIn(l) -> PlayerFailureReason.DECODER_MEMORY
            "error_key" in l || "cryptoinfo" in l || "0x80001100" in l || ("drm" in l && "error" in l) -> PlayerFailureReason.DRM
            httpCode == "509" -> PlayerFailureReason.HTTP_509
            httpCode == "429" || httpCode == "458" -> PlayerFailureReason.ONE_SESSION_PROVIDER
            httpCode == "403" -> PlayerFailureReason.HTTP_403
            httpCode == "401" -> PlayerFailureReason.HTTP_401
            httpCode == "404" -> PlayerFailureReason.HTTP_404
            httpCode == "400" -> PlayerFailureReason.HTTP_400
            "certificate verify failed" in l || ("ssl" in l && "certif" in l) || "cert_" in l -> PlayerFailureReason.SSL
            "unrecognized file format" in l || "invalid data found" in l -> PlayerFailureReason.FORMAT
            "connection refused" in l || "connection reset" in l || "timed out" in l || "timeout" in l -> PlayerFailureReason.NETWORK
            "audiotrack" in l || "audiosink" in l || "audioflinger" in l || "audio codec" in l ->
                PlayerFailureReason.AUDIO
            else -> null
        }
    }

    fun reasonFor(raw: String): PlayerFailureReason? = classify(raw)
}
