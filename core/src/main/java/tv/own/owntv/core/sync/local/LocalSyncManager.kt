package tv.own.owntv.core.sync.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.sync.work.LocalAutoSyncScheduler

/** Which way the data goes. Always the user's explicit choice — never inferred. */
enum class SyncDirection {
    /** This device's data goes to the other one. Nothing here changes. */
    SEND,

    /** The other device's data comes here. Nothing there changes. */
    RECEIVE,

    /** Both, receive first: the far side's changes land here, then this device's go back. */
    MERGE,
}

/** How far along a sync is, for the screen driving it. */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data object Connecting : SyncProgress
    data object Preparing : SyncProgress
    data object Transferring : SyncProgress
    data object Applying : SyncProgress
    data class Done(val received: BackupManager.ImportSummary?, val sent: Boolean) : SyncProgress
    data class Failed(val reason: SyncFailure) : SyncProgress
}

/**
 * A container sitting in the cache, what applying it would change, and the key that opens it.
 *
 * The key travels with the file because the user no longer supplies one: it is either the far side's
 * hosting key (a pull) or the secret the pairing established (a push). The screen carries it from the
 * dry run to the apply without ever showing it to anyone.
 */
data class SyncPayload(
    val file: File,
    val preview: BackupManager.Preview,
    val password: String?,
)

sealed interface SyncFailure {
    /** Nothing answered at that address — wrong network, or the other screen is closed. */
    data object Unreachable : SyncFailure

    /** The PIN was wrong, or the pairing was removed on the other device. */
    data object NotAuthorized : SyncFailure

    /** It answered, but what came back was not an OwnTV backup. */
    data object BadPayload : SyncFailure
    data object Unknown : SyncFailure
}

/**
 * Local sync: two OwnTV devices on the same Wi-Fi exchanging their data directly, with no account,
 * no cloud and no server of ours.
 *
 * It is deliberately thin, because nearly all of it already existed. The payload **is** a backup
 * container, so [BackupManager] writes and reads it unchanged; the applying **is** a restore, which
 * has merged rather than overwritten since 2026-07-18; the listener **is** the companion server the
 * Remote flow uses, with one extra mode. What is new is the client
 * ([LocalSyncClient]), the pairing ([PairedDeviceStore]) and the deletions
 * ([tv.own.owntv.core.database.entity.UserDataTombstoneEntity]) — without which a merge quietly
 * reinstates everything the user has ever removed.
 *
 * Both apps use this class and both can host, so a sync can be started from whichever device the
 * user happens to be holding.
 */
