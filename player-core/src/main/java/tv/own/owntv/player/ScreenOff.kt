package tv.own.owntv.player

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import tv.own.owntv.core.R

/**
 * The sleep timer's "and turn the screen off" (N17 follow-up).
 *
 * An app cannot put a device to sleep — that takes a permission only the firmware holds. The one door
 * open to an ordinary app is the device-admin "force lock" policy: `lockNow()` is the power button,
 * the screen goes off and, on most Android TVs, the set goes to standby. The user grants it once in a
 * system screen, and **the grant is the setting**: nothing is stored, so there is nothing to back up
 * or to restore onto a device that never granted it. Unticking the row gives the grant back.
 */
class ScreenOff(private val context: Context) {
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    private val admin get() = ComponentName(context, Admin::class.java)

    fun isAllowed(): Boolean = dpm?.isAdminActive(admin) == true

    /** The system screen that asks for the grant; start it for a result and re-read [isAllowed]. */
    fun requestIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.player_sleep_timer_screen_off_explanation))

    /** Give the grant back. While it is held, Android will not uninstall the app without asking first. */
    fun revoke() {
        runCatching { dpm?.removeActiveAdmin(admin) }
    }

    /** Called when the timer has stopped playback. A no-op unless the user granted it. */
    fun turnOffIfAllowed() {
        if (!isAllowed()) return
        runCatching { dpm?.lockNow() }.onFailure { Log.w(TAG, "lockNow failed", it) }
    }

    /** Declared in this module's manifest; asks for "force lock" and nothing else. */
    class Admin : DeviceAdminReceiver()

    private companion object {
        const val TAG = "ScreenOff"
    }
}
