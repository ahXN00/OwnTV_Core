package tv.own.owntv.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The kind of output sound is going to — each has a latency of its own (a Bluetooth headset adds a
 *  couple of hundred milliseconds, a soundbar on ARC tens), so each keeps its own extra audio delay. */
// The newer device-type constants are only compared with what the platform reports, never passed to it,
// so they are safe on every API level.
@SuppressLint("InlinedApi")
enum class AudioOutputKind {
    HDMI, ARC, BLUETOOTH, SPEAKER, OTHER;

    companion object {
        /**
         * The kind for a set of [AudioDeviceInfo] types. Given the devices media is routed to, that is the
         * answer; given every connected output (before Android 13), it is the one Android routes media to
         * first: Bluetooth, then a receiver on ARC/eARC, then HDMI, then wired/USB, then the speakers.
         */
        fun of(types: Collection<Int>): AudioOutputKind = when {
            types.any { it in BLUETOOTH_TYPES } -> BLUETOOTH
            types.any { it == AudioDeviceInfo.TYPE_HDMI_ARC || it == AudioDeviceInfo.TYPE_HDMI_EARC } -> ARC
            types.any { it == AudioDeviceInfo.TYPE_HDMI } -> HDMI
            types.any { it in OTHER_TYPES } -> OTHER
            else -> SPEAKER
        }

        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
        private val OTHER_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
        )
    }
}

/** Extra audio delay per [AudioOutputKind], added on top of the Audio sync setting and any per-item
 *  delay. Stored as `KIND=ms,KIND=ms`; an output with no entry adds nothing. */
data class OutputAudioDelays(private val byKind: Map<AudioOutputKind, Int>) {

    fun offsetFor(kind: AudioOutputKind): Int = byKind[kind] ?: 0

    fun with(kind: AudioOutputKind, ms: Int): OutputAudioDelays {
        val clamped = ms.coerceIn(-MAX_MS, MAX_MS)
        return OutputAudioDelays(if (clamped == 0) byKind - kind else byKind + (kind to clamped))
    }

    fun encode(): String = byKind.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}=${it.value}" }

    companion object {
        const val MAX_MS = 5_000
        val NONE = OutputAudioDelays(emptyMap())

        fun parse(raw: String?): OutputAudioDelays {
            if (raw.isNullOrBlank()) return NONE
            var out = NONE
            for (part in raw.split(',')) {
                val kind = AudioOutputKind.entries.firstOrNull { it.name == part.substringBefore('=') } ?: continue
                val ms = part.substringAfter('=', "").toIntOrNull() ?: continue
                out = out.with(kind, ms)
            }
            return out
        }
    }
}

/** The output media plays to right now, kept current as devices come and go. One per process. */
object AudioOutputWatch {
    @Volatile private var state: MutableStateFlow<AudioOutputKind>? = null

    fun current(context: Context): StateFlow<AudioOutputKind> {
        state?.let { return it.asStateFlow() }
        return synchronized(this) {
            state ?: start(context.applicationContext).also { state = it }
        }.asStateFlow()
    }

    private fun start(context: Context): MutableStateFlow<AudioOutputKind> {
        val am = context.getSystemService(AudioManager::class.java)
        val flow = MutableStateFlow(read(am))
        am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { flow.value = read(am) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { flow.value = read(am) }
        }, Handler(Looper.getMainLooper()))
        return flow
    }

    private fun read(am: AudioManager?): AudioOutputKind {
        if (am == null) return AudioOutputKind.SPEAKER
        val routed = if (Build.VERSION.SDK_INT >= 33) runCatching {
            am.getAudioDevicesForAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
        }.getOrNull() else null
        val devices = routed?.takeIf { it.isNotEmpty() } ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return AudioOutputKind.of(devices.map { it.type })
    }
}