class LocalSyncManager(
    context: Context,
    private val companion: CompanionController,
    private val backups: BackupManager,
    private val paired: PairedDeviceStore,
    private val client: LocalSyncClient,
    private val discovery: LocalSyncDiscovery,
    private val autoSync: LocalAutoSyncStore,
    private val scheduler: LocalAutoSyncScheduler,
) {
    private val appContext = context.applicationContext

    /**
     * The secrets this listener will accept in place of the PIN, snapshotted when hosting starts and
     * extended as devices pair, mapped to the device each one belongs to. Held in memory because the
     * server reads it from inside a request.
     *
     * The device id is not decoration: it is how an automatic pass is attributed to a paired device,
     * so a consent can be checked against the caller instead of against "someone with a secret".
     */
    @Volatile private var acceptedSecrets: Map<String, String> = emptyMap()

    /**
     * The passphrase the container prepared by [startHosting] is sealed with, for as long as this
     * device is hosting. Minted fresh each time and handed out over `/sync/hello`, which already
     * demands the PIN or a pairing secret — so whoever is allowed to fetch the file is exactly
     * whoever is allowed to open it.
     *
     * It exists because the alternative was asking the user to invent a backup password in the middle
     * of a sync. That question was never really about protecting the transfer: without a passphrase
     * [BackupManager] simply **leaves the playlist logins out**, so the honest meaning of the empty
     * field was "send my other device everything except the part it needs". Now the two devices agree
     * a key between themselves and nobody is asked anything.
     *
     * **Assigned only once the container it opens exists**, in [startServing], never when the key is
     * minted. Publishing it up front left a window as long as an export — many seconds on a large
     * library — in which a listener already running from an earlier session answered `/sync/hello`
     * with the *new* key while `/backup.own` still served the *previous* file. The far side then
     * downloaded a container its key could not open, and the sync died in `previewImport` with
     * nothing on screen but "something went wrong". The key and the file now change together.
     */
    @Volatile private var sessionPassword: String? = null

    /** This device's lasting identity, read once and kept — [PairedDeviceStore.selfId] mints it. */
    @Volatile private var selfId: String? = null

    /** Outlives a single request: the pairing write is launched here, not awaited on a server thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Merging the containers automatic passes push here. Runs only while this device is hosting. */
    private var autoSyncIngest: Job? = null

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    val pairedDevices: Flow<List<PairedDevice>> = paired.devices

    /** The listener's own state — the PIN, the QR and the addresses to show while hosting. */
    val hostState: Flow<tv.own.owntv.core.companion.CompanionServerState> = companion.state

    /** What the other device will call this one. The name the user gave the phone, if they gave one. */
    val deviceName: String by lazy {
        val configured = runCatching { Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
        configured?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    // --- hosting ---------------------------------------------------------------------------------

    /**
     * Starts listening and announces this device on the network. The PIN and address to show come
     * from [CompanionController.state], as they do for every other companion flow.
     *
     * The export is prepared **now**, once, rather than when the other device asks for it: a request
     * has a timeout and building a container out of a large library does not always fit inside one.
     * That also means what the other device receives is this device's state at the moment the screen
     * opened, which is the moment the user is looking at.
     *
     * Running only while that screen is open is the entire security posture, so the caller must
     * [stopHosting] when it leaves.
     */
    suspend fun startHosting(
        port: Int = CompanionLink.DEFAULT_PORT,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        profileIds: Set<Long>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val folder = File(cacheDir(), "outgoing").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        // Sealed, always. See [sessionPassword] for why this is not the user's problem to solve — and
        // for why it stays a local until the container it opens has actually been written.
        val password = PairedDeviceStore.newSecret()
        selfId = paired.selfId()
        val exported = backups.export(folder, sections, password, profileIds, recordAsBackup = false)
            .getOrElse { return@withContext Result.failure(it) }
        // Read once, here, rather than per request: the server asks for these while answering, and a
        // device unpaired mid-session simply stops working on the next session, which is soon enough.
        acceptedSecrets = paired.current().associate { it.secret to it.id }
        startServing(port, File(exported), password)
        Result.success(Unit)
    }

    private fun startServing(port: Int, file: File, password: String) {
        // The key becomes answerable in the same breath as the file it opens. An export that failed
        // above never reaches here, so a listener left over from an earlier session keeps advertising
        // the key to the container it is still serving rather than one that does not exist yet.
        sessionPassword = password
        companion.startForLocalSync(
            port = port,
            file = file,
            info = {
                JSONObject()
                    .put("name", deviceName)
                    .put("app", CoreBuildInfo.versionName)
                    .put("payload", PAYLOAD_VERSION)
                    // Both only ever reach a caller that already holds the PIN or a pairing secret.
                    .put("device", selfId.orEmpty())
                    .put("session", sessionPassword.orEmpty())
                    .toString()
            },
            onPair = { remoteName, remoteAddress, remoteId -> pairFromHost(remoteName, remoteAddress, remoteId) },
            secrets = { acceptedSecrets },
            onAutoSyncAsk = ::askForAutoSync,
            autoSyncAllowed = { deviceId -> autoSync.allows(deviceId) },
            // The far side has withdrawn the acceptance our schedule was running on, so stop it
            // now rather than waiting for the next pass to be refused.
            onAutoSyncRevoke = { deviceId -> disableAutoSync(deviceId) },
        )
        discovery.advertise(deviceName, port, selfId.orEmpty())
        // The other half of a pass run by the far device: it pulls this export and pushes its own
        // container back. That one is merged as it arrives — the consent at the server is the
        // confirmation, and nobody is standing here to give another.
        autoSyncIngest?.cancel()
        autoSyncIngest = scope.launch {
            companion.autoSyncBackups.collect { file -> mergeIncoming(file) }
        }
    }

    fun stopHosting() {
        discovery.stopAdvertising()
        companion.stop()
        autoSyncIngest?.cancel()
        autoSyncIngest = null
        acceptedSecrets = emptyMap()
        sessionPassword = null
    }

    fun discover(): Flow<DiscoveredDevice> = discovery.discover()

    /**
     * Containers the other device has pushed to this one while hosting. Nothing is applied on
     * arrival — the screen previews it and the user confirms, because an unattended device must not
     * change its own data because something on the network asked it to.
     */
    val incoming: Flow<File> = companion.backups

    /**
     * Devices asking, right now, to sync with this one automatically. Only non-empty while this
     * device is hosting, because that is the only time another device can reach it.
     */
    val autoSyncRequests: Flow<List<CompanionController.AutoSyncRequest>> = companion.autoSyncRequests

    /** The answer to a request that is on this device's screen. */
    fun answerAutoSync(requestId: String, accepted: Boolean) = companion.answerAutoSync(requestId, accepted)

    /**
     * A paired device has asked to sync with this one automatically, and the answer is the user's.
     *
     * The question is put on the screen of whichever app is hosting; the far side is waiting on the
     * HTTP request this runs inside. Accepting writes the consent **here** — where the automatic
     * passes are checked — before the far side is told yes, so a pass can never arrive between the
     * answer and the record of it.
     */
    private suspend fun askForAutoSync(deviceId: String): Boolean {
        val name = paired.current().firstOrNull { it.id == deviceId }?.name ?: UNKNOWN_DEVICE_NAME
        val accepted = companion.askAutoSync(deviceId, name)
        if (accepted) autoSync.grantConsent(deviceId)
        return accepted
    }

    /**
     * Called on the HOST when the other device presents the right PIN: mint its secret, remember it,
     * and hand it back.
     *
     * The secret is added to [acceptedSecrets] before the store is written, and the write itself is
     * launched rather than waited for. This runs *inside* an HTTP request on the server's own small
     * thread pool: blocking one of those threads on a DataStore write is how a busy listener stops
     * answering, and the far side would be handed a secret it cannot use until the disk catches up.
     */
    private fun pairFromHost(remoteName: String, remoteAddress: String, remoteId: String): String? = runCatching {
        val secret = PairedDeviceStore.newSecret()
        // The far device's own id, so pairing it again REPLACES this row rather than adding a twin.
        // A random one only where the far side is too old to send one — which brings the duplicate
        // back, and is still better than refusing to pair.
        val id = remoteId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        acceptedSecrets = acceptedSecrets + (secret to id)
        scope.launch {
            paired.put(
                PairedDevice(
                    id = id,
                    name = remoteName.ifBlank { UNKNOWN_DEVICE_NAME },
                    // Where it connected FROM, so this device can start a sync towards it later
                    // instead of only ever being the one connected to. Its port is the default one:
                    // both apps host on it, and nothing in a pairing request says otherwise.
                    address = remoteAddress,
                    port = CompanionLink.DEFAULT_PORT,
                    secret = secret,
                    pairedAt = System.currentTimeMillis(),
                ),
            )
        }
        secret
    }.onFailure { Log.w(TAG, "Pairing failed on the host side", it) }.getOrNull()

    // --- connecting ------------------------------------------------------------------------------

    /**
     * First contact: the user typed the PIN shown on the other device. Trades it for a lasting
     * secret and remembers the device, so nothing is typed again.
     */
    suspend fun pair(address: String, port: Int, pin: String): Result<PairedDevice> {
        val mine = paired.selfId().also { selfId = it }
        val secret = client.pair(address, port, pin, deviceName, mine).getOrElse { return Result.failure(it) }
        val remote = client.hello(address, port, secret).getOrNull()
        val device = PairedDevice(
            // Its id, not a new one of ours — the same rule as [pairFromHost], for the same reason.
            id = remote?.deviceId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = remote?.name?.takeIf { it.isNotBlank() } ?: address,
            address = address,
            port = port,
            secret = secret,
            pairedAt = System.currentTimeMillis(),
        )
        paired.put(device)
        return Result.success(device)
    }

    /**
     * Forgets a device, and ends that device's schedule with it.
     *
     * The settings for it are kept on disk — re-pairing the same device should bring its answer
     * back — but they no longer count as paired, so the timer stops being derived from them and a
     * pass stops including them. A timer left running for a device that is no longer there wakes up
     * to find nothing to do, and keeps waking up.
     */
    suspend fun unpair(id: String) {
        paired.remove(id)
        autoSync.revokeConsent(id)
        scheduler.schedule(autoSync.current())
    }

    // --- transferring ----------------------------------------------------------------------------

    /**
     * Downloads the other device's data into a file WITHOUT applying any of it, so the user can be
     * shown what would change before anything does. [apply] finishes the job.
     */
    suspend fun fetch(
        device: PairedDevice,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        /** True when this is an automatic pass, so the far side can check it has a consent. */
        autoSync: Boolean = false,
    ): Result<SyncPayload> =
        withContext(Dispatchers.IO) {
            _progress.value = SyncProgress.Connecting
            // Ask who is there before taking anything from them: the answer carries the key to the
            // container they have prepared. It is the same call pairing already makes, so the far
            // side needs no new endpoint and an older one simply returns nothing here.
            val password = client.hello(device.address, device.port, device.secret)
                .getOrNull()?.sessionPassword?.takeIf { it.isNotBlank() }
            val target = File(cacheDir(), "incoming-${System.currentTimeMillis()}.own")
            _progress.value = SyncProgress.Transferring
            client.fetch(device.address, device.port, device.secret, target, autoSync)
                .mapCatching { file ->
                    // The user's chosen sections, not all of them: a summary that counts favorites
                    // the user just unticked promises a change that the apply will not make.
                    val preview = backups.previewImport(file, sections, password).getOrThrow()
                    paired.touch(device.id, device.address, device.port)
                    _progress.value = SyncProgress.Idle
                    SyncPayload(file, preview, password)
                }
                .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
        }

    /**
     * Merges a container an automatic pass pushed here.
     *
     * Unlike [previewIncoming]'s other callers, this one has nobody to show a preview to: the
     * device that ran the pass already checked that this one accepted automatic sync, and it is
     * suspended waiting for its upload to land. Every section the container carries is merged —
     * what the sender left out was never in the file — and the temporary copy is deleted either
     * way. A container that will not open is logged and dropped; the next pass sends it again.
     */
    private suspend fun mergeIncoming(file: File) {
        try {
            val payload = previewIncoming(file).getOrNull()
            if (payload == null) {
                Log.w(TAG, "Dropped an unreadable automatic sync container")
                return
            }
            apply(payload.file, BackupManager.Section.entries.toSet(), payload.password)
        } finally {
            file.delete()
        }
    }

    /**
     * What a container that was pushed here would change — and the key that opens it.
     *
     * The sender sealed it with the secret the two devices share, so the key is already here: every
     * secret this device knows is tried until one reads the file. There are a handful at most, and
     * the alternative is asking the user for a password only the other device could know.
     */
    suspend fun previewIncoming(
        file: File,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
    ): Result<SyncPayload> = withContext(Dispatchers.IO) {
        // Secrets first and `null` only as the last resort, deliberately. A container this device can
        // unseal must be opened sealed: [BackupManager] will read an encrypted file without its
        // passphrase and simply leave every secret field blank, so trying `null` first would "succeed"
        // and quietly drop the playlist logins this change exists to carry.
        val candidates = (acceptedSecrets.keys + paired.secrets()).toList() + listOf(null)
        for (candidate in candidates) {
            val preview = backups.previewImport(file, sections, candidate).getOrNull() ?: continue
            return@withContext Result.success(SyncPayload(file, preview, candidate))
        }
        Result.failure(LocalSyncHttpException(HTTP_BAD_PAYLOAD))
    }

    /**
     * Applies a fetched file, with the chosen sections only.
     *
     * Explicitly a [BackupManager.ImportMode.MERGE] — this is the *other device's* data arriving, not
     * the user restoring their own snapshot, so a deletion made here still beats an older record from
     * there. Every direction lands on this one call: RECEIVE and MERGE run it locally, and SEND runs
     * it on the far device, which reaches it the same way.
     */
    suspend fun apply(
        file: File,
        sections: Set<BackupManager.Section>,
        password: String? = null,
        /** The user ticked the other device's hardware settings on the confirm step. */
        deviceSettings: Boolean = false,
    ): Result<BackupManager.ImportSummary> {
        _progress.value = SyncProgress.Applying
        return backups.import(file, sections, password, BackupManager.ImportMode.MERGE, deviceSettings)
            .onSuccess { _progress.value = SyncProgress.Done(received = it, sent = false) }
            .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
    }

    /**
     * Exports the chosen sections and hands the file to the other device, which merges it there.
     *
     * The far side is not asked to choose: the sender picked what to send, and a merge cannot destroy
     * anything on arrival. What it can do is add — which is why the receiving screen shows what
     * landed afterwards.
     */
    suspend fun send(
        device: PairedDevice,
        sections: Set<BackupManager.Section>,
        profileIds: Set<Long>? = null,
        /** True when this is an automatic pass, so the far side can check it has a consent. */
        autoSync: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Sealed with the secret the pairing already established, which is the one key the far side
        // is certain to have. Nothing is asked of the user, and the playlist logins travel.
        val password = device.secret
        _progress.value = SyncProgress.Preparing
        // Its own folder: the fetched file the user is still looking at lives in the cache too, and
        // clearing the whole cache here would delete it out from under the confirmation sheet.
        val folder = File(cacheDir(), "outgoing").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        backups.export(folder, sections, password, profileIds, recordAsBackup = false)
            .mapCatching { path ->
                _progress.value = SyncProgress.Transferring
                client.send(device.address, device.port, device.secret, File(path), autoSync).getOrThrow()
                paired.touch(device.id, device.address, device.port)
                _progress.value = SyncProgress.Done(received = null, sent = true)
            }
            .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
    }

    // --- unattended -----------------------------------------------------------------------------

    /**
     * Turns auto-sync on for [device] — after the person at that device accepts.
     *
     * The acceptance is the whole switch. Until it comes back there is no schedule, no timer and no
     * background pass; the other device can still be synced with by hand, as any pairing can. On
     * acceptance the schedule is written here, where the passes run, and the other device keeps the
     * consent that lets them in. If the request cannot be delivered (the other device is not
     * hosting, or is gone) this fails and changes nothing: a schedule the far side never agreed to
     * would only wake up to be refused.
     *
     * An automatic pass is always a merge, so the only thing left to choose is when. [timing] is
     * that choice — today's draft from the sheet — and "never" is not one of its answers: turning
     * auto-sync *off* is [disableAutoSync]'s job.
     */
    suspend fun enableAutoSync(
        device: PairedDevice,
        timing: LocalAutoSyncTiming = LocalAutoSyncTiming.HOURS_24,
    ): AutoSyncEnableResult {
        val accepted = client.requestAutoSync(device.address, device.port, device.secret)
            .getOrElse { return AutoSyncEnableResult.Failed(failureFor(it)) }
        if (!accepted) return AutoSyncEnableResult.Declined
        val chosen = timing.takeIf { it != LocalAutoSyncTiming.OFF } ?: LocalAutoSyncTiming.HOURS_24
        autoSync.save(device.id, autoSync.current()[device.id].copy(timing = chosen))
        scheduler.schedule(autoSync.current())
        return AutoSyncEnableResult.Enabled
    }

    /**
     * Turns this device's own schedule for [deviceId] off. The consent on the far side is that
     * device's to keep or withdraw; stopping a schedule here does not, and must not, touch it.
     */
    suspend fun disableAutoSync(deviceId: String) {
        val config = autoSync.current()[deviceId]
        if (!config.enabled) return
        autoSync.save(deviceId, config.copy(timing = LocalAutoSyncTiming.OFF))
        scheduler.schedule(autoSync.current())
    }

    /**
     * Withdraws this device's permission for [deviceId] to sync here automatically, and tells it
     * best-effort. The local write is what matters: it is checked on the next request, whether or
     * not this call ever reaches the other device.
     */
    suspend fun revokeAutoSyncConsent(deviceId: String) {
        autoSync.revokeConsent(deviceId)
        paired.current().firstOrNull { it.id == deviceId }?.let { device ->
            client.revokeAutoSync(device.address, device.port, device.secret)
        }
    }

    /**
     * One automatic pass: every device in [configs], each with its own answer, reached and synced,
     * with nobody there to press anything.
     *
     * One device's settings do not touch another's: what syncs with the television is whatever the
     * user chose on the television's row, and what syncs with the tablet is whatever they chose on
     * the tablet's row. The caller decides who is due — the worker does, from the timings and the
     * last run — and this only carries out that decision.
     *
     * This is the manual flow's three steps — fetch, apply, send — run without a screen, and always
     * in that order: an automatic pass is a merge by definition, because merging is what the other
     * device agreed to. The one thing it does *not* do is ask: a container that arrives is merged
     * rather than previewed, and that is the whole point of the setting, because a preview nobody
     * answers is a preview that never becomes data. Everything it does is something the user agreed
     * to when they turned auto-sync on, and the sections and the profiles they left unticked stay
     * unticked: what leaves this device is scoped at the export, exactly as a manual backup is.
     *
     * The far device has to be answering. The pull needs it to be hosting, and so does the push; a
     * device that is not there is reported as unreachable and the pass moves on: one phone switched
     * off is not a reason to skip the other one.
     */
    suspend fun runAutoSync(
        configs: Map<String, LocalAutoSyncConfig>,
    ): LocalAutoSyncReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val all = paired.current().associateBy { it.id }
        // A device that is not paired is not synced, whatever the settings still say about it.
        val reports = configs.mapNotNull { (id, config) ->
            all[id]?.let { device -> syncOne(device, config) }
        }
        // A consent withdrawn on the far side is not something to retry: the schedule it belongs to
        // is turned off here, so the next tick does not wake up just to be refused again. The user
        // sees the outcome in the row's last-run line rather than a timer that quietly never works.
        val refused = reports.filter { it.outcome == LocalAutoSyncOutcome.CONSENT_REVOKED }
        if (refused.isNotEmpty()) {
            val current = autoSync.current()
            refused.forEach { report ->
                val config = current[report.deviceId]
                if (config.enabled) {
                    autoSync.save(report.deviceId, config.copy(timing = LocalAutoSyncTiming.OFF))
                }
            }
            scheduler.schedule(autoSync.current())
        }
        LocalAutoSyncReport(reports, startedAt, System.currentTimeMillis())
    }

    /** One device's share of a pass. */
    private suspend fun syncOne(device: PairedDevice, config: LocalAutoSyncConfig): LocalAutoSyncDeviceReport {
        // The config's empty set means "every profile"; [send] spells that as null.
        val profiles = config.profileIds.takeIf { it.isNotEmpty() }
        val fetched = fetch(device, config.sections, autoSync = true)
        if (fetched.isFailure) {
            return LocalAutoSyncDeviceReport(device.id, device.name, outcomeFor(fetched.exceptionOrNull()))
        }
        val payload = fetched.getOrThrow()
        val applied = apply(payload.file, config.sections, payload.password)
        // The container is a copy of the other device's data sitting in the cache; it is not needed
        // again and must not be left where the next export could pick it up.
        payload.file.delete()
        if (applied.isFailure) {
            return LocalAutoSyncDeviceReport(device.id, device.name, outcomeFor(applied.exceptionOrNull()))
        }
        // The other half of the merge: this device's own data goes back, so both ends end up with
        // everything either of them chose to share. A send that fails does not undo what was
        // received: a merge is a merge, not a transaction, and the next pass will send it again.
        // The one failure that is not just "try again" is a refusal: the far side has withdrawn its
        // consent since the fetch, and the schedule must stop rather than keep pulling.
        val sent = send(device, config.sections, profiles, autoSync = true)
        val refused = sent.isFailure && outcomeFor(sent.exceptionOrNull()) == LocalAutoSyncOutcome.CONSENT_REVOKED
        return LocalAutoSyncDeviceReport(
            deviceId = device.id,
            name = device.name,
            outcome = if (refused) LocalAutoSyncOutcome.CONSENT_REVOKED else LocalAutoSyncOutcome.SYNCED,
            items = applied.getOrThrow().items,
        )
    }

    /** The same reading of a failure as [failureFor], in the words a pass reports it in. */
    private fun outcomeFor(t: Throwable?): LocalAutoSyncOutcome = when {
        t == null -> LocalAutoSyncOutcome.UNKNOWN
        t is LocalSyncHttpException && t.code == 401 -> LocalAutoSyncOutcome.NOT_AUTHORIZED
        t is LocalSyncHttpException && t.code == 403 -> LocalAutoSyncOutcome.CONSENT_REVOKED
        t is LocalSyncHttpException -> LocalAutoSyncOutcome.BAD_PAYLOAD
        t is BackupManager.WrongPasswordException -> LocalAutoSyncOutcome.BAD_PAYLOAD
        t is java.io.IOException -> LocalAutoSyncOutcome.UNREACHABLE
        else -> LocalAutoSyncOutcome.UNKNOWN
    }

    fun clearProgress() {
        _progress.value = SyncProgress.Idle
    }

    /** Cache for sync payloads only, wiped before each export so one transfer cannot pick up another. */
    private fun cacheDir(): File = File(appContext.cacheDir, "local-sync").apply { mkdirs() }

    private fun failureFor(t: Throwable): SyncFailure = when {
        t is LocalSyncHttpException && t.code == 401 -> SyncFailure.NotAuthorized
        t is LocalSyncHttpException -> SyncFailure.BadPayload
        // A container that arrived whole and would not open. Its own answer, not the shrug: this is
        // precisely what [SyncFailure.BadPayload] says — "what arrived could not be read" — and
        // leaving it to fall through told the user "something went wrong" and nothing else.
        t is BackupManager.WrongPasswordException -> SyncFailure.BadPayload
        t is java.io.IOException -> SyncFailure.Unreachable
        else -> SyncFailure.Unknown
    }

    private companion object {
        const val TAG = "LocalSyncManager"

        /** The backup schema this build writes; the far side reports its own in `/sync/hello`. */
        const val PAYLOAD_VERSION = 21
        const val UNKNOWN_DEVICE_NAME = "OwnTV"

        /** Not a real HTTP answer — [SyncFailure.BadPayload] is what the screen has to say. */
        const val HTTP_BAD_PAYLOAD = 422
    }
}
