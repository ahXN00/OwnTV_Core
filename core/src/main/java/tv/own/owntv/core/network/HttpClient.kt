package tv.own.owntv.core.network

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thin OkHttp wrapper for fetching M3U playlists and Xtream JSON. [get] streams the response body to
 * a block (so huge payloads are never fully buffered) and always closes the response. A per-source
 * custom User-Agent can be supplied (Phase 12 power feature).
 */
class HttpClient(private val client: OkHttpClient) {

    /** Non-2xx response. Thrown before the body block runs, so retrying it never re-emits parsed items. */
    class HttpStatusException(val code: Int, message: String) : IOException(message)

    suspend fun <T> get(
        url: String,
        userAgent: String? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
        maxAttempts: Int = 1,
        headers: Map<String, String> = emptyMap(),
        block: suspend (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val ua = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            // Caller-supplied identity headers (today: the metadata Worker's x-owntv-key / x-owntv-client).
            // Blank values are dropped so an unconfigured build never sends an empty header.
            .apply { headers.forEach { (name, value) -> if (value.isNotBlank()) header(name, value) } }
            .build()

        val coroutineContext = currentCoroutineContext()
        val startedAt = SystemClock.elapsedRealtime()
        val safeUrl = redact(url)
        val attempts = maxAttempts.coerceAtLeast(1)
        var attempt = 1
        while (attempt <= attempts) {
            coroutineContext.ensureActive()
            val call = client.newCall(request)
            val cancellationHook = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            var responseReceived = false
            Log.d(TAG, "GET start url=$safeUrl ua=${ua.take(48)} attempt=$attempt/$attempts")
            try {
                call.execute().use { response ->
                    responseReceived = true
                    val headersAt = SystemClock.elapsedRealtime()
                    if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code} for ${redact(url)}")
                    val body = response.body
                    val totalBytes = body.contentLength().takeIf { it >= 0 }
                    // Info, not debug, and deliberately so: release builds strip Log.d, and this one
                    // line is what answers "are we actually getting gzip?" — the question behind a
                    // 73 MB catalogue body that the provider drops four minutes in (plan N2a). It
                    // was debug-level for exactly as long as that question went unanswered.
                    Log.i(
                        TAG,
                        "GET headers url=$safeUrl code=${response.code} contentLength=${totalBytes ?: -1} " +
                            "contentEncoding=${response.header("Content-Encoding").orEmpty()} " +
                            // The one that actually answers the question. OkHttp adds
                            // `Accept-Encoding: gzip` on its own when the caller sets none, and then
                            // *strips* Content-Encoding from the user-facing response after
                            // decompressing — so the field above reads empty whether gzip happened
                            // or not. The network response still carries the wire value.
                            "wireEncoding=${response.networkResponse?.header("Content-Encoding").orEmpty()} " +
                            "transferEncoding=${response.header("Transfer-Encoding").orEmpty()} " +
                            "headersMs=${headersAt - startedAt}",
                    )
                    onProgress?.invoke(0, totalBytes)
                    body.byteStream().withProgress(totalBytes, onProgress, safeUrl).use { input ->
                        return@withContext block(input).also {
                            Log.d(TAG, "GET body done url=$safeUrl totalMs=${SystemClock.elapsedRealtime() - startedAt}")
                        }
                    }
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                // Retry only when the body block never ran: no response at all, or a 5xx/429 status
                // (thrown before the block). Mid-body failures are NOT retried — the block may already
                // have consumed items, and the truncation fallback handles those.
                val retryableStatus = e is HttpStatusException && (e.code in 500..599 || e.code == 429)
                val retrying = (!responseReceived || retryableStatus) && attempt < attempts
                Log.w(
                    TAG,
                    "GET failed url=$safeUrl attempt=$attempt/$attempts totalMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "message=${e.message}${if (retrying) " retrying" else ""}",
                    e,
                )
                if (!retrying) throw e
            } finally {
                cancellationHook?.dispose()
            }
            delay((RETRY_DELAY_MS * attempt).coerceAtMost(MAX_RETRY_DELAY_MS))
            attempt++
        }
        throw IOException("GET failed for $safeUrl")
    }

    /** Mask credentials in a URL before it appears in an error/log — Xtream embeds user/pass in the query. */
    private fun redact(url: String): String =
        url.replace(Regex("(?i)(username|password|user|pass|token)=[^&]*"), "$1=***")

    /** Convenience for small responses (e.g. Xtream category lists). */
    suspend fun getText(
        url: String,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = get(url, userAgent, headers = headers) { it.readBytes().decodeToString() }

    companion object {
        private const val TAG = "HttpClient"

        /** Player-style UA that IPTV panels broadly accept. Overridable per-source in Phase 12. */
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /**
         * The identity to fall back on when a provider refuses [DEFAULT_USER_AGENT] outright.
         *
         * Some hosts sit behind a WAF that blocklists player User-Agents by name: measured on a
         * Cloudflare-fronted panel that answers `VLC/3.0.20 LibVLC/3.0.20` — and `vlc`, and `okhttp/…`,
         * and a blank UA — with a 403 "Just a moment…" challenge page, while serving the identical URL
         * 200 to anything else. A bare `Mozilla/5.0` was enough to clear that particular panel, but the
         * full desktop-browser string is what such rules are actually written around, so it is the safer
         * bet against the next WAF that scores UA *completeness* rather than matching a blocklist.
         *
         * Never the default — plenty of panels expect (and some require) the VLC identity, so this is
         * only ever reached after the default has actually been refused.
         */
        const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val RETRY_DELAY_MS = 750L
        private const val MAX_RETRY_DELAY_MS = 3_000L

        /** Mask credentials for display (info overlay / logs): query params AND Xtream `/type/user/pass/`
         *  path segments, which is where live URLs embed them. */
        fun redactUrl(url: String): String = url
        .replace(Regex("(?i)(username|password|user|pass|token|data|auth|signature|sig|key)=[^&]*"), "$1=***")
            // `timeshift` is the catch-up/archive form (/timeshift/user/pass/duration/start/id.ts) — it
            // embeds the same credentials as a live URL, so it must be masked here too.
            .replace(Regex("(?i)(://[^/]+/(?:live|movie|series|vod|timeshift)/)([^/]+)/([^/]+)/"), "$1•••/•••/")
            // Strip userinfo credentials from a `scheme://user:pass@host` URL (e.g. a proxy URL handed
            // to mpv, or any source URL with embedded creds) so they never reach a log line.
            .replace(Regex("(?i)(://)([^/@:]+)(:[^/@]*)?@"), "$1***@")
    }
}

internal fun InputStream.withProgress(
    totalBytes: Long?,
    onProgress: ((Long, Long?) -> Unit)?,
    diagnosticLabel: String? = null,
): InputStream = if (onProgress == null && diagnosticLabel == null) {
    this
} else {
    ProgressInputStream(this, totalBytes, onProgress, diagnosticLabel)
}

private class ProgressInputStream(
    input: InputStream,
    private val totalBytes: Long?,
    private val onProgress: ((Long, Long?) -> Unit)?,
    private val diagnosticLabel: String?,
) : FilterInputStream(input) {
    private val startedAt = SystemClock.elapsedRealtime()
    private var bytesRead = 0L
    private var lastNotifiedBytes = 0L
    private var lastNotifiedAt = 0L
    private var lastDiagnosticBytes = 0L
    private var lastDiagnosticAt = startedAt
    private var readCalls = 0L
    private var readBlockedMs = 0L
    private var maxReadMs = 0L

    // No per-call timing here: a caller reading byte-by-byte would pay two clock syscalls per byte.
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) advance(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val started = SystemClock.elapsedRealtime()
        val read = super.read(b, off, len)
        recordRead(SystemClock.elapsedRealtime() - started)
        if (read > 0) advance(read.toLong())
        return read
    }

    override fun close() {
        try {
            super.close()
        } finally {
            notifyProgress(force = true)
        }
    }

    private fun recordRead(durationMs: Long) {
        readCalls++
        readBlockedMs += durationMs
        if (durationMs > maxReadMs) maxReadMs = durationMs
    }

    private fun advance(delta: Long) {
        bytesRead += delta
        notifyProgress()
    }

    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime() // monotonic — wall clock can jump on NTP sync
        val bytesDelta = bytesRead - lastNotifiedBytes
        val timeDelta = now - lastNotifiedAt
        val complete = totalBytes?.let { bytesRead >= it } == true
        if (!force && bytesDelta < PROGRESS_BYTES_STEP && timeDelta < PROGRESS_MIN_INTERVAL_MS && !complete) return
        lastNotifiedBytes = bytesRead
        lastNotifiedAt = now
        onProgress?.invoke(bytesRead, totalBytes)
        notifyDiagnostics(force)
    }

