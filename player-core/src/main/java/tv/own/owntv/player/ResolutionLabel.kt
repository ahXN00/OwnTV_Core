package tv.own.owntv.player

/**
 * Shared resolution classifier for both playback engines (ExoPlayer live preview and mpv), so a
 * channel never reports one quality on one engine and another on the other.
 *
 * Provider streams are frequently cinemascope or otherwise letterbox-cropped (3840×1600, 1920×800,
 * 1280×536): the picture loses *rows*, so height alone reads a real 4K feed as "1440p" and a 1080p
 * feed as "720p". Width survives a vertical crop, so we derive the 16:9-equivalent height from the
 * width and classify on whichever of the two implies the higher class.
 *
 * When width is unknown (some mpv property updates report height first) we fall back to height
 * alone rather than dropping the label entirely — a slightly conservative label beats no label.
 *
 * Anything below 480p is reported as its true height ("360p"), never rounded up to a standard.
 */
internal fun classifyResolution(width: Int, height: Int): String? {
    if (height <= 0) return null
    val effective = if (width > 0) maxOf(height, (width * 9) / 16) else height
    return when {
        effective >= 2160 -> ULTRA_HD
        effective >= 1440 -> "1440p"
        effective >= 1080 -> "1080p"
        effective >= 720 -> "720p"
        effective >= 480 -> "480p"
        else -> "${height}p"
    }
}

private const val ULTRA_HD = "4K"

/** True when an engine's `videoRes` label is 4K — the players then draw it as a badge, not a chip. */
fun isUltraHd(label: String?): Boolean = label == ULTRA_HD
