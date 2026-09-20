package tv.own.owntv.core.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StalkerCredentials
import tv.own.owntv.core.stalker.StalkerDeviceIdentity
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.core.sync.SyncWarning
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.Pin
import tv.own.owntv.core.util.classifySyncFailure
import java.io.File

/**
 * Adding content to a profile, from the first-run wizard or from the settings screen: create the
 * profile, add a source, sync it, finalize it, enqueue whatever was deferred — and, when any of that
 * fails, undo exactly as much as is safe to undo.
 *
 * Every function here suspends and the **caller owns the job**, because "Run in background" is the
 * whole reason the job outlives its screen: the host launches into a scope that survives the wizard
 * (an activity-scoped ViewModel), sets [backgroundHandoff], and walks away while the import finishes.
 * Cancelling the caller's job cancels the import and runs the same cleanup.
 */
class SourceImporter(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val sourceRepository: SourceRepository,
    private val backup: BackupManager,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val importFinalizer: ImportFinalizer,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val stalkerAuth: StalkerAuthManager,
) {

    sealed interface SetupFailure {
        data object InvalidMac : SetupFailure
        data object BackupRead : SetupFailure
        data object Restore : SetupFailure
        data class Sync(val failure: FriendlySyncFailure) : SetupFailure
    }

    sealed interface ImportState {
        data object Idle : ImportState
        data object Running : ImportState
        data class Success(
            val counts: SyncCounts? = null,
            val warnings: List<SyncWarning> = emptyList(),
            val remainder: SyncContentTypes = SyncContentTypes(false, false, false),
            val restoredItems: Int? = null,
            val passwordsOmitted: Boolean = false,
            val skippedSources: Int = 0,
            val invalidLocale: Boolean = false,
            /** The source as it stands after the sync — the host uses it to offer a guide sync. */
            val source: SourceEntity? = null,
        ) : ImportState
        data class Failed(val failure: SetupFailure) : ImportState
        /** Encrypted backup needs the backup password before restoring; [retry] after a wrong attempt.
         *  [sealed] marks a whole-file-encrypted `.own`, where the password is mandatory — there is
         *  nothing to restore without it, so the host hides "Skip". */
        data class NeedPassword(val file: File, val retry: Boolean = false, val sealed: Boolean = false) : ImportState
    }

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<ImportStage?>(null)
    val progress: StateFlow<ImportStage?> = _progress.asStateFlow()

    /**
     * Set when the user left the wizard with "Run in background". A late failure must not silently
     * DELETE the source they think they added — keep it, credentials intact, and wipe only the
     * partial content.
     */
    var backgroundHandoff = false

    private var createdProfileId = -1L
    private var createdProfileName = ""

    /** Creates the profile (not active yet); the rest of onboarding attaches content to it. */
    suspend fun createProfile(name: String, avatarId: Int, isKids: Boolean, pin: String?): Long {
        createdProfileName = name
        createdProfileId = profileDao.insert(
            ProfileEntity(
                name = name,
                avatarColor = 0,
                avatarId = avatarId,
                isKids = isKids,
                pinHash = pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) },
            ),
        )
        return createdProfileId
    }

    /**
     * Attach what follows to a profile that already exists — "add a playlist" from an app that is
     * already set up, where onboarding's brand-new profile is not what the user asked for. Without
     * it the next add would silently create a second profile.
     */
    fun useProfile(id: Long) {
        createdProfileId = id
    }

    suspend fun xtream(
        name: String,
        server: String,
        username: String,
        password: String,
        userAgent: String = "",
        epgUrl: String = "",
        autoRefresh: PlaylistRefresh = PlaylistRefresh.OFF,
        live: SyncScopeChoice = SyncScopeChoice.Now,
        movies: SyncScopeChoice = SyncScopeChoice.Now,
        series: SyncScopeChoice = SyncScopeChoice.Now,
        preferHls: Boolean = false,
        makeDefault: Boolean = false,
    ) {
        val enabled = SyncContentTypes.fromChoices(live, movies, series)
        val priority = SyncContentTypes.priorityFromChoices(live, movies, series)
        runImport(
            autoRefresh, priority, enabledScope = enabled, enqueueRemainder = true,
            requiresNetwork = true, makeDefault = makeDefault,
        ) { profileId ->
            sourceRepository.addXtreamSource(
                profileId = profileId,
                name = name.trim(),
                serverUrl = server.trim(),
                username = username.trim(),
                password = password,
                userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
                syncLive = enabled.live, syncMovies = enabled.movies, syncSeries = enabled.series,
                preferHls = preferHls,
            )
        }
    }

    /** Stalker/MAC portal: the handshake is verified BEFORE the source is saved, so a typo'd portal
     *  or MAC fails with a clear error instead of leaving a dead source on a brand-new profile.
     *  Defaults: Live Now, Movies/Series Later — Stalker VOD has no bulk endpoint. */
    suspend fun stalker(
        name: String,
        portalUrl: String,
        mac: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        userAgent: String = "",
        autoRefresh: PlaylistRefresh = PlaylistRefresh.OFF,
        live: SyncScopeChoice = SyncScopeChoice.Now,
        movies: SyncScopeChoice = SyncScopeChoice.Later,
        series: SyncScopeChoice = SyncScopeChoice.Later,
        makeDefault: Boolean = false,
    ) {
        val canonicalMac = StalkerClient.canonicalizeMac(mac)
        if (canonicalMac == null) {
            _state.value = ImportState.Failed(SetupFailure.InvalidMac)
            return
        }
        val enabled = SyncContentTypes.fromChoices(live, movies, series)
        val priority = SyncContentTypes.priorityFromChoices(live, movies, series)
        runImport(
            autoRefresh, contentTypes = priority, enabledScope = enabled, enqueueRemainder = true,
            requiresNetwork = true, makeDefault = makeDefault,
        ) { profileId ->
            stalkerAuth.testConnection(
                StalkerCredentials(
                    sourceId = STALKER_TEST_SOURCE_ID,
                    portalUrl = portalUrl.trim(),
                    mac = canonicalMac,
                    userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                    deviceIdentity = StalkerDeviceIdentity(
                        serialNumber = serialNumber.trim().takeIf { it.isNotBlank() },
                        deviceId = deviceId.trim().takeIf { it.isNotBlank() },
                        deviceId2 = deviceId2.trim().takeIf { it.isNotBlank() },
                        signature = signature.trim().takeIf { it.isNotBlank() },
                    ),
                ),
            )
            sourceRepository.addStalkerSource(
                profileId, name.trim(), portalUrl.trim(), canonicalMac,
                serialNumber.trim().takeIf { it.isNotBlank() },
                deviceId.trim().takeIf { it.isNotBlank() },
                deviceId2.trim().takeIf { it.isNotBlank() },
                signature.trim().takeIf { it.isNotBlank() },
                userAgent.trim().takeIf { it.isNotBlank() },
                syncLive = enabled.live, syncMovies = enabled.movies, syncSeries = enabled.series,
            )
        }
    }

    suspend fun m3u(
        name: String,
        url: String,
        userAgent: String = "",
        epgUrl: String = "",
        autoRefresh: PlaylistRefresh = PlaylistRefresh.OFF,
        makeDefault: Boolean = false,
    ) = runImport(autoRefresh, requiresNetwork = !url.isLocalPlaylistPath(), makeDefault = makeDefault) { profileId ->
        sourceRepository.addM3uSource(
            profileId = profileId,
            name = name.trim(),
            url = url.trim(),
            userAgent = userAgent.trim().takeIf { it.isNotBlank() },
            epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
        )
    }

    private suspend fun runImport(
        autoRefresh: PlaylistRefresh = PlaylistRefresh.OFF,
        contentTypes: SyncContentTypes = SyncContentTypes(),
        enabledScope: SyncContentTypes = SyncContentTypes(),
        enqueueRemainder: Boolean = false,
        requiresNetwork: Boolean = true,
        /** "Make this the default playlist" — set once the sync succeeded, never on a source that
         *  is about to be cleaned up again. */
        makeDefault: Boolean = false,
        addSource: suspend (Long) -> SourceEntity,
    ) {
        _state.value = ImportState.Running
        _progress.value = null
        var source: SourceEntity? = null
        try {
            if (requiresNetwork && !connectivity.isOnlineNow()) {
                _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(null, online = false)))
                return
            }
            val profileId = createdProfileId.takeIf { it > 0 } ?: ensureFallbackProfile()
            source = addSource(profileId)
            val freshSync = source.lastSyncAt == null
            val remainder = if (enqueueRemainder) {
                enabledScope.remainderAfter(contentTypes)
            } else {
                SyncContentTypes(live = false, movies = false, series = false)
            }
            settings.setPlaylistAutoRefresh(source.id, autoRefresh)
            when (val result = sourceRepository.sync(source, onProgress = { _progress.value = it }, contentTypes = contentTypes)) {
                is SyncResult.Success -> {
                    // Just the playlist content — EPG is added separately (Settings → EPG sources).
                    val counts = importFinalizer.finalize(source, deferIndexes = freshSync)
                    if (makeDefault) settings.setDefaultSource(source.id)
                    val syncedSource = sourceDao.getById(source.id) ?: source
                    if (enqueueRemainder) enqueueRemainderSync(source, contentTypes, enabledScope)
                    if (freshSync && !remainder.hasAny) catalogSyncScheduler.enqueueContentIndexBuild(reason = "fresh_add")
                    _state.value = ImportState.Success(
                        counts = counts,
                        warnings = result.warnings,
                        remainder = remainder,
                        source = syncedSource,
                    )
                    runCatching { launcherIntegrationRepository.refreshProfile(profileId) }
                }
                is SyncResult.Failed -> {
                    cleanupFailedAdd(source)
                    _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(result.message, connectivity.isOnlineNow())))
                }
                SyncResult.Cancelled -> {
                    cleanupFailedAdd(source)
                    _state.value = ImportState.Idle
                }
            }
        } catch (c: CancellationException) {
            cleanupFailedAdd(source)
            _state.value = ImportState.Idle
            _progress.value = null
            throw c
        } catch (e: Exception) {
            cleanupFailedAdd(source)
            _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(e.message, connectivity.isOnlineNow())))
        }
    }

    private fun String.isLocalPlaylistPath(): Boolean =
        startsWith("/") || startsWith("file://") || startsWith("content://")

    private fun enqueueRemainderSync(source: SourceEntity, priority: SyncContentTypes, enabledScope: SyncContentTypes) {
        val remainder = enabledScope.remainderAfter(priority)
        if (remainder.hasAny) {
            catalogSyncScheduler.enqueueSync(source.id, reason = "add_remainder", contentTypes = remainder, completesInitialSync = true)
        }
    }

    /** Playlists belonging to unlocked (no-PIN) profiles that aren't already on the new profile. */
    suspend fun availableExistingSources(): List<SourceEntity> {
        val unlocked = profileDao.getAllOnce().filter { it.pinHash == null && it.id != createdProfileId }.map { it.id }.toSet()
        if (unlocked.isEmpty()) return emptyList()
        val links = sourceDao.allLinks()
        val fromUnlocked = links.filter { it.profileId in unlocked }.map { it.sourceId }.toSet()
        val alreadyMine = links.filter { it.profileId == createdProfileId }.map { it.sourceId }.toSet()
        val wanted = fromUnlocked - alreadyMine
        return sourceDao.getAllOnce().filter { it.id in wanted }
    }

    /**
     * Link the chosen existing sources to the new profile (shared content, separate favorites and
     * history), then re-sync each one so its catalog is fresh — exactly like adding a brand-new
     * source. Drives the same [state] and [progress] as an add, so the host shows one import screen.
     */
    suspend fun linkExisting(sourceIds: Set<Long>) {
        _state.value = ImportState.Running
        _progress.value = null
        try {
            val pid = createdProfileId.takeIf { it > 0 } ?: ensureFallbackProfile()
            sourceIds.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = pid, sourceId = it)) }
            val sources = sourceDao.getAllOnce().filter { it.id in sourceIds }
            var total = SyncCounts(0, 0, 0, 0)
            var failure: String? = null
            val warnings = mutableListOf<SyncWarning>()
            for (source in sources) {
                when (val result = sourceRepository.sync(source, onProgress = { _progress.value = it })) {
                    is SyncResult.Success -> {
                        val c = importFinalizer.finalize(source)
                        total = SyncCounts(total.channels + c.channels, total.movies + c.movies, total.series + c.series, total.epg + c.epg)
                        warnings += result.warnings
                    }
                    is SyncResult.Failed -> failure = result.message
                    SyncResult.Cancelled -> {}
                }
            }
            runCatching { launcherIntegrationRepository.refreshProfile(pid) }
            _state.value = failure?.let { ImportState.Failed(SetupFailure.Sync(classifySyncFailure(it, connectivity.isOnlineNow()))) }
                ?: ImportState.Success(counts = total, warnings = warnings)
        } catch (c: CancellationException) {
            _state.value = ImportState.Idle
            _progress.value = null
            throw c
        } catch (e: Exception) {
            _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(e.message, connectivity.isOnlineNow())))
        }
    }

    /**
     * Restore from a backup file. Encrypted backups first ask for the backup password via
     * [ImportState.NeedPassword]; returns true only when data was actually restored.
     *
     * [sections] is what the user chose to bring back, and defaults to all of it. Setup used to have
     * no say at all — Backup & Restore in Settings has offered the tick-list for as long as it has
     * existed, but the first-run wizard called this with no sections and therefore took everything,
     * so "my playlists but not that device's settings" was not expressible on the one screen where
     * it is asked for most. The caller carries the choice across [ImportState.NeedPassword] and hands
     * it back to [restoreWithPassword], because a sealed file is chosen from before it is opened.
     */
    suspend fun importBackup(
        file: File,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
    ): Boolean {
        _state.value = ImportState.Running
        // A sealed .own reveals nothing before it is decrypted — ask for the password first.
        if (backup.isSealed(file)) {
            _state.value = ImportState.NeedPassword(file, sealed = true)
            return false
        }
        val inspection = backup.sectionsIn(file).getOrElse {
            _state.value = ImportState.Failed(SetupFailure.BackupRead)
            return false
        }
        if (inspection.encrypted) {
            _state.value = ImportState.NeedPassword(file)
            return false
        }
        return doRestore(file, null, sections)
    }

    /** Continue an encrypted restore once the user provides (or skips, password = null) the passphrase. */
    suspend fun restoreWithPassword(
        file: File,
        password: String?,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
    ): Boolean {
        _state.value = ImportState.Running
        return doRestore(file, password, sections)
    }

    private suspend fun doRestore(
        file: File,
        password: String?,
        sections: Set<BackupManager.Section>,
    ): Boolean =
        backup.import(file, sections, backupPassword = password).fold(
            onSuccess = { summary ->
                _state.value = ImportState.Success(
                    restoredItems = summary.items,
                    passwordsOmitted = password.isNullOrBlank(),
                    skippedSources = summary.skippedSources,
                    invalidLocale = summary.invalidLocale,
                )
                true
            },
            onFailure = {
                if (it is BackupManager.WrongPasswordException) {
                    _state.value = ImportState.NeedPassword(file, retry = true, sealed = backup.isSealed(file))
                } else {
                    _state.value = ImportState.Failed(SetupFailure.Restore)
                }
                false
            },
        )

    private suspend fun ensureFallbackProfile(): Long {
        if (createdProfileId > 0) return createdProfileId
        createdProfileId = profileDao.insert(ProfileEntity(name = createdProfileName, avatarColor = 0, avatarId = 0))
        return createdProfileId
    }

    fun reset() {
        _state.value = ImportState.Idle
        _progress.value = null
    }

    /**
     * Completes onboarding: the new profile becomes active, which is what routes the app into the
     * shell — and what every screen reading `activeProfileSources()` waits for. Returns the active
     * profile id, or null when there is none.
     */
    suspend fun finish(): Long? {
        if (createdProfileId > 0) settings.setActiveProfile(createdProfileId)
        return settings.activeProfileId.first().takeIf { it >= 0L }
    }

    private suspend fun cleanupFailedAdd(source: SourceEntity?) {
        if (source == null) return
        withContext(NonCancellable) {
            catalogSyncScheduler.cancelSync(source.id)
            if (backgroundHandoff) {
                // The user already entered the app with "Run in background" — deleting the source
                // would make the playlist they just added silently vanish. Keep it so they can
                // re-sync from Settings → Playlists; wipe only the partial content (a never-synced
                // source re-syncs via insertFresh, which assumes empty tables — leftovers duplicate).
                runCatching { sourceRepository.clearSourceContent(source.id) }
            } else {
                runCatching { sourceRepository.deleteSource(source) }
                runCatching { settings.setPlaylistAutoRefresh(source.id, PlaylistRefresh.OFF) }
            }
        }
    }

    private companion object {
        /** Sentinel sourceId for the pre-save Stalker handshake. */
        const val STALKER_TEST_SOURCE_ID = -1L
    }
}
