package tv.own.owntv.core.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * A recording is due: start the worker, and re-arm for whatever comes after it.
 *
 * Starting a foreground service from here is allowed — an exact alarm grants the app a short window
 * in which it may, which is the whole reason the alarm is exact. The boot receiver below is the case
 * where it is **not** allowed.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECORDING_DUE) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                RecordingWorker.kick(appContext)
                runCatching { GlobalContext.get().get<RecordingScheduler>().rearmAll() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECORDING_DUE = "tv.own.owntv.core.action.RECORDING_DUE"
    }
}

/**
 * After a reboot, after the app is replaced, and when the exact-alarm permission changes:
 * **re-arm the alarms, and nothing else.**
 *
 * Alarms do not survive a reboot, so without this every scheduled recording would quietly evaporate
 * the first time the television was switched off at the wall.
 *
 * **It must not start a recording, even one that is due right now.** An app targeting Android 15
 * cannot start a `mediaPlayback` or `dataSync` foreground service from a `BOOT_COMPLETED` receiver;
 * trying throws, and the throw would take the re-arming down with it. A recording whose window is
 * already open is armed for *now* instead, and the alarm that fires a moment later is allowed to
 * start the service.
 *
 * The exact-alarm case matters as much as boot and is easy to miss: when the user revokes the
 * permission Android **cancels every exact alarm the app has set**, so without re-arming here every
 * future recording would be silently lost at the moment of revocation rather than merely becoming
 * less punctual.
 */
class RecordingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { GlobalContext.get().get<RecordingScheduler>().rearmAll() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // API 31+. Named as a literal because the constant does not exist on lower API levels.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** Shared by both receivers: a broadcast's own thread must not be held while the database is read. */
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
