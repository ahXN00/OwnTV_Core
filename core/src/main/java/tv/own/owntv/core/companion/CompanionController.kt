package tv.own.owntv.core.companion

import android.content.Context
import android.util.Log
import java.io.File
import java.net.BindException
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.R
import tv.own.owntv.core.i18n.LocaleStore

/**
 * Owns the Remote companion HTTP listener for the app's lifetime. Registered as a Koin `single` so
 * the Setup and Settings screens share one server instead of each carrying duplicate networking code.
 *
 * The remote browser only fills the Add Source form; it never starts the import. Each submission is exposed two
 * ways:
 *  - [payloads] — a live event stream, used by the Remote screen to know when to hand off; and
 *  - [lastPayload] — the retained latest value, so the Manual form (which subscribes *after* the
 *    hand-off navigation) still sees it. A replay-0 SharedFlow alone would drop it in that window.
 *
 * [start]/[stop] are safe to call from the UI thread; the server does its I/O on its own dispatcher.
 */
class CompanionController(context: Context, localeStore: LocaleStore) {

    private val appContext = context.applicationContext
    private val server = CompanionHttpServer(appContext, localeStore)

    private val _state = MutableStateFlow<CompanionServerState>(CompanionServerState.Idle)
    val state: StateFlow<CompanionServerState> = _state.asStateFlow()

    private val _payloads = MutableSharedFlow<CompanionPayload>(extraBufferCapacity = 8)
    val payloads: SharedFlow<CompanionPayload> = _payloads.asSharedFlow()

    private val _lastPayload = MutableStateFlow<CompanionPayload?>(null)
    val lastPayload: StateFlow<CompanionPayload?> = _lastPayload.asStateFlow()

    /** Backup files uploaded from the remote device in [startForBackupRestore] mode, saved to cache and emitted here. */
    private val _backups = MutableSharedFlow<File>(extraBufferCapacity = 4)
    val backups: SharedFlow<File> = _backups.asSharedFlow()

    /** Cache folder the remote-export flow writes the backup into before serving it for download. */
    val backupExportDir: File get() = File(appContext.cacheDir, "remote-backup-export").apply { mkdirs() }

    /** Image files uploaded from the remote device in [startForImageUpload] mode, saved to cache and emitted here. */
    private val _images = MutableSharedFlow<File>(extraBufferCapacity = 4)
    val images: SharedFlow<File> = _images.asSharedFlow()

    /** TMDB API keys handed over from the remote device in [startForTmdbKey] mode. */
    private val _tmdbKeys = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val tmdbKeys: SharedFlow<String> = _tmdbKeys.asSharedFlow()
    private val _tmdbConfigs = MutableSharedFlow<CompanionServiceConfig>(extraBufferCapacity = 4)
    val tmdbConfigs: SharedFlow<CompanionServiceConfig> = _tmdbConfigs.asSharedFlow()
    private val _openSubtitlesConfigs = MutableSharedFlow<CompanionServiceConfig>(extraBufferCapacity = 4)
    val openSubtitlesConfigs: SharedFlow<CompanionServiceConfig> = _openSubtitlesConfigs.asSharedFlow()

    /** A fresh 6-digit PIN per [start], so a leaked code is short-lived. */
    @Volatile private var currentPin: String = ""

    /** Bundled Lora TTF bytes, loaded once and served on the web form; null if the resource can't be read. */
    private val loraBytes: ByteArray? by lazy {
        // openRawResource serves any file-backed resource, not just res/raw; a font is exactly that,
        // and we want the TTF bytes verbatim rather than a loaded Typeface. runCatching covers the
        // resource being unavailable, which is the only thing the lint check is protecting against.
        @Suppress("ResourceType")
        runCatching { appContext.resources.openRawResource(R.font.lora_variable).use { it.readBytes() } }
            .onFailure { Log.w(TAG, "Could not load Lora for companion web form; falling back to serif", it) }
            .getOrNull()
    }

    /** Starts the add-source companion server (the original Remote add-source flow). */
    fun start(port: Int) = startInternal(port, CompanionMode.ADD_SOURCE)

    /** Starts the companion server in backup-restore mode: the remote device uploads a backup JSON, emitted on [backups]. */
    fun startForBackupRestore(port: Int) = startInternal(port, CompanionMode.BACKUP_RESTORE)

    /** Starts the companion server in backup-download mode, serving [file] for the remote device to download. */
    fun startForBackupDownload(port: Int, file: File) = startInternal(port, CompanionMode.BACKUP_DOWNLOAD, file)

