package tv.own.owntv.core.sync.local

import android.util.Base64
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the other device says about itself when asked. */
data class RemoteDevice(
    val name: String,
    val appVersion: String,
    /** Backup schema version the far side writes; a much older one is worth warning about. */
    val payloadVersion: Int,
    /** Its lasting identity — what its pairing is filed under here, so re-pairing updates one row. */
    val deviceId: String,
    /** The passphrase its prepared container is sealed with, for as long as it is hosting. */
    val sessionPassword: String,
)

/**
 * The client half of local sync — the piece the companion server never had, because until now the
 * thing at the other end was always a web browser.
 *
 * It speaks the endpoints [tv.own.owntv.core.companion.CompanionHttpServer] already serves, with no
 * second protocol: `/sync/hello` to identify, `/sync/pair` to exchange a PIN for a lasting secret,
 * `GET /backup.own` to fetch the other device's export and `POST /backup` to hand over this one's.
 * Both apps use it, so the phone can drive the television and the television the phone.
 *
 * Deliberately `HttpURLConnection` rather than the app's OkHttp: this talks to a plain-HTTP address
 * on the local network, and it must not inherit the proxy, interceptors, cookie jar or user-agent an
 * IPTV provider's client is configured with.
 */
class LocalSyncClient {

    /** Who is at [address]? Also the reachability check, so a dead pairing is reported as one. */
    suspend fun hello(address: String, port: Int, credential: String): Result<RemoteDevice> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(get(url(address, port, "/sync/hello"), credential).toString(Charsets.UTF_8))
                RemoteDevice(
                    name = json.optString("name"),
                    appVersion = json.optString("app"),
                    payloadVersion = json.optInt("payload"),
                    deviceId = json.optString("device"),
                    sessionPassword = json.optString("session"),
                )
            }
        }

    /**
     * Trades the PIN the user typed for a secret that works from now on, telling the other device
     * what to call this one in its own paired list.
     */
    suspend fun pair(address: String, port: Int, pin: String, myName: String, myId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8.name())
                // The id rides with the name so the far side files this device under it — pair twice
                // and it updates the one row instead of listing the same phone again.
                val body = "name=${enc(myName)}&id=${enc(myId)}"
                val response = post(url(address, port, "/sync/pair"), pin, body, "application/x-www-form-urlencoded")
                JSONObject(response).optString("secret").ifBlank { error("No secret in pairing response") }
            }
        }

    /** Downloads the other device's export into [into]. The file is a backup container, unchanged. */
    suspend fun fetch(address: String, port: Int, credential: String, into: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = get(url(address, port, "/backup.own"), credential)
                if (bytes.isEmpty()) error("Empty payload")
                into.writeBytes(bytes)
                into
            }
        }

    /**
     * Uploads [file] to the other device.
     *
     * Base64 in a data-URL, because the endpoint is the same one the browser upload page posts to and
     * that path is text-only by design (see `CompanionController.onBackupUploaded`). It costs a third
     * more bytes on a LAN transfer, which is a fair price for not forking the protocol.
     */
    suspend fun send(address: String, port: Int, credential: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                post(url(address, port, "/backup"), credential, "data:application/octet-stream;base64,$encoded", "text/plain")
                Unit
            }
        }

    private fun url(address: String, port: Int, path: String): URL {
        // An address may arrive as a bare IP, or as the whole URL a QR code carried.
        val host = address.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
        return URL("http://$host:$port$path")
    }

    private fun get(url: URL, credential: String): ByteArray = open(url, credential).run {
        requestMethod = "GET"
        connectOrThrow(this)
        inputStream.use { it.readBytes() }.also { disconnect() }
    }

    private fun post(url: URL, credential: String, body: String, contentType: String): String =
        open(url, credential).run {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
            connectOrThrow(this)
            inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).also { disconnect() }
        }

    private fun open(url: URL, credential: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            // The header, not the query string: a PIN or a secret in a URL ends up in logs.
            setRequestProperty("X-Companion-Pin", credential)
        }

    private fun connectOrThrow(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_OK) return
        // Never the body: an error page is HTML, and the credential is in the request either way.
        Log.w(TAG, "Local sync request to ${connection.url.path} failed with HTTP $code")
        connection.disconnect()
        throw LocalSyncHttpException(code)
    }

    private companion object {
        const val TAG = "LocalSyncClient"
        const val CONNECT_TIMEOUT_MS = 8_000

        /** A whole catalogue's worth of favorites over Wi-Fi, on a television's radio. */
        const val READ_TIMEOUT_MS = 60_000
    }
}

/** A non-200 from the other device. [code] 401 is the one the UI has to explain: wrong or stale PIN. */
class LocalSyncHttpException(val code: Int) : Exception("HTTP $code")
