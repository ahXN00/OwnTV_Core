package tv.own.owntv.core.companion

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.own.owntv.core.R
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.model.SourceType

/**
 * Tiny embedded HTTP listener behind the Remote add-source flow. It serves a mobile-friendly,
 * OwnTV-themed form (Xtream / M3U / Stalker) that any device on the same Wi-Fi can open.
 *
 * ## Security model
 * The QR encodes only the server URL — never the PIN. Opening it shows a PIN-entry gate; the visitor
 * types the 6-digit PIN shown on the TV. Only then is the form served (with the PIN baked into its
 * submit endpoints). Direct POSTs to `/xtream|/m3u|/stalker` must also carry the PIN (`?pin=` or the
 * `X-Companion-Pin` header) or receive 401. So a stray device on the network cannot push a source.
 *
 * The remote browser only fills the form; it never starts the import. Submitted details are surfaced via
 * [start]'s onPayload callback, which the host uses to pre-fill Add Source on the TV.
 *
 * Everything HTTP happens off the main thread on [Dispatchers.IO]. Passwords/MAC are never logged.
 */
class CompanionHttpServer(
    private val context: Context,
    private val localeStore: LocaleStore,
) {

    /**
     * The server's **own** slice of the IO pool (C3). Every accepted socket used to be handled on the
     * shared `Dispatchers.IO`, where each one holds a thread for up to its `soTimeout` (10 s, or 30 s
     * for uploads) — so a connection flood, or just a browser pre-connecting aggressively, could park
     * the whole pool and stall the app's own syncs, downloads and image loading. A limited view caps
     * the blast radius at [MAX_PARALLELISM] threads. It's a view of `Dispatchers.IO`, not a pool of
     * its own, so there is nothing extra to release in [close].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val running = AtomicBoolean(false)

    /** In-flight connections (C3). Beyond the cap a socket is closed at once rather than queued. */
    private val inFlight = java.util.concurrent.Semaphore(MAX_IN_FLIGHT)

    /**
     * Consecutive wrong PINs this session (C2). A 6-digit PIN is a million possibilities, which a
     * script on a LAN exhausts in minutes; the constant-time compare stops timing leakage but says
     * nothing about volume. Reset by any correct PIN.
     */
    private val failedPins = java.util.concurrent.atomic.AtomicInteger(0)

    /** Fired when [MAX_PIN_ATTEMPTS] is reached and the server shuts itself down. */
    @Volatile private var onLocked: () -> Unit = {}

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pin: String = ""

    /** Bundled Lora TTF served at `/lora.ttf` so the web form matches the app's popup font offline. */
    @Volatile private var fontBytes: ByteArray? = null

    /** Which page/endpoint set is live for the current session (add-source vs. backup upload). */
    @Volatile private var mode: CompanionMode = CompanionMode.ADD_SOURCE

    /** Session callbacks — set on [start], only the one matching [mode] ever fires. */
    @Volatile private var onPayload: (CompanionPayload) -> Unit = {}
    @Volatile private var onBackup: (String) -> Unit = {}
    @Volatile private var onImage: (bytes: ByteArray, extension: String) -> Unit = { _, _ -> }
    @Volatile private var onTmdbKey: (String) -> Unit = {}
    @Volatile private var onServiceConfig: (CompanionServiceConfig) -> Unit = {}

    /** The backup file served at `/backup.json` in [CompanionMode.BACKUP_DOWNLOAD] mode. */
    @Volatile private var downloadFile: File? = null

    /**
     * Local sync only (see [CompanionMode.LOCAL_SYNC]). [syncInfo] answers `/sync/hello`, [onPair]
     * mints and stores a secret for a device that just typed the right PIN, and [pairedSecrets] are
     * the secrets already issued — any one of them is accepted in place of the PIN, which is what
     * makes the second sync one tap instead of a fresh six digits.
     */
    @Volatile private var syncInfo: () -> String = { "{}" }
    @Volatile private var onPair: (name: String, address: String, deviceId: String) -> String? = { _, _, _ -> null }
    @Volatile private var pairedSecrets: () -> Set<String> = { emptySet() }

    /**
     * Bind [port] and start accepting. [pin] gates the pages and POST endpoints. [fontBytes], when
     * provided, is served at `/lora.ttf`. [mode] selects the served page (add-source form or backup
     * upload); [onPayload] receives an add-source submission, [onBackup] the raw JSON of an uploaded
     * backup. Returns the LAN URLs to show on the TV. Throws on bind failure (the caller maps that to
     * [CompanionServerState.Failed]).
     */
    fun start(
        port: Int,
        pin: String,
        fontBytes: ByteArray?,
        mode: CompanionMode = CompanionMode.ADD_SOURCE,
        onPayload: (CompanionPayload) -> Unit = {},
        onBackup: (String) -> Unit = {},
        onImage: (bytes: ByteArray, extension: String) -> Unit = { _, _ -> },
        onTmdbKey: (String) -> Unit = {},
        onServiceConfig: (CompanionServiceConfig) -> Unit = {},
        downloadFile: File? = null,
        syncInfo: () -> String = { "{}" },
        onPair: (name: String, address: String, deviceId: String) -> String? = { _, _, _ -> null },
        pairedSecrets: () -> Set<String> = { emptySet() },
        onLocked: () -> Unit = {},
    ): List<String> {
        stop()
        this.pin = pin
        this.onLocked = onLocked
        failedPins.set(0)
        this.fontBytes = fontBytes
        this.mode = mode
        this.onPayload = onPayload
        this.onBackup = onBackup
        this.onImage = onImage
        this.onTmdbKey = onTmdbKey
        this.onServiceConfig = onServiceConfig
        this.downloadFile = downloadFile
        this.syncInfo = syncInfo
        this.onPair = onPair
        this.pairedSecrets = pairedSecrets
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running.set(true)
        scope.launch { acceptLoop(socket) }
        return CompanionLink.lanUrls(port)
    }

    fun stop() {
        running.set(false)
        pin = ""
        onPayload = {}
        onBackup = {}
        onImage = { _, _ -> }
        onLocked = {}
        downloadFile = null
        syncInfo = { "{}" }
        onPair = { _, _, _ -> null }
        pairedSecrets = { emptySet() }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Permanent teardown — cancels the I/O scope. Call when the owning singleton is disposed. */
    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        try {
            while (running.get() && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Companion accept failed", e)
                    break
                }
                // C3: shed rather than queue. A queued connection still holds a socket and would be
                // served long after the client gave up; closing it immediately keeps the cap honest.
                if (!inFlight.tryAcquire()) {
                    Log.w(TAG, "Companion connection refused — $MAX_IN_FLIGHT already in flight")
                    runCatching { client.close() }
                    continue
                }
                scope.launch {
                    try {
                        handleClient(client)
                    } finally {
                        inFlight.release()
                    }
                }
            }
        } finally {
            stop()
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            // Backup/image uploads can be several MB over Wi-Fi; give them more headroom than a tiny form post.
            socket.soTimeout = if (mode == CompanionMode.BACKUP_RESTORE || mode == CompanionMode.IMAGE_UPLOAD || mode == CompanionMode.LOCAL_SYNC) 30_000 else 10_000
            val input = BufferedInputStream(socket.getInputStream())
            // Resolve one locale context for the whole request. This keeps every error and page in a
            // single effective locale even if the user changes the app language while a request is
            // being parsed, and avoids rebuilding a ContextWrapper for each field/error branch.
            val pageContext = localizedContext()
            fun localized(id: Int): String = pageContext.getString(id)
            val requestLine = readLine(input) ?: return sendText(socket, 400, localized(R.string.companion_error_bad_request))
            val parts = requestLine.split(' ')
            if (parts.size < 3) return sendText(socket, 400, localized(R.string.companion_error_bad_request))

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val queryPin = CompanionHttpProtocol.parseQuery(query)["pin"].orEmpty()

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            // Font asset for the web form (no PIN needed — it is only a font).
            if (method == "GET" && path == "/lora.ttf") {
                val bytes = fontBytes
                return if (bytes != null) sendBytes(socket, 200, "font/ttf", bytes) else sendText(socket, 404, localized(R.string.companion_error_not_found))
            }

            // Landing: a correct PIN in the query (e.g. the "send another" link) skips straight to the
            // page for the current mode; otherwise show the PIN gate.
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                if (pinOk(queryPin)) {
                    pinAccepted()
                    return sendHtml(socket, 200, authedPage(pageContext))
                }
                // An empty PIN is just someone opening the link, not a guess — only count real ones.
                if (queryPin.isNotBlank() && pinRejected()) return sendText(socket, 403, lockoutMessage(pageContext))
                return sendHtml(
                    socket,
                    200,
                    CompanionHtml.pinPage(pageContext, if (queryPin.isNotBlank()) pinMismatchMessage(pageContext) else null),
                )
            }

            // PIN submission from the gate.
            if (method == "POST" && path == "/") {
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_body_too_large))
                val submitted = CompanionHttpProtocol.parseQuery(body)["pin"].orEmpty()
                if (pinOk(submitted)) {
                    pinAccepted()
                    return sendHtml(socket, 200, authedPage(pageContext))
                }
                if (pinRejected()) return sendText(socket, 403, lockoutMessage(pageContext))
                return sendHtml(socket, 200, CompanionHtml.pinPage(pageContext, pinMismatchMessage(pageContext)))
            }

            // --- local sync (LOCAL_SYNC mode) — no web page: the other OwnTV app talks here directly.

            // Who is this? Answered to a device holding the PIN or an already-issued pairing secret,
            // so a phone can show "Living Room TV" before anyone commits to anything.
            if (method == "GET" && path == "/sync/hello") {
                if (mode != CompanionMode.LOCAL_SYNC) return sendText(socket, 404, localized(R.string.companion_error_not_found))
                if (!requireSyncAuth(queryPin.ifBlank { headers["x-companion-pin"].orEmpty() })) {
                    return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                }
                return sendJson(socket, 200, syncInfo())
            }

            // Pairing. The PIN and only the PIN: a secret cannot mint another secret, so one leaked
            // pairing can never widen itself into a second device the user never approved.
            if (method == "POST" && path == "/sync/pair") {
                if (mode != CompanionMode.LOCAL_SYNC) return sendText(socket, 404, localized(R.string.companion_error_not_found))
                if (!requirePin(queryPin.ifBlank { headers["x-companion-pin"].orEmpty() })) {
                    return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                }
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_body_too_large))
                val fields = CompanionHttpProtocol.parseQuery(body)
                val name = fields["name"].orEmpty().take(64)
                // Who the caller is, so re-pairing updates its row instead of adding a second one.
                // Blank from a build older than this one — the caller then gets a random id as before.
                val deviceId = fields["id"].orEmpty().take(64)
                // The address is the socket's, not something the caller claims: a host has to be
                // able to reach back later, and a device that lies about where it is would only be
                // making itself unreachable.
                val remoteAddress = socket.inetAddress?.hostAddress.orEmpty()
                val secret = onPair(name, remoteAddress, deviceId)
                    ?: return sendText(socket, 500, localized(R.string.companion_error_pair_failed))
                return sendJson(socket, 200, org.json.JSONObject().put("secret", secret).toString())
            }

            // Backup download (BACKUP_DOWNLOAD mode) — PIN required, streams the exported container.
            // `/backup.json` stays routed here: the old path costs nothing to keep and a remote browser that
            // bookmarked it still works. What it serves is whatever export produced — a `.own` file.
            if (method == "GET" && (path == "/backup.own" || path == "/backup.json")) {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requireSyncAuth(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val file = downloadFile
                val bytes = file?.takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }
                    ?: return sendText(socket, 404, localized(R.string.companion_error_no_backup))
                return sendDownload(socket, bytes, file.name)
            }

            // Backup upload (BACKUP_RESTORE mode) — PIN required, JSON body is the backup file.
            if (method == "POST" && path == "/backup") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requireSyncAuth(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_backup_too_large))
                if (body.isBlank()) return sendText(socket, 400, localized(R.string.companion_error_empty_backup))
                onBackup(body)
                return sendHtml(socket, 200, CompanionHtml.backupSentPage(pageContext, pin))
            }

            // Background-image upload (IMAGE_UPLOAD mode) — PIN required. Body is a base64 data-URL
            // (`data:image/jpeg;base64,...`) produced by the web page's FileReader; decoding it here
            // keeps binary handling out of the socket path.
            if (method == "POST" && path == "/background") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_image_too_large))
                val decoded = decodeImageDataUrl(body) ?: return sendText(socket, 400, localized(R.string.companion_error_invalid_image))
                onImage(decoded.first, decoded.second)
                return sendHtml(socket, 200, CompanionHtml.imageSentPage(pageContext, pin))
            }

            // TMDB key handover (TMDB_KEY mode) — PIN required. Body is the bare key as text.
            if (method == "POST" && path == "/tmdbkey") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_body_too_large))
                val key = body.trim()
                // Shape check only — the app cannot verify a key without spending a request, and the
                // Metadata screen already has a "Test lookup" button for that. This just stops obvious
                // junk (an empty box, a pasted URL) from silently replacing a working key.
                if (!Regex("^[A-Za-z0-9._-]{16,128}$").matches(key)) {
                    return sendText(socket, 400, localized(R.string.companion_tmdb_invalid))
                }
                onTmdbKey(key)
                return sendHtml(socket, 200, CompanionHtml.tmdbKeySentPage(pageContext, pin))
            }

            if (method == "POST" && path == "/serviceconfig") {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_body_too_large))
                val fields = CompanionHttpProtocol.parseQuery(body)
                val key = fields["apiKey"].orEmpty().trim()
                val url = fields["serverUrl"].orEmpty().trim()
                // Credentials belong to the OpenSubtitles page only; dropped outright in TMDB mode so
                // a hand-crafted POST can't push an account into a flow that has no use for one.
                val credentials = mode == CompanionMode.OPEN_SUBTITLES_CONFIG
                val username = if (credentials) fields["username"].orEmpty().trim() else ""
                // NOT trimmed: leading/trailing spaces can be part of a password, and silently eating
                // them would produce an "invalid credentials" the user cannot explain.
                val password = if (credentials) fields["password"].orEmpty() else ""
                if (key.isBlank() && url.isBlank() && username.isBlank() && password.isEmpty()) {
                    return sendText(socket, 400, localized(
                        if (credentials) R.string.companion_service_config_empty_account
                        else R.string.companion_service_config_empty
                    ))
                }
                if (key.isNotBlank() && !Regex("^[A-Za-z0-9._-]{8,256}$").matches(key)) return sendText(socket, 400, localized(R.string.companion_service_config_invalid_key))
                if (url.isNotBlank() && runCatching { java.net.URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() } }.getOrDefault(false).not()) return sendText(socket, 400, localized(R.string.companion_service_config_invalid_url))
                // Length-bounded only. A password may legitimately contain anything, and OpenSubtitles
                // does not publish a username charset — rejecting on a guessed pattern would lock real
                // accounts out with no way for the user to tell why.
                if (username.length > 256 || password.length > 256) {
                    return sendText(socket, 400, localized(R.string.companion_service_config_invalid_credentials))
                }
                onServiceConfig(CompanionServiceConfig(key, url, username, password))
                return sendHtml(socket, 200, CompanionHtml.serviceConfigSentPage(pageContext, pin))
            }

            // Source submissions — PIN required (query or header), else 401.
            if (method == "POST" && (path == "/xtream" || path == "/m3u" || path == "/stalker")) {
                val headerPin = headers["x-companion-pin"].orEmpty()
                if (!requirePin(queryPin.ifBlank { headerPin })) return sendText(socket, 401, localized(R.string.companion_error_unauthorized))
                val fallback = when (path) {
                    "/stalker" -> SourceType.STALKER
                    "/m3u" -> SourceType.M3U
                    else -> SourceType.XTREAM
                }
                val body = CompanionHttpProtocol.readBody(input, headers, CompanionHttpProtocol.maxBodyBytes(path))
                    ?: return sendText(socket, 413, localized(R.string.companion_error_body_too_large))
                val payload = CompanionHttpProtocol.parsePayload(headers["content-type"], body, fallback)
                    ?: return sendText(socket, 400, localized(R.string.companion_error_missing_fields))
                onPayload(payload)
                return sendHtml(socket, 200, CompanionHtml.savedPage(pageContext, payload, pin))
            }

            sendText(socket, 404, localized(R.string.companion_error_not_found))
        }
    }

    private fun pinOk(candidate: String): Boolean = pin.isNotBlank() && CompanionHttpProtocol.pinEquals(candidate, pin)

    /**
     * PIN check for the direct POST endpoints, with the C2 attempt counting applied. Returns true
     * when the caller may proceed.
     */
    private suspend fun requirePin(candidate: String): Boolean {
        if (pinOk(candidate)) {
            pinAccepted()
            return true
        }
        pinRejected()
        return false
    }

    /**
     * [requirePin], widened for local sync: a paired device's stored secret is accepted in place of
     * the six digits, so only the FIRST sync between two devices asks the user for anything.
     *
     * The secret is 256 random bits against the PIN's million, and it is only ever issued to a caller
     * that already typed the PIN — so this widens who may connect, never how easily. Outside
     * [CompanionMode.LOCAL_SYNC] it is exactly [requirePin], because no other mode issues secrets.
     */
    private suspend fun requireSyncAuth(candidate: String): Boolean {
        if (mode == CompanionMode.LOCAL_SYNC && candidate.isNotBlank() &&
            pairedSecrets().any { CompanionHttpProtocol.pinEquals(candidate, it) }
        ) {
            pinAccepted()
            return true
        }
        return requirePin(candidate)
    }

    /** A correct PIN clears the strike count — the limit is on *consecutive* failures. */
    private fun pinAccepted() {
        failedPins.set(0)
    }

    /**
     * Records a wrong PIN. Returns true once the session is locked out.
     *
     * The delay is **fixed**, not proportional to the attempt number: a variable delay would leak a
     * timing signal, which is exactly what `pinEquals` exists to avoid. On the cap the server stops
     * itself — reopening the screen on the TV mints a fresh PIN, so a lockout costs an attacker a
     * complete restart and the legitimate user one button press.
     */
    private suspend fun pinRejected(): Boolean {
        val attempts = failedPins.incrementAndGet()
        kotlinx.coroutines.delay(FAILED_PIN_DELAY_MS)
        if (attempts < MAX_PIN_ATTEMPTS) return false
        Log.w(TAG, "Companion link locked after $attempts incorrect PIN attempts")
        onLocked()
        stop()
        return true
    }

    /** The page served after a valid PIN, for the current [mode]. */
    private fun authedPage(context: Context): String = when (mode) {
        CompanionMode.ADD_SOURCE -> CompanionHtml.formPage(context, pin)
        CompanionMode.BACKUP_RESTORE -> CompanionHtml.backupUploadPage(context, pin)
        CompanionMode.BACKUP_DOWNLOAD -> CompanionHtml.backupDownloadPage(context, pin)
        CompanionMode.IMAGE_UPLOAD -> CompanionHtml.imageUploadPage(context, pin)
        CompanionMode.TMDB_KEY -> CompanionHtml.tmdbKeyPage(context, pin)
        CompanionMode.TMDB_CONFIG -> CompanionHtml.serviceConfigPage(context, pin, openSubtitles = false)
        CompanionMode.OPEN_SUBTITLES_CONFIG -> CompanionHtml.serviceConfigPage(context, pin, openSubtitles = true)
        // Local sync has no browser side. Someone who opens the address in a browser and types the
        // PIN gets told so rather than a blank page.
        CompanionMode.LOCAL_SYNC -> CompanionHtml.localSyncPage(context)
    }

    /**
     * Decode a `data:image/<subtype>;base64,<payload>` data-URL into raw bytes + a file extension,
     * or null when the body isn't one (wrong prefix, non-image mime, or bad base64).
     */
    private fun decodeImageDataUrl(body: String): Pair<ByteArray, String>? {
        val match = Regex("^data:image/([a-zA-Z0-9.+-]+);base64,", RegexOption.IGNORE_CASE).find(body) ?: return null
        val ext = when (match.groupValues[1].lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "bmp" -> "bmp"
            else -> return null // keep the accepted set in lockstep with the TV-side picker's extensions
        }
        val bytes = runCatching {
            android.util.Base64.decode(body.substring(match.value.length), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        return if (bytes.isEmpty()) null else bytes to ext
    }

    private fun sendJson(socket: Socket, code: Int, body: String) =
        sendBytes(socket, code, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun sendHtml(socket: Socket, code: Int, body: String) =
        sendBytes(socket, code, "text/html; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun sendText(socket: Socket, code: Int, body: String) =
        sendBytes(socket, code, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    /** Streams [bytes] as a file download so the browser saves it rather than rendering it. */
    private fun sendDownload(socket: Socket, bytes: ByteArray, filename: String) {
        runCatching {
            socket.getOutputStream().use { out ->
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun sendBytes(socket: Socket, code: Int, contentType: String, bytes: ByteArray) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            413 -> "Payload Too Large"; else -> "OK"
        }
        runCatching {
            socket.getOutputStream().use { out ->
                val header = buildString {
                    append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) buffer.write(next)
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * Companion pages are a named final renderer. Wrap at request/render time rather than retaining
     * the Application's startup resources: a language change must affect the next remote page without
     * restarting the server. Context and LocaleStore are mandatory production dependencies, so an
     * instance cannot start listening and fail only when its first page is rendered.
     */
    private fun localizedContext(): Context = AppLocale.wrap(context, localeStore.readBlocking())

    private fun pinMismatchMessage(context: Context): String =
        context.getString(tv.own.owntv.core.R.string.companion_pin_mismatch)

    private fun lockoutMessage(context: Context): String =
        context.getString(tv.own.owntv.core.R.string.setup_companion_locked)

    companion object {
        private const val TAG = "CompanionServer"

        /** Kept as compatibility aliases; protocol-only tests use [CompanionHttpProtocol] directly. */
        internal const val UPLOAD_BODY_LIMIT = CompanionHttpProtocol.UPLOAD_BODY_LIMIT
        internal const val FORM_BODY_LIMIT = CompanionHttpProtocol.FORM_BODY_LIMIT

        /** Consecutive wrong PINs before the link closes itself (C2). */
        internal const val MAX_PIN_ATTEMPTS = 10

        /** Fixed pause before answering a wrong PIN (C2) — fixed so it carries no timing signal. */
        internal const val FAILED_PIN_DELAY_MS = 500L


        /** Threads the companion server may take from the shared IO pool (C3). */
        private const val MAX_PARALLELISM = 6

        /** Connections served at once (C3); further ones are closed immediately rather than queued. */
        private const val MAX_IN_FLIGHT = 16

        /** Pure protocol delegates retained for callers that used the old static API. */
        fun pinEquals(submitted: String, expected: String): Boolean = CompanionHttpProtocol.pinEquals(submitted, expected)
        fun parseQuery(query: String): Map<String, String> = CompanionHttpProtocol.parseQuery(query)
    }
}
