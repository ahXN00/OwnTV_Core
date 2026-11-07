package tv.own.owntv.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Whether the app can be seen and heard: its activity is started **and** the television it plays to is
 * still there. Pure, with the clock passed in; [PlaybackVisibilityWatch] feeds it on a device.
 *
 * Some boxes keep their activity running when the television is switched off — only the HDMI sink goes
 * away — so the "left the app" path that frees and later re-tunes every stream never ran there, and the
 * channel came back frozen or minutes behind. Losing every audible output now counts as leaving, and
 * getting one back as returning. Both signals go through this one machine, so a box that does stop the
 * activity on standby behaves exactly as before and the two paths can never both fire.
 *
 * An output must stay gone for [CONFIRM_MS] before it counts, and a display change (a refresh-rate
 * switch renegotiates HDMI and drops the sink for a moment) holds the decision off for
 * [DISPLAY_CHANGE_GRACE_MS] after it.
 */
class PlaybackVisibility {

    enum class Change { HIDDEN, VISIBLE }

    private var started = false
    private var outputPresent = true
    private var absentSinceMs: Long? = null
    private var outputLost = false
    private var lastDisplayChangeMs: Long? = null

    /** What the app last reported. Starts hidden: the first start is the first [Change.VISIBLE]. */
    var visible = false
        private set

    fun activityStarted(): Change? { started = true; return settle() }

    fun activityStopped(): Change? { started = false; return settle() }

    fun outputChanged(present: Boolean, nowMs: Long): Change? {
        outputPresent = present
        if (present) {
            absentSinceMs = null
            outputLost = false
        } else if (absentSinceMs == null) {
            absentSinceMs = nowMs
        }
        return settle()
    }

    fun displayChanged(nowMs: Long) { lastDisplayChangeMs = nowMs }

    /** When an absent output becomes a confirmed loss, or null when nothing is pending. */
    fun deadlineMs(): Long? {
        val since = absentSinceMs ?: return null
        if (outputLost) return null
        val grace = lastDisplayChangeMs?.let { it + DISPLAY_CHANGE_GRACE_MS } ?: Long.MIN_VALUE
        return maxOf(since + CONFIRM_MS, grace)
    }

    /** Call at [deadlineMs]. */
    fun tick(nowMs: Long): Change? {
        val deadline = deadlineMs() ?: return null
        if (!outputPresent && nowMs >= deadline) outputLost = true
        return settle()
    }

    private fun settle(): Change? {
        val now = started && !outputLost
        if (now == visible) return null
        visible = now
        return if (now) Change.VISIBLE else Change.HIDDEN
    }

    companion object {
        const val CONFIRM_MS = 6_000L
        const val DISPLAY_CHANGE_GRACE_MS = 10_000L
    }
}

/**
 * [PlaybackVisibility] on a device: the activity reports start/stop, and this watches the audio outputs,
 * `ACTION_HDMI_AUDIO_PLUG` and display changes itself. [listeners] hear each change once, newest first —
 * the order lifecycle observers ran in before the activity's own code, so a screen reads what is playing
 * before the activity frees the engines. Main thread only; one per process.
 */
class PlaybackVisibilityWatch(context: Context) {

    private val app = context.applicationContext
    private val machine = PlaybackVisibility()
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var hdmiPlugged: Boolean? = null
    private var lastPresent = true
    private val tick = Runnable { dispatch(machine.tick(now())) }

    val visible: Boolean get() = machine.visible

    init {
        audio?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = readOutputs()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = readOutputs()
        }, handler)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                hdmiPlugged = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1) != 0
                readOutputs()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { machine.displayChanged(now()); reschedule() }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }, handler)
        readOutputs()
    }

    fun addListener(listener: (Boolean) -> Unit) { listeners += listener }

    fun removeListener(listener: (Boolean) -> Unit) { listeners -= listener }

    fun activityStarted() = dispatch(machine.activityStarted())

    fun activityStopped() = dispatch(machine.activityStopped())

    private fun readOutputs() {
        val types = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type } ?: return
        val present = AudioOutputPresence.present(types, hdmiPlugged)
        if (present != lastPresent) Log.i(TAG, if (present) "audio output back" else "no audio output left")
        lastPresent = present
        dispatch(machine.outputChanged(present, now()))
    }

    private fun dispatch(change: PlaybackVisibility.Change?) {
        reschedule()
        when (change) {
            PlaybackVisibility.Change.HIDDEN -> {
                Log.i(TAG, "hidden — freeing playback")
                listeners.asReversed().toList().forEach { it(false) }
            }
            PlaybackVisibility.Change.VISIBLE -> {
                Log.i(TAG, "visible — restoring playback")
                listeners.asReversed().toList().forEach { it(true) }
            }
            null -> Unit
        }
    }

    private fun reschedule() {
        handler.removeCallbacks(tick)
        machine.deadlineMs()?.let { handler.postAtTime(tick, it) }
    }

    private fun now() = SystemClock.uptimeMillis()

    private companion object {
        const val TAG = "OwnTV-Visibility"
    }
}
