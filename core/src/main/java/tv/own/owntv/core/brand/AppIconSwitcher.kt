package tv.own.owntv.core.brand

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Switches which of the host app's six launcher activities is enabled, i.e. which [AppIcon] the home
 * screen, the TV banner and the launch screen show.
 *
 * The *applied* icon is read from [PackageManager], never stored, so it cannot drift from what the
 * launcher shows. A choice is pending exactly when the saved [SettingsRepository.appIcon] differs
 * from it. Pending choices are applied only when the app is in the background, because some launchers
 * kill the app when a launcher component changes.
 */
object AppIconSwitcher {

    /**
     * The host app's `MainActivity` class name. Assigned in `Application.onCreate` before Koin starts, like
     * the other core hooks. The five colour activities are this name + [AppIcon.activitySuffix].
     */
    var mainActivityClass: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun component(context: Context, icon: AppIcon) =
        ComponentName(context.packageName, mainActivityClass + icon.activitySuffix)

    /** The icon whose launcher activity is enabled right now. */
    fun applied(context: Context): AppIcon {
        if (mainActivityClass.isEmpty()) return AppIcon.DEFAULT
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { icon ->
            when (pm.getComponentEnabledSetting(component(context, icon))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                // Not touched yet: the manifest decides, and only the default (Eggshell) is enabled there.
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
                else -> false
            }
        } ?: AppIcon.DEFAULT
    }

    /**
     * The launcher activity that is enabled right now. An `Intent` into the app (a notification) must use
     * this, not `MainActivity`: with another colour chosen, `MainActivity` is disabled and opens nothing.
     */
    fun launchComponent(context: Context): ComponentName = component(context, applied(context))

    /** Enables [icon]'s activity first, then disables the rest, so there is never a moment with none. */
    fun apply(context: Context, icon: AppIcon) {
        if (mainActivityClass.isEmpty()) return
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * "Restart now": applies [icon], opens the app again through its new launcher activity and ends this
     * process. The caller must have finished saving the choice first.
     */
    fun restartWith(activity: Activity, icon: AppIcon) {
        apply(activity, icon)
        activity.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component(activity, icon))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finishAffinity()
        Process.killProcess(Process.myPid())
    }

    /**
     * Applies a pending choice each time the last visible activity stops ("Later", the first-run pick, a
     * restore). Called once from `Application.onCreate`, after Koin.
     */
    fun start(app: Application, settings: SettingsRepository) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) { started++ }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started > 0 || activity.isChangingConfigurations) return
                scope.launch {
                    val chosen = settings.appIcon.first()
                    if (chosen != applied(app)) apply(app, chosen)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
