package tv.own.owntv.player

import tv.own.owntv.core.network.HttpClient

/**
 * What the live engine's HTTP probe reads from a response for its diagnostics: the refusal message worth
 * quoting, a scrubbed one-line body, the request's role and the payload's media signature. Pure — the
 * engine's interceptor does the logging and remembers the panel's answers.
 */
internal object HttpDiagnostics {

    /**
     * A refusal body that reads like a message to a human, so the error screen can quote it; else null.
     *
     * Deliberately picky about what qualifies — a wrong quote is worse than none:
     * - **HTML is skipped.** That body is a WAF challenge or a hosting landing page ("Just a moment…
     *   Enable JavaScript and cookies to continue"), written for a browser, not for this user.
     * - **Binary is skipped.** A refused *segment* often still carries media bytes, which [textPrefix]
     *   would hand back as punctuation soup; a body has to be mostly letters and spaces to count.
     * - **Very short bodies are skipped**, since "0" or "error" explains nothing the status didn't.
     */
    fun providerMessage(contentType: String?, text: String): String? {
        if (contentType?.contains("html", ignoreCase = true) == true) return null
        val clean = text.trim()
        if (clean.length < 12) return null
        if (clean.count { it.isLetter() || it.isWhitespace() } < clean.length * 3 / 4) return null
        return clean
    }

    /**
     * A failed response's body, reduced to one short printable line for the log: markup stripped,
     * whitespace collapsed, capped, then scrubbed of anything that could identify the account. Scrubbing
     * is done against [requestUrl]'s **own** credentials — the Xtream `/live/<user>/<pass>/` segments and
     * signed query values — so even a panel that echoes the username back in an error page cannot leak it.
     */
    fun textPrefix(bytes: ByteArray, requestUrl: String): String {
        val text = bytes.decodeToString()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .filter { it.code in 32..126 }
            .trim()
            .take(160)
        return HttpClient.redactUrl(secretsIn(requestUrl).fold(text) { acc, secret -> acc.replace(secret, "***") })
            .replace('"', '\'')
    }

    /** Credential-bearing substrings of [url] — path segments and signed query values — never logged. */
    private fun secretsIn(url: String): List<String> {
        val path = url.substringBefore('?')
        val segments = Regex("(?i)/(?:live|movie|series|vod|timeshift)/([^/]+)/([^/]+)/")
            .find(path)?.groupValues?.drop(1).orEmpty()
        val queryValues = url.substringAfter('?', "").split('&')
            .mapNotNull { it.substringAfter('=', "").takeIf { v -> v.length >= 6 } }
        return (segments + queryValues).filter { it.length >= 3 }
    }

    /** What this request was for — the one thing a bare URL in the log doesn't say. */
    fun requestRole(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "playlist"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4") -> "segment"
            path.endsWith(".key") -> "key"
            else -> "stream"
        }
    }

    fun mediaSignature(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        fun has(vararg expected: Int): Boolean =
            bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 0xff) == expected[it] }
        val textStart = bytes.take(32).map { it.toInt().toChar() }.joinToString("").trimStart().lowercase()
        return when {
            // Three sync bytes distinguish a real transport stream from an error page beginning with 'G'.
            bytes.size > 376 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte() -> "mpeg-ts"
            has(0x1a, 0x45, 0xdf, 0xa3) -> "matroska"
            bytes.size >= 8 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> "mp4"
            has(0x00, 0x00, 0x01, 0xba) -> "mpeg-ps"
            has(0x49, 0x44, 0x33) -> "id3/audio"
            textStart.startsWith("#extm3u") -> "hls-manifest"
            textStart.startsWith("<!doctype") || textStart.startsWith("<html") -> "html"
            textStart.startsWith("{") || textStart.startsWith("[") -> "json"
            else -> "unknown"
        }
    }
}