    private fun notifyDiagnostics(force: Boolean = false) {
        val label = diagnosticLabel ?: return
        val now = SystemClock.elapsedRealtime()
        val bytesDelta = bytesRead - lastDiagnosticBytes
        val timeDelta = now - lastDiagnosticAt
        if (!force && bytesDelta < DIAGNOSTIC_BYTES_STEP && timeDelta < DIAGNOSTIC_MIN_INTERVAL_MS) return
        val kbps = if (timeDelta > 0) bytesDelta * 1000 / timeDelta / 1024 else 0
        Log.d(
            "HttpClient",
            "GET body progress url=$label bytes=$bytesRead total=${totalBytes ?: -1} " +
                "deltaBytes=$bytesDelta deltaMs=$timeDelta kbps=$kbps " +
                "readCalls=$readCalls readBlockedMs=$readBlockedMs maxReadMs=$maxReadMs " +
                "elapsedMs=${now - startedAt}",
        )
        lastDiagnosticBytes = bytesRead
        lastDiagnosticAt = now
    }

    private companion object {
        const val PROGRESS_BYTES_STEP = 256L * 1024L
        const val PROGRESS_MIN_INTERVAL_MS = 150L
        const val DIAGNOSTIC_BYTES_STEP = 2L * 1024L * 1024L
        const val DIAGNOSTIC_MIN_INTERVAL_MS = 5_000L
    }
}
