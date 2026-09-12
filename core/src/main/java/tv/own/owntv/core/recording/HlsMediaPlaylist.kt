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
    data class Segment(val uri: String, val durationSecs: Double, val sequence: Long)

    /** True when the segments are encrypted in a way this recorder will not produce a playable file for. */
    val isEncrypted: Boolean
        get() = encryptionMethod != null && !encryptionMethod.equals(NO_ENCRYPTION, ignoreCase = true)

    /**
     * How long to wait before re-fetching. Half the target duration, so a segment is never missed
     * because the poll landed just before it was published, and never less than a second, so a
     * playlist that reports nonsense cannot turn into a spin loop.
     */
    val pollIntervalMs: Long
        get() = ((targetDurationSecs * 1000).toLong() / 2).coerceIn(1_000L, 10_000L)

    companion object {
        private const val NO_ENCRYPTION = "NONE"

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
         * Parse a media playlist. Unknown tags are ignored rather than refused: HLS gains tags all
         * the time and a recorder that stopped at one it had not seen would be broken by its own
         * strictness.
         */
        fun parse(text: String): HlsMediaPlaylist {
            var mediaSequence = 0L
            var targetDuration = DEFAULT_TARGET_SECS
            var endList = false
            var encryption: String? = null
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
                    line.startsWith("#EXT-X-KEY:") ->
                        encryption = attribute(line.substringAfter(':'), "METHOD") ?: encryption
                    line.startsWith("#EXTINF:") ->
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    // Any other tag is somebody else's business.
                    line.startsWith("#") -> Unit
                    else -> {
                        segments += Segment(line, pendingDuration, mediaSequence + segments.size)
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
    }
}
