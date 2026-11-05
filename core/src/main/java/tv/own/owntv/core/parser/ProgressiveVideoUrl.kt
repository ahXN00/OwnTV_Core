package tv.own.owntv.core.parser

/**
 * Whether a stream URL names a single video file — the shape of a film in a playlist that does not
 * tag its entries (`type` / `tvg-type`). Many do not: an Xtream-style panel's M3U export lists every
 * film as a plain entry ending in `.mkv` or `.mp4`, so without this the whole film catalogue lands in
 * Live TV. Used only for untagged entries; a tag always wins (see [M3uEntry.isVod]).
 *
 * Deliberately narrow, because a live channel filed under Movies is worse than a film left in Live
 * TV:
 *  - only `http`, `https` and `file` URLs (and bare local paths) — never `rtmp`, `rtsp`, `udp`, `rtp`;
 *  - only the last path segment's extension counts, so `/movies.mp4/live/1.ts` stays live, and the
 *    query and fragment are ignored, so `film.mp4?token=…` is still a film;
 *  - segment and manifest formats (`m3u8`, `ts`, `mpd`) are never on the list, and neither is
 *    `flv`: HTTP-FLV is a common live-streaming protocol.
 */
internal object ProgressiveVideoUrl {

    val EXTENSIONS = setOf("mp4", "m4v", "mkv", "avi", "mov", "wmv", "webm", "mpg", "mpeg")

    private val FILE_SCHEMES = setOf("http", "https", "file")

    fun matches(url: String): Boolean {
        val path = pathOf(url.trim()) ?: return false
        val segment = path.substringAfterLast('/')
        val dot = segment.lastIndexOf('.')
        if (dot <= 0 || dot == segment.lastIndex) return false
        return segment.substring(dot + 1).lowercase() in EXTENSIONS
    }

    /** The URL's path without query or fragment; null for a scheme that is not a file transfer. */
    private fun pathOf(url: String): String? {
        val end = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
        val bare = url.substring(0, end)
        val schemeEnd = bare.indexOf("://")
        if (schemeEnd < 0) return bare
        if (bare.substring(0, schemeEnd).lowercase() !in FILE_SCHEMES) return null
        val pathStart = bare.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) null else bare.substring(pathStart)
    }
}
