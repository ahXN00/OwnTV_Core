package tv.own.owntv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/**
 * mpv's multichannel `audio-channels` list, taken from what the audio output itself says it plays.
 *
 * `auto-safe` cannot do this on Android: mpv's AudioTrack output ignores the "safe" part and always
 * offers up to 7.1, so a sink's real limit was only found after it failed (the #25 class, caught by the
 * stereo latch). The output's own PCM channel masks are the limit instead — a TCL panel's speakers list
 * stereo and 5.1, so 7.1 is downmixed by mpv rather than handed to a HAL that never claimed it.
 */
internal object MpvAudioChannels {

    /** What mpv got before: used whenever the output does not say what it takes. */
    const val UNKNOWN = "auto-safe"

    /** mpv's list for an output whose PCM takes [counts] channels; [UNKNOWN] when it reported none. */
    fun listFor(counts: Set<Int>): String {
        if (counts.isEmpty()) return UNKNOWN
        return listOfNotNull("stereo", "5.1".takeIf { 6 in counts }, "7.1".takeIf { 8 in counts }).joinToString(",")
    }

    /** The PCM channel counts of the output media plays on; empty when that cannot be told. */
    fun outputPcmChannelCounts(context: Context): Set<Int> = runCatching {
        val am = context.getSystemService(AudioManager::class.java)
        val device = if (Build.VERSION.SDK_INT >= 33) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(media).firstOrNull()
        } else {
            // No routing query before Android 13: only an HDMI output is a known destination.
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HDMI_TYPES }
        } ?: return@runCatching emptySet()
        if (Build.VERSION.SDK_INT >= 31) {
            device.audioProfiles.filter { it.format == AudioFormat.ENCODING_PCM_16BIT }
                .flatMap { p -> p.channelMasks.map(Integer::bitCount) }.toSet()
        } else {
            device.channelCounts.toSet()
        }
    }.getOrDefault(emptySet())

    // TYPE_HDMI_EARC (API 31) is only compared against, never called: an older device never reports it.
    @android.annotation.SuppressLint("InlinedApi")
    private val HDMI_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
    )
}
