package tv.own.owntv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.player.ArchiveDecodeStore
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The start-of-process work the engines depend on, for every host. It used to live in the TV app's
 * `Application` and shell only, so on the phone the Detailed diagnostics switch did nothing, catch-up
 * software-decode lessons were forgotten on every run, and a restored latency or frame-rate choice
 * read as Balanced / Off.
 *
 * Call once from `Application.onCreate`, after Koin has started. Everything here is fire-and-forget on
 * [scope]: nothing on the launch path waits for it.
 */
object PlaybackStartup {

    fun start(scope: CoroutineScope, settings: SettingsRepository, archiveStore: ArchiveDecodeStore) {
        // S15 — start reading the engines' settings now, so an engine built later is born with them.
        val snapshot = PlaybackSettings.of(settings)
        // Night mode / volume levelling (P14) are read by every ExoPlayer audio processor on every buffer.
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
        // Seed the one persisted playback quirk (panels whose catch-up archive needs a software
        // decoder). One small DataStore read; it is consulted only when an archive opens.
        scope.launch {
            val known = runCatching { archiveStore.hosts() }.getOrDefault(emptySet())
            LiveStreamQuirks.installArchivePersistence(known) { host ->
                scope.launch { runCatching { archiveStore.remember(host) } }
            }
        }
        scope.launch { settings.runOneTimeMigrations() }
    }
}