    /**
     * Starts the companion server for local sync between two OwnTV devices.
     *
     * The only mode that both receives and serves in one session, because a merge does both: an
     * uploaded container arrives on [backups] exactly as it does in backup-restore mode, and [file]
     * — this device's own export, prepared by the caller — is what the other device downloads.
     *
     * [info] answers "who are you", [onPair] mints a lasting secret for a device that presented the
     * right PIN, and [secrets] are the ones already issued, each accepted in place of the PIN so only
     * the first sync between two devices asks the user for anything.
     */
    fun startForLocalSync(
        port: Int,
        file: File?,
        info: () -> String,
        onPair: (name: String, address: String, deviceId: String) -> String?,
        secrets: () -> Set<String>,
    ) = startInternal(
        port = port,
        mode = CompanionMode.LOCAL_SYNC,
        downloadFile = file,
        syncInfo = info,
        onPair = onPair,
        pairedSecrets = secrets,
    )

    /** Starts the companion server in image-upload mode: the remote device sends a background image, emitted on [images]. */
    fun startForImageUpload(port: Int) = startInternal(port, CompanionMode.IMAGE_UPLOAD)

    /** Starts the companion server in TMDB-key mode: the remote device sends an API key, emitted on [tmdbKeys]. */
    fun startForTmdbKey(port: Int) = startInternal(port, CompanionMode.TMDB_KEY)
    fun startForTmdbConfig(port: Int) = startInternal(port, CompanionMode.TMDB_CONFIG)
    fun startForOpenSubtitlesConfig(port: Int) = startInternal(port, CompanionMode.OPEN_SUBTITLES_CONFIG)

