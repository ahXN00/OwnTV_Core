package tv.own.owntv.core.sync.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
) {
    private val appContext = context.applicationContext

    /**
     * The secrets this listener will accept in place of the PIN, snapshotted when hosting starts and
     * extended as devices pair. Held in memory because the server reads it from inside a request.
     */
    @Volatile private var acceptedSecrets: Set<String> = emptySet()

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
     */
    @Volatile private var sessionPassword: String? = null

    /** This device's lasting identity, read once and kept — [PairedDeviceStore.selfId] mints it. */
    @Volatile private var selfId: String? = null

    /** Outlives a single request: the pairing write is launched here, not awaited on a server thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // Sealed, always. See [sessionPassword] for why this is not the user's problem to solve.
        val password = PairedDeviceStore.newSecret().also { sessionPassword = it }
        selfId = paired.selfId()
        val exported = backups.export(folder, sections, password, profileIds)
            .getOrElse { return@withContext Result.failure(it) }
        // Read once, here, rather than per request: the server asks for these while answering, and a
        // device unpaired mid-session simply stops working on the next session, which is soon enough.
        acceptedSecrets = paired.secrets()
        startServing(port, File(exported))
        Result.success(Unit)
    }

    private fun startServing(port: Int, file: File) {
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
        )
        discovery.advertise(deviceName, port, selfId.orEmpty())
    }

    fun stopHosting() {
        discovery.stopAdvertising()
        companion.stop()
        acceptedSecrets = emptySet()
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
        acceptedSecrets = acceptedSecrets + secret
        scope.launch {
            paired.put(
                PairedDevice(
                    // The far device's own id, so pairing it again REPLACES this row rather than
                    // adding a twin. A random one only where the far side is too old to send one —
                    // which brings the duplicate back, and is still better than refusing to pair.
                    id = remoteId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
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

    suspend fun unpair(id: String) = paired.remove(id)

    // --- transferring ----------------------------------------------------------------------------

    /**
     * Downloads the other device's data into a file WITHOUT applying any of it, so the user can be
     * shown what would change before anything does. [apply] finishes the job.
     */
    suspend fun fetch(
        device: PairedDevice,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
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
            client.fetch(device.address, device.port, device.secret, target)
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
        val candidates = (acceptedSecrets + paired.secrets()).toList() + listOf(null)
        for (candidate in candidates) {
            val preview = backups.previewImport(file, sections, candidate).getOrNull() ?: continue
            return@withContext Result.success(SyncPayload(file, preview, candidate))
        }
        Result.failure(LocalSyncHttpException(HTTP_BAD_PAYLOAD))
    }

    /** Applies a fetched file — the ordinary restore path, merging, with the chosen sections only. */
    suspend fun apply(
        file: File,
        sections: Set<BackupManager.Section>,
        password: String? = null,
    ): Result<BackupManager.ImportSummary> {
        _progress.value = SyncProgress.Applying
        return backups.import(file, sections, password)
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
        backups.export(folder, sections, password, profileIds)
            .mapCatching { path ->
                _progress.value = SyncProgress.Transferring
                client.send(device.address, device.port, device.secret, File(path)).getOrThrow()
                paired.touch(device.id, device.address, device.port)
                _progress.value = SyncProgress.Done(received = null, sent = true)
            }
            .onFailure { _progress.value = SyncProgress.Failed(failureFor(it)) }
    }

    fun clearProgress() {
        _progress.value = SyncProgress.Idle
    }

    /** Cache for sync payloads only, wiped before each export so one transfer cannot pick up another. */
    private fun cacheDir(): File = File(appContext.cacheDir, "local-sync").apply { mkdirs() }

    private fun failureFor(t: Throwable): SyncFailure = when {
        t is LocalSyncHttpException && t.code == 401 -> SyncFailure.NotAuthorized
        t is LocalSyncHttpException -> SyncFailure.BadPayload
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
