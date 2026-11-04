package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.timeshift.TimeshiftStorage

/**
 * The start-of-process work the engines depend on, for every host. It used to live in the TV app's
 * `Application` and shell only, so on the phone the Detailed diagnostics switch did nothing and a
 * restored latency or frame-rate choice read as Balanced / Off.
 *
 * Call once from `Application.onCreate`, after Koin has started. Everything here is fire-and-forget on
 * [scope]: nothing on the launch path waits for it.
 */
object PlaybackStartup {

    fun start(context: Context, scope: CoroutineScope, settings: SettingsRepository) {
        // Start reading the engines' settings now, so an engine built later is born with them.
        val snapshot = PlaybackSettings.of(settings)
        // Night mode / volume levelling are read by every ExoPlayer audio processor on every buffer.
        scope.launch {
            snapshot.collect { s ->
                if (s == null) return@collect
                AudioDynamics.nightMode = s.nightMode
                AudioDynamics.levelling = s.volumeLevelling
            }
        }
        // Detailed playback logging follows the setting for the whole process — not from whenever a
        // live engine first happens to be built.
        scope.launch {
            settings.detailedDiagnostics.collect { on ->
                LiveDiagnosticsLog.enabled = on || CoreBuildInfo.debug || CoreBuildInfo.diagnosticBuild
            }
        }
        scope.launch { settings.runOneTimeMigrations() }
        // Once: list orders saved by older versions held every item of the list, so the sort chip could not
        // sort it. They are dropped (Favorites kept); the lists start again from the playlist's order.
        scope.launch {
            runCatching {
                if (!settings.listOrdersReset()) {
                    GlobalContext.get().get<ContentOrderDao>().dropListOrdersExcept(ContentOrderEntity.FAV_CONTEXT)
                    settings.markListOrdersReset()
                }
            }
        }
        // Timeshift buffers never outlive the run that wrote them.
        scope.launch { TimeshiftStorage.clearAll(context) }
    }
}
