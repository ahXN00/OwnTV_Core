package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import tv.own.owntv.core.player.FfmpegAudioSetting
import tv.own.owntv.player.ffmpeg.FfmpegLibrary

/**
 * ExoPlayer's FFmpeg audio decoder ([tv.own.owntv.player.ffmpeg]), running on the FFmpeg the mpv engine
 * already ships (`libowntvffmpeg.so` in OwnTV_libmpv). It sits **behind** the device's MediaCodec
 * decoders in [OwnTVRenderersFactory], so the TV's chip and passthrough always win; FFmpeg only decodes
 * what the chip cannot (DTS, TrueHD, MP2 on many boxes). Used only while the user's "FFmpeg audio
 * decoder" setting is on ([FfmpegAudioSetting], off by default); off, or on an engine without the
 * library, it reports unavailable and ExoPlayer has only the device's decoders.
 */
@UnstableApi
object FfmpegAudio {
    const val TAG = "OwnTV-FfmpegAudio"

    /**
     * The setting this process started with. Off is ExoPlayer's audio path from before FFmpeg audio, in
     * full: no FFmpeg decoder, no mid-playback re-pick of the audio decoder, and only audio within a
     * decoder's advertised limits counts as playable ([ExoAudioGate.anyPlayableAudio]).
     */
    val switchedOn: Boolean get() = FfmpegAudioSetting.active

    /** FFmpeg audio is on and its library loaded. */
    val available: Boolean get() = switchedOn && libraryLoaded

    /** Loads the library on first use and logs the outcome once, so a broken engine contract shows. */
    private val libraryLoaded: Boolean by lazy {
        val ok = FfmpegLibrary.isAvailable()
        android.util.Log.i(TAG, "available=$ok lavc=${FfmpegLibrary.getVersion()}")
        ok
    }

    private val supported = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** FFmpeg can decode this audio MIME type here. */
    fun supports(mime: String): Boolean =
        available && supported.getOrPut(mime) { FfmpegLibrary.supportsFormat(mime) }

    private val deviceDecodable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** The device offers a MediaCodec decoder for this audio MIME type, whatever its advertised limits. */
    fun deviceDecodes(mime: String): Boolean = deviceDecodable.getOrPut(mime) {
        runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrDefault(false)
    }

    /** Whether [FfmpegAudioRenderer][tv.own.owntv.player.ffmpeg.FfmpegAudioRenderer] offers to decode [mime]. */
    fun takes(mime: String): Boolean = takes(mime, ::deviceDecodes, ::supports)

    /**
     * FFmpeg decodes only what the device has no decoder for. Media3 gives a track to the renderer that
     * rates it best and uses list order only to break ties, so FFmpeg answering "handled" would take every
     * track a device decoder rates "exceeds capabilities" — tracks the chip plays fine — away from it.
     */
    fun takes(mime: String, deviceDecoder: (String) -> Boolean, ffmpeg: (String) -> Boolean): Boolean =
        !deviceDecoder(mime) && ffmpeg(mime)
}

/** Which audio ExoPlayer can play — the film engine's hand-off gate and the "no playable audio" check. */
object ExoAudioGate {

    /** Why the film gate let a codec through, or [UNSUPPORTED]. */
    enum class Verdict(val safe: Boolean) {
        ALLOWLIST(true), DEVICE_DECODER(true), FFMPEG(true), PASSTHROUGH(true), UNSUPPORTED(false)
    }

    /**
     * mpv codec names every device decodes (MediaCodec or Media3's own), matched by prefix. MP2 is not
     * one of them: Media3 has no MP2→MP3 substitution and many decoders declare only MP3, so it goes
     * through the checks below like DTS and TrueHD.
     */
    private val ALWAYS_DECODABLE = setOf("aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac", "pcm", "alac")

    /** The Android MIME type for an mpv/FFmpeg audio codec name, or null when Media3 has none (MLP). */
    fun mimeFor(codec: String): String? = when {
        codec.startsWith("eac3") || codec.startsWith("e-ac-3") -> MimeTypes.AUDIO_E_AC3
        codec.startsWith("ac3") || codec.startsWith("ac-3") -> MimeTypes.AUDIO_AC3
        codec.startsWith("dts") || codec.startsWith("dca") -> MimeTypes.AUDIO_DTS
        codec.startsWith("truehd") -> MimeTypes.AUDIO_TRUEHD
        codec.startsWith("mp2") -> MimeTypes.AUDIO_MPEG_L2
        codec.startsWith("mp3") -> MimeTypes.AUDIO_MPEG
        codec.startsWith("aac") -> MimeTypes.AUDIO_AAC
        codec.startsWith("opus") -> MimeTypes.AUDIO_OPUS
        codec.startsWith("vorbis") -> MimeTypes.AUDIO_VORBIS
        codec.startsWith("flac") -> MimeTypes.AUDIO_FLAC
        codec.startsWith("alac") -> MimeTypes.AUDIO_ALAC
        else -> null
    }

    /**
     * Can ExoPlayer play audio mpv reports as [codec] (lower case)? Through a device decoder, FFmpeg, or
     * bitstreamed to a receiver that takes it — the last only when the caller allows passthrough at all.
     */
    fun verdict(
        codec: String,
        deviceDecoder: (String) -> Boolean,
        ffmpeg: (String) -> Boolean,
        passthrough: (String) -> Boolean,
    ): Verdict {
        if (ALWAYS_DECODABLE.any { codec.startsWith(it) }) return Verdict.ALLOWLIST
        val mime = mimeFor(codec) ?: return Verdict.UNSUPPORTED
        return when {
            deviceDecoder(mime) -> Verdict.DEVICE_DECODER
            ffmpeg(mime) -> Verdict.FFMPEG
            passthrough(mime) -> Verdict.PASSTHROUGH
            else -> Verdict.UNSUPPORTED
        }
    }

    /**
     * Some audio track ExoPlayer can play. With [exceedingCounts] (FFmpeg audio on), exceeding the
     * decoder's *advertised* channels, rate or profile counts: Media3's selector picks and plays such a
     * track anyway. Without it, only a track within those limits counts, as before FFmpeg audio.
     */
    fun anyPlayableAudio(tracks: Tracks, exceedingCounts: Boolean): Boolean = tracks.groups.any { g ->
        g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.isTrackSupported(it, exceedingCounts) }
    }
}
