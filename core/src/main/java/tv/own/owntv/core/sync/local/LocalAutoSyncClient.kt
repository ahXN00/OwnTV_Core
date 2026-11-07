package tv.own.owntv.core.sync.local

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupManager

/** The other device refused, with [code] and the reason it gave (see [AutoSyncRefusal]). */
class AutoSyncHttpException(val code: Int, val reason: String?) : Exception("HTTP $code $reason")

/**
 * The caller's side of automatic sync: signed requests to a paired device's [LocalAutoSyncServer].
 * Every request is signed with the pairing [secret] ([LocalAutoSyncAuth]); the secret itself is
 * never sent.
 */
class LocalAutoSyncClient(private val clock: () -> Long = System::currentTimeMillis) {

    /** Who answered at that address, and what it lets travel. */
    data class Hello(val deviceId: String, val name: String, val sections: Set<BackupManager.Section>)

    suspend fun hello(address: String, port: Int, selfId: String, secret: String): Hello = withContext(Dispatchers.IO) {
        val json = JSONObject(request("GET", address, port, PATH_HELLO, selfId, secret, ByteArray(0), HELLO_TIMEOUT_MS).toString(Charsets.UTF_8))
        Hello(json.optString("device"), json.optString("name"), LocalAutoSyncStore.sections(json.optJSONArray("sections")).orEmpty())
    }

    /** The other device's export, sealed with the pairing secret, written to [into]. */
    suspend fun export(address: String, port: Int, selfId: String, secret: String, into: File): File = withContext(Dispatchers.IO) {
        val bytes = request("GET", address, port, PATH_EXPORT, selfId, secret, ByteArray(0), TRANSFER_TIMEOUT_MS)
        if (bytes.isEmpty()) throw AutoSyncHttpException(204, null)
        into.writeBytes(bytes)
        into
    }

    /** Hands this device's export to the other device, which merges what its own agreement lets in. */
    suspend fun import(address: String, port: Int, selfId: String, secret: String, file: File): Int = withContext(Dispatchers.IO) {
        val answer = request("POST", address, port, PATH_IMPORT, selfId, secret, file.readBytes(), TRANSFER_TIMEOUT_MS)
        JSONObject(answer.toString(Charsets.UTF_8)).optInt("items")
    }

    /** Tells the other device that this one turned automatic sync off. */
    suspend fun notice(address: String, port: Int, selfId: String, secret: String) = withContext(Dispatchers.IO) {
        request("POST", address, port, PATH_NOTICE, selfId, secret, NOTICE_TURNED_OFF, HELLO_TIMEOUT_MS)
        Unit
    }

    private fun request(
        method: String,
        address: String,
        port: Int,
        path: String,
        selfId: String,
        secret: String,
        body: ByteArray,
        readTimeoutMs: Int,
    ): ByteArray {
        val host = address.removePrefix("http://").substringBefore('/').substringBefore(':')
        val time = clock()
        val nonce = LocalAutoSyncAuth.newNonce()
        val connection = (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            requestMethod = method
            setRequestProperty(LocalAutoSyncAuth.HEADER_DEVICE, selfId)
            setRequestProperty(LocalAutoSyncAuth.HEADER_TIME, time.toString())
            setRequestProperty(LocalAutoSyncAuth.HEADER_NONCE, nonce)
            setRequestProperty(LocalAutoSyncAuth.HEADER_SIGNATURE, LocalAutoSyncAuth.sign(secret, method, path, time, nonce, body))
        }
        try {
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val reason = runCatching { JSONObject(connection.errorStream.use { it.readBytes() }.toString(Charsets.UTF_8)).optString("reason") }.getOrNull()
                Log.w(TAG, "auto-sync $path refused: HTTP $code $reason")
                throw AutoSyncHttpException(code, reason)
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PATH_HELLO = "/sync/auto/hello"
        const val PATH_EXPORT = "/sync/auto/export"
        const val PATH_IMPORT = tv.own.owntv.core.companion.CompanionHttpProtocol.AUTO_SYNC_IMPORT_PATH
        const val PATH_NOTICE = "/sync/auto/notice"

        private val NOTICE_TURNED_OFF = "{\"type\":\"turned-off\"}".toByteArray(Charsets.UTF_8)

        private const val TAG = "LocalAutoSync"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val HELLO_TIMEOUT_MS = 5_000
        private const val TRANSFER_TIMEOUT_MS = 120_000
    }
}
