package tv.own.owntv.core.brand

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * "Restart now" after an icon change, done from outside the app's own process.
 *
 * The app cannot reopen itself and then die: the screen it asked for is started in the process being
 * killed, and Android drops it with the process — the icon changed and the app stayed closed. This
 * invisible activity runs in its own `:restart` process (see core's manifest), so it outlives the old
 * one: it ends the old process, opens the app through the new launcher activity and ends itself.
 * Each app's `Application.onCreate` returns at once in this process ([AppIconSwitcher.isRestartProcess]).
 */
class AppRestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra(EXTRA_PID, -1)
        if (oldPid > 0 && oldPid != Process.myPid()) Process.killProcess(oldPid)
        val target = intent.getStringExtra(EXTRA_COMPONENT)?.let(ComponentName::unflattenFromString)
        if (target != null) {
            // A task of its own, never the old one: that task was started by the icon just disabled, and
            // when Android applies the switch it clears such a task — with the reopened app inside it
            // ("disabled-package", seen on the TCL a second after the restart).
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            )
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    internal companion object {
        const val EXTRA_PID = "pid"
        const val EXTRA_COMPONENT = "component"
    }
}
