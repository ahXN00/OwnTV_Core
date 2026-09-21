package tv.own.owntv.player

/**
 * The one vocabulary for the stream-info overlay's Format row.
 *
 * Three engines can own playback — live ExoPlayer ([LivePreviewEngine]), VOD ExoPlayer
 * ([ExoSubtitleEngine]) and mpv ([OwnTVPlayer.mpvStreamInfo]) — and each learns the container a
 * different way: live *chooses* it ([StreamRoute]), VOD reads back what Media3 resolved, and mpv
 * reports FFmpeg's own demuxer name. Left to themselves they disagree in wording, so the same film
 * reads `MP4` on one engine and `MOV,MP4,M4A,3GP,3G2,MJ2` on another. These labels are what someone
 * reads back to us in a bug report, so all three map onto this file.
 *
 * Protocol and container names, not prose: never translated, classified `protocol` in
 * `safe_literals.txt`.
 */
internal object StreamFormatLabels {
    const val HLS = "HLS"
    const val DASH = "DASH"
    const val MPEG_TS = "MPEG-TS"
    const val MP4 = "MP4"
    const val MKV = "MKV"

    /**
     * Maps a container name onto a label, or null if we do not recognise it.
     *
     * Takes both of the shapes an engine can hand us: a MIME type from Media3
     * (`application/dash+xml`, `video/mp2t`) and FFmpeg's comma-joined demuxer name from mpv
     * (`mov,mp4,m4a,3gp,3g2,mj2`, `matroska,webm`). Substring matching rather than equality is what
     * lets one function serve both — an exact-match table would need every FFmpeg alias list in it.
     *
     * Null rather than a guess: a caller that knows a better fallback than we do (mpv knows the raw
     * name is at least *true*) should be free to use it.
     */
    fun ofContainerName(raw: String?): String? {
        val d = raw?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            // DASH and HLS first: their MIME types carry substrings the container arms also match.
            "dash" in d || "mpd" in d -> DASH
            "mpegurl" in d || "hls" in d || "m3u" in d -> HLS
            "mp2t" in d || "mpegts" in d -> MPEG_TS
            // Matroska before MP4 — FFmpeg names them separately, but a muxed name could carry both.
            "matroska" in d || "webm" in d || "mkv" in d -> MKV
            "mp4" in d || "mov" in d || "m4a" in d || "m4v" in d || "quicktime" in d -> MP4
            else -> null
        }
    }

    /**
     * Maps a URL's file extension onto a label — the last resort, when the player reported no
     * container at all. Exact matching here, unlike [ofContainerName]: an extension is a whole
     * token, and `ts` as a substring would match far too much.
     */
    fun ofFileExtension(ext: String?): String? = when (ext?.lowercase()?.takeIf { it.isNotBlank() }) {
        "m3u8", "m3u" -> HLS
        "mpd" -> DASH
        "ts", "m2ts", "mts" -> MPEG_TS
        "mkv", "webm" -> MKV
        "mp4", "m4v", "mov" -> MP4
        else -> null
    }

    /**
     * What ExoPlayer ended up playing, best evidence first.
     *
     * [containerMimeType] is the honest one — the container Media3 *resolved*, from the manifest or
     * the extractor that won. [requestedMimeType] is only what we asked for, so it is null for the
     * majority of items and can never stand alone. The path extension is a guess, and last.
     *
     * Pure and string-typed so it is unit-testable without a player or an `android.net.Uri`.
     */
    fun resolve(containerMimeType: String?, requestedMimeType: String?, path: String?): String? =
        ofContainerName(containerMimeType)
            ?: ofContainerName(requestedMimeType)
            ?: ofFileExtension(path?.substringAfterLast('/')?.substringAfterLast('.', ""))
}