    private fun startInternal(
        port: Int,
        mode: CompanionMode,
        downloadFile: File? = null,
        syncInfo: () -> String = { "{}" },
        onPair: (name: String, address: String, deviceId: String) -> String? = { _, _, _ -> null },
        pairedSecrets: () -> Set<String> = { emptySet() },
    ) {
        if (port !in 1..65535) {
            _state.value = CompanionServerState.Failed(CompanionFailure.InvalidPort)
            return
        }
        _state.value = CompanionServerState.Starting
        try {
            currentPin = generatePin()
            _lastPayload.value = null
            val urls = server.start(
                port = port,
                pin = currentPin,
                fontBytes = loraBytes,
                mode = mode,
                onPayload = { raw ->
                    // An uploaded playlist becomes a real file on the TV before the payload goes any
                    // further, so what the Add Source form receives is an ordinary local path.
                    val payload = savePlaylistUpload(raw)
                    Log.d(TAG, "Received ${payload.type} payload '${payload.name.ifBlank { "(unnamed)" }}' — forwarding to UI")
                    _lastPayload.value = payload
                    _payloads.tryEmit(payload)
                },
                onBackup = ::onBackupUploaded,
                onImage = ::onImageUploaded,
                // Never logged, at any level: it is the user's own credential.
                onTmdbKey = { key -> _tmdbKeys.tryEmit(key) },
                onServiceConfig = { config ->
                    when (mode) {
                        CompanionMode.TMDB_CONFIG -> _tmdbConfigs.tryEmit(config)
                        CompanionMode.OPEN_SUBTITLES_CONFIG -> _openSubtitlesConfigs.tryEmit(config)
                        else -> Unit
                    }
                },
                downloadFile = downloadFile,
                syncInfo = syncInfo,
                onPair = onPair,
                pairedSecrets = pairedSecrets,
                onLocked = {
                    // The server has already stopped itself; just reflect it on the TV so the user
                    // knows why the link went away and that restarting gives them a new PIN.
                    Log.w(TAG, "Companion link locked out after repeated wrong PINs")
                    currentPin = ""
                    _state.value = CompanionServerState.Locked
                },
            )
            _state.value = CompanionServerState.Listening(
                port = port,
                urls = urls,
                pin = currentPin,
                // The QR encodes the plain URL only — the PIN is typed on the remote device's gate page.
                qr = CompanionLink.renderQr(CompanionLink.lanUrl(port)),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start companion listener", t)
            _state.value = CompanionServerState.Failed(friendlyFailure(t, port))
        }
    }

    /**
     * Persists an uploaded backup to a cache file and emits it for the restore UI to inspect.
     *
     * Two wire shapes, because a `.own` container is binary while the old `.json` was not:
     * a **data-URL** (`data:...;base64,…`) is decoded back to bytes — the same trick the background
     * image upload uses, so the socket layer stays text-only — and anything else is a legacy JSON
     * backup pasted through verbatim. The extension follows suit so the restore path's format sniff
     * and the temp file agree.
     */
    private fun onBackupUploaded(text: String) {
        runCatching {
            val base64 = text.substringAfter("base64,", missingDelimiterValue = "")
                .takeIf { text.startsWith("data:") && it.isNotBlank() }
            val bytes = base64?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            val file = File.createTempFile(
                "owntv-remote-restore",
                if (bytes != null) ".own" else ".json",
                appContext.cacheDir,
            )
            if (bytes != null) file.writeBytes(bytes) else file.writeText(text)
            Log.d(TAG, "Received remote backup (${file.length()} bytes) → ${file.name}")
            file
        }.onSuccess { _backups.tryEmit(it) }
            .onFailure { Log.w(TAG, "Failed to persist uploaded backup", it) }
    }

    /**
     * Writes a playlist uploaded through the companion page into the app's own storage and returns
     * the payload with `server` pointing at it. Anything else passes through untouched.
     *
     * `filesDir`, not `cacheDir`: the remote browser only *fills* the form — the TV user presses Start Import
     * later, possibly much later, and the system may evict a cache file in between. The saved path is
     * absolute, which is exactly what `M3uSyncer` already recognises as a local playlist.
     *
     * On a write failure the payload is returned unchanged, so the TV shows an empty URL box rather
     * than a path to a file that is not there.
     */
    private fun savePlaylistUpload(payload: CompanionPayload): CompanionPayload {
        if (payload.playlistContent.isBlank()) return payload
        return runCatching {
            val dir = File(appContext.filesDir, PLAYLIST_UPLOAD_DIR).apply { mkdirs() }
            // Keep only the newest few: a playlist can be megabytes, and every upload that was never
            // imported would otherwise sit here forever.
            dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                .drop(MAX_KEPT_UPLOADS - 1).forEach { it.delete() }
            val file = File(dir, safePlaylistName(payload.playlistFileName))
            file.writeText(payload.playlistContent)
            Log.d(TAG, "Saved uploaded playlist (${file.length()} bytes) → ${file.name}")
            payload.copy(server = file.absolutePath, playlistContent = "")
        }.getOrElse {
            Log.w(TAG, "Failed to save uploaded playlist", it)
            payload.copy(playlistContent = "")
        }
    }

    /**
     * A filename safe to write: the upload names the file, and the name arrives from a browser. Only
     * the last path segment is kept and only safe characters survive it, so `../../databases/owntv`
     * cannot escape the upload directory.
     */
    private fun safePlaylistName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .trimStart('.')
            .takeLast(64)
        return if (base.length > 4 && base.contains('.')) base else "playlist-${System.currentTimeMillis()}.m3u"
    }

    /** Persists an uploaded background image to a cache file and emits it for the settings UI to ingest. */
    private fun onImageUploaded(bytes: ByteArray, extension: String) {
        runCatching {
            val file = File.createTempFile("owntv-remote-bg", ".$extension", appContext.cacheDir)
            file.writeBytes(bytes)
            Log.d(TAG, "Received remote background image (${bytes.size} bytes) → ${file.name}")
            file
        }.onSuccess { _images.tryEmit(it) }
            .onFailure { Log.w(TAG, "Failed to persist uploaded image", it) }
    }

    fun stop() {
        server.stop()
        currentPin = ""
        _state.value = CompanionServerState.Idle
    }

    /** Clears [lastPayload] after the Manual Add Source form consumed it for pre-fill. */
    fun consumePayload() {
        _lastPayload.value = null
    }

    /** Permanent teardown for the app-wide singleton. */
    fun dispose() {
        server.close()
        _state.value = CompanionServerState.Idle
    }

    private fun generatePin(): String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    private fun friendlyFailure(t: Throwable, port: Int): CompanionFailure = when (t) {
        is BindException -> CompanionFailure.PortInUse(port)
        else -> CompanionFailure.Unavailable
    }

    private companion object {
        const val TAG = "CompanionController"

        /** Under `filesDir`, so an upload survives until the TV user actually imports it. */
        const val PLAYLIST_UPLOAD_DIR = "companion-playlists"
        const val MAX_KEPT_UPLOADS = 5
    }
}
