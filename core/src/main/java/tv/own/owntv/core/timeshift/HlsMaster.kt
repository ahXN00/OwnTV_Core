package tv.own.owntv.core.timeshift

/**
 * The two HLS facts the recorder's [tv.own.owntv.core.recording.HlsMediaPlaylist] does not need but a
 * timeshift buffer does: which variant of a master playlist to save, and whether the segments are
 * fragmented MP4 (`#EXT-X-MAP`) rather than MPEG-TS.
 *
 * **One variant, fixed at the start.** A buffer is one continuous stream handed to a player; switching
 * quality mid-buffer would change the codec setup under it. A variant whose sound is a separate
 * rendition cannot be one stream at all, so it is passed over — and a master with nothing else is
 * refused, which plays the channel the ordinary way instead.
 */
internal object HlsMaster {

    data class Variant(val uri: String, val bandwidth: Long, val height: Int?, val audioGroup: String?)

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun variants(text: String): List<Variant> {
        val out = mutableListOf<Variant>()
        var pending: String? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = line.substringAfter(':')
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    val attrs = pending!!
                    out += Variant(
                        uri = line,
                        bandwidth = attribute(attrs, "BANDWIDTH")?.toLongOrNull() ?: 0L,
                        height = attribute(attrs, "RESOLUTION")?.substringAfter('x', "")?.toIntOrNull(),
                        audioGroup = attribute(attrs, "AUDIO"),
                    )
                    pending = null
                }
            }
        }
        return out
    }

    /** Audio groups whose renditions live in playlists of their own (a `URI`), i.e. separate sound. */
    fun separateAudioGroups(text: String): Set<String> =
        text.lineSequence().map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { it.substringAfter(':') }
            .filter { attribute(it, "TYPE").equals("AUDIO", ignoreCase = true) && attribute(it, "URI") != null }
            .mapNotNull { attribute(it, "GROUP-ID") }
            .toSet()

    /**
     * The variant to save: the best one within [maxHeight] (the Settings quality limit, null for none)
     * that carries its own sound. When every variant is above the limit, the smallest is taken.
     */
    fun choose(text: String, maxHeight: Int?): Variant? {
        val separate = separateAudioGroups(text)
        val muxed = variants(text).filter { it.audioGroup == null || it.audioGroup !in separate }
        if (muxed.isEmpty()) return null
        val within = if (maxHeight == null) muxed else muxed.filter { (it.height ?: 0) <= maxHeight }
        return within.maxByOrNull { it.bandwidth } ?: muxed.minByOrNull { it.bandwidth }
    }

    /** The `#EXT-X-MAP` URI of a media playlist — present exactly when the segments are fragmented MP4. */
    fun mapUri(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#EXT-X-MAP:") }
            ?.let { attribute(it.substringAfter(':'), "URI") }

    /** `KEY=VALUE,KEY="VALUE"` — quoted values may hold commas. */
    fun attribute(attributes: String, name: String): String? {
        var quoted = false
        val current = StringBuilder()
        val parts = mutableListOf<String>()
        attributes.forEach { c ->
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts.firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=').trim()
            if (key.equals(name, ignoreCase = true) && part.contains('=')) part.substringAfter('=').trim().trim('"') else null
        }
    }
}
