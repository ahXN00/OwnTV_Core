package tv.own.owntv.core.recording

/**
 * Just enough of an HLS **media** playlist to record one, parsed from the text.
 *
 * Deliberately not a general HLS implementation and deliberately not the app's [M3U parser]
 * — `M3uParser` reads a *channel list*, which shares only the `#EXTM3U` first line with this. What a
 * recorder needs is four facts: which segments are listed, where the list starts in the provider's
 * numbering, how long to wait before asking again, and whether the stream has ended.
 *
 * [M3U parser]: tv.own.owntv.core.parser.M3uParser
 */
data class HlsMediaPlaylist(
    /** Segment URIs exactly as written — relative ones are resolved against the playlist's own URL. */
    val segments: List<Segment>,
    /** `#EXT-X-MEDIA-SEQUENCE`: the provider's number for the *first* segment listed. */
    val mediaSequence: Long,
    /** `#EXT-X-TARGETDURATION`, in seconds. The basis for how often to re-ask. */
    val targetDurationSecs: Double,
    /** `#EXT-X-ENDLIST` — a finished stream, not a live one. Catch-up windows are often like this. */
    val endList: Boolean,
    /** The `METHOD` of `#EXT-X-KEY`, or null when the playlist is in the clear. */
    val encryptionMethod: String?,
) {
    /** [key] is the AES-128 key in force where the segment is listed; null when it is in the clear. */
    data class Segment(val uri: String, val durationSecs: Double, val sequence: Long, val key: Key? = null)

    /** An `#EXT-X-KEY:METHOD=AES-128`: where the key is, and the IV when the playlist states one. */
    data class Key(val uri: String, val iv: String?)

    /**
     * True when the segments are encrypted in a way this recorder will not produce a playable file
     * for. AES-128 is not among them: its key is a public URL every player fetches, so the recorder
     * decrypts it too (#243). SAMPLE-AES and anything unknown are refused.
     */
    val isEncrypted: Boolean
        get() = encryptionMethod != null &&
            !encryptionMethod.equals(NO_ENCRYPTION, ignoreCase = true) &&
            !encryptionMethod.equals(AES_128, ignoreCase = true)

    /**
     * How long to wait before re-fetching. Half the target duration, so a segment is never missed
     * because the poll landed just before it was published, and never less than a second, so a
     * playlist that reports nonsense cannot turn into a spin loop.
     */
    val pollIntervalMs: Long
        get() = ((targetDurationSecs * 1000).toLong() / 2).coerceIn(1_000L, 10_000L)

    companion object {
        private const val NO_ENCRYPTION = "NONE"
        private const val AES_128 = "AES-128"

        /**
         * Does this look like a playlist rather than a stream of video?
         *
         * Checked on the content type *and* the first bytes, because providers label `.m3u8` as
         * everything from `application/vnd.apple.mpegurl` to `text/plain` to `video/mp2t`, and the
         * URL's extension disappears behind a redirect. The body itself never lies.
         */
        fun looksLikePlaylist(contentType: String?, body: String): Boolean =
            body.trimStart().startsWith("#EXTM3U") ||
                contentType?.lowercase()?.let {
                    it.contains("mpegurl") || it.contains("m3u")
                } == true

        /**
         * Is this a **master** playlist — a list of qualities, each its own media playlist — rather
         * than a list of segments? Read as segments, its quality lines were fetched and written into
         * the recording as text (#243).
         */
        fun isMaster(text: String): Boolean = text.lineSequence().any { it.trim().startsWith(STREAM_INF) }

        /**
         * The media playlist to record from a master: the highest `BANDWIDTH`, as the player picks
         * by default. With no bandwidths stated, the first listed. Null for a media playlist.
         */
        fun bestVariant(text: String): String? {
            var best: Pair<Long, String>? = null
            var pendingBandwidth: Long? = null
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith(STREAM_INF) ->
                        pendingBandwidth = attribute(line.substringAfter(':'), "BANDWIDTH")?.toLongOrNull() ?: 0L
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> pendingBandwidth?.let { bw ->
                        if (best == null || bw > best!!.first) best = bw to line
                        pendingBandwidth = null
                    }
                }
            }
            return best?.second
        }

        /**
         * Does a downloaded "segment" start with `#EXTM3U`? Then it is a playlist, and writing it into
         * a `.ts` file makes a recording that can never play. A UTF-8 BOM and leading blanks are skipped.
         */
        fun isPlaylistBody(head: ByteArray): Boolean =
            String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

        /**
         * Parse a media playlist. Unknown tags are ignored rather than refused: HLS gains tags all
         * the time and a recorder that stopped at one it had not seen would be broken by its own
         * strictness.
         */
        fun parse(text: String): HlsMediaPlaylist {
            var mediaSequence = 0L
            var targetDuration = DEFAULT_TARGET_SECS
            var endList = false
            var encryption: String? = null
            var key: Key? = null
            var pendingDuration = 0.0
            val segments = mutableListOf<Segment>()

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: mediaSequence
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        targetDuration = line.substringAfter(':').trim().toDoubleOrNull() ?: targetDuration
                    line.startsWith("#EXT-X-ENDLIST") -> endList = true
                    line.startsWith("#EXT-X-KEY:") -> {
                        val attrs = line.substringAfter(':')
                        val method = attribute(attrs, "METHOD")
                        // The strongest method seen decides the refusal; NONE never undoes SAMPLE-AES.
                        if (method != null && (encryption == null || isEncryptedMethod(method))) encryption = method
                        key = if (method.equals(AES_128, ignoreCase = true)) {
                            attribute(attrs, "URI")?.let { Key(it, attribute(attrs, "IV")) }
                        } else {
                            null
                        }
                    }
                    line.startsWith("#EXTINF:") ->
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    // Any other tag is somebody else's business.
                    line.startsWith("#") -> Unit
                    else -> {
                        segments += Segment(line, pendingDuration, mediaSequence + segments.size, key)
                        pendingDuration = 0.0
                    }
                }
            }
            return HlsMediaPlaylist(
                segments = segments,
                mediaSequence = mediaSequence,
                targetDurationSecs = targetDuration,
                endList = endList,
                encryptionMethod = encryption,
            )
        }

        private fun isEncryptedMethod(method: String) =
            !method.equals(NO_ENCRYPTION, ignoreCase = true) && !method.equals(AES_128, ignoreCase = true)

        /** `KEY=VALUE,KEY="VALUE"` attribute lists, as every `#EXT-X-` tag uses. */
        private fun attribute(attributes: String, name: String): String? {
            var depth = false
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            attributes.forEach { c ->
                when {
                    c == '"' -> { depth = !depth; current.append(c) }
                    c == ',' && !depth -> { parts += current.toString(); current.clear() }
                    else -> current.append(c)
                }
            }
            parts += current.toString()
            return parts.firstNotNullOfOrNull { part ->
                val (key, value) = part.split('=', limit = 2).let {
                    it.first().trim() to it.getOrElse(1) { "" }.trim()
                }
                if (key.equals(name, ignoreCase = true)) value.trim('"') else null
            }
        }

        /** What to assume when the playlist does not say. Six seconds is the usual live segment. */
        private const val DEFAULT_TARGET_SECS = 6.0

        private const val STREAM_INF = "#EXT-X-STREAM-INF"
    }
}
