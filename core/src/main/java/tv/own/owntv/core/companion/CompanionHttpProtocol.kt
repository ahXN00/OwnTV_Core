package tv.own.owntv.core.companion

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.SyncScopeChoice

/**
 * Pure HTTP protocol helpers used by [CompanionHttpServer]. Keeping parsing, PIN comparison, and
 * bounded-body handling independent of Android Context makes the security-sensitive paths testable
 * in the ordinary JVM unit-test source set without constructing a listener.
 */
internal object CompanionHttpProtocol {
    /** Backup JSON / base64 image uploads. Generous, but finite. */
    const val UPLOAD_BODY_LIMIT = 16 * 1024 * 1024

    /** PIN posts and source forms — a few hundred bytes in practice. */
    const val FORM_BODY_LIMIT = 64 * 1024

    /** Marks a request that belongs to an automatic pass; the host refuses an unconsented one. */
    const val AUTO_SYNC_HEADER = "x-owntv-auto-sync"
    const val AUTO_SYNC_MARKER = "1"

    private const val INITIAL_BODY_BUFFER = 64 * 1024
    private const val BODY_CHUNK = 16 * 1024

    /** Length-checked, constant-time PIN compare so timing cannot leak the PIN digit by digit. */
    fun pinEquals(submitted: String, expected: String): Boolean {
        if (submitted.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (submitted[i].code xor expected[i].code)
        return diff == 0
    }

    /** Parse an `application/x-www-form-urlencoded` string into a key/value map. */
    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val split = pair.split('=', limit = 2)
            val key = urlDecode(split[0])
            if (key.isNotBlank()) out[key] = urlDecode(split.getOrNull(1).orEmpty())
        }
        return out
    }

    /** Return the finite body cap for an endpoint. */
    fun maxBodyBytes(path: String): Int = when {
        // `/m3u` joins the upload group: the M3U panel can carry a whole playlist file inline, and a
        // real playlist is far past the 64 KB a form field needs.
        path == "/backup" || path == "/background" || path == "/m3u" -> UPLOAD_BODY_LIMIT
        else -> FORM_BODY_LIMIT
    }

    /**
     * Read a request body without trusting Content-Length for allocation. A declared or observed
     * body over [limit] returns null so the caller can answer 413. A body that ends before its
     * Content-Length throws [java.io.EOFException]: half an uploaded backup used to be kept and then
     * failed to decrypt, which the TV could only report as a wrong password.
     */
    fun readBody(input: BufferedInputStream, headers: Map<String, String>, limit: Int): String? {
        val declared = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared > limit) return null
        if (declared == 0L) return ""
        val buffer = ByteArrayOutputStream(declared.coerceAtMost(INITIAL_BODY_BUFFER.toLong()).toInt())
        val chunk = ByteArray(BODY_CHUNK)
        var total = 0L
        while (total < declared) {
            val want = minOf(chunk.size.toLong(), declared - total).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        if (total < declared) throw java.io.EOFException("Body ended at $total of $declared bytes")
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    /** Parse a submitted form/JSON body, or null when its required fields are missing. */
    fun parsePayload(contentType: String?, bodyText: String, fallbackType: SourceType): CompanionPayload? {
        val isJson = contentType?.contains("application/json", ignoreCase = true) == true ||
            bodyText.trimStart().startsWith("{")
        val fields = if (isJson) {
            // A malformed JSON body (or a JSON-less test environment) must not crash the accept loop.
            runCatching { parseJsonFields(bodyText) }.getOrDefault(emptyMap())
        } else {
            parseQuery(bodyText)
        }

        val rawType = pick(fields, "type", "sourceType", "source_type")
            .ifBlank { fallbackType.name }
            .trim()
            .lowercase()
        val type = when (rawType) {
            "m3u", "m3u8" -> SourceType.M3U
            "stalker" -> SourceType.STALKER
            else -> SourceType.XTREAM
        }

        val server = pick(fields, "server", "url").trim()
        // An uploaded playlist: the file's text arrives inline and the TV saves it locally, so there
        // is no URL to give. Deliberately NOT trimmed — leading whitespace is the file's own content.
        val playlistContent = pick(fields, "playlistFile", "playlist_file")
        val playlistFileName = pick(fields, "playlistFileName", "playlist_file_name").trim()
        val user = pick(fields, "user", "username").trim()
        val pass = pick(fields, "pass", "password").trim()
        val portalUrl = pick(fields, "portalUrl", "portal_url").trim()
        val mac = pick(fields, "mac", "macAddress", "mac_address").trim()

        when (type) {
            SourceType.STALKER -> if (portalUrl.isBlank() || mac.isBlank()) return null
            // One of the two is required, not both: a URL to fetch, or a file to save.
            SourceType.M3U -> if (server.isBlank() && playlistContent.isBlank()) return null
            SourceType.XTREAM, SourceType.LOCAL_BACKUP -> if (server.isBlank() || user.isBlank() || pass.isBlank()) return null
        }

        return CompanionPayload(
            type = type,
            name = pick(fields, "name").trim(),
            server = server,
            playlistFileName = playlistFileName,
            playlistContent = playlistContent,
            user = user,
            pass = pass,
            portalUrl = portalUrl,
            mac = mac,
            serialNumber = pick(fields, "serialNumber", "serial_number", "sn").trim(),
            deviceId = pick(fields, "deviceId", "device_id").trim(),
            deviceId2 = pick(fields, "deviceId2", "device_id2").trim(),
            signature = pick(fields, "signature").trim(),
            userAgent = pick(fields, "userAgent", "user_agent").trim(),
            epgUrl = pick(fields, "epgUrl", "epg_url").trim(),
            autoRefresh = pick(fields, "autoRefresh", "auto_refresh").ifBlank { "OFF" },
            syncLive = scopeChoice(fields, "syncLive", "sync_live", SyncScopeChoice.Now),
            syncMovies = scopeChoice(fields, "syncMovies", "sync_movies", SyncScopeChoice.Now),
            syncSeries = scopeChoice(fields, "syncSeries", "sync_series", SyncScopeChoice.Now),
            isDefault = bool(fields, "isDefault", "is_default", default = false),
        )
    }

    private fun parseJsonFields(bodyText: String): Map<String, String> {
        val json = org.json.JSONObject(bodyText)
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val value = json.opt(key)
            if (value != null && value != org.json.JSONObject.NULL) out[key] = value.toString()
        }
        return out
    }

    private fun pick(fields: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> fields[key]?.takeIf { it.isNotBlank() } } ?: ""

    private fun bool(fields: Map<String, String>, primary: String, secondary: String, default: Boolean): Boolean {
        val raw = fields[primary] ?: fields[secondary] ?: return default
        return raw.equals("true", true) || raw == "1" || raw.equals("on", true) || raw.equals("yes", true)
    }

    private fun scopeChoice(
        fields: Map<String, String>,
        primary: String,
        secondary: String,
        default: SyncScopeChoice,
    ): SyncScopeChoice {
        val raw = fields[primary] ?: fields[secondary] ?: return default
        return SyncScopeChoice.parse(raw, default)
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8.name()) }
            .getOrDefault(value)
}
