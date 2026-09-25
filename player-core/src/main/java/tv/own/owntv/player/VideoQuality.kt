package tv.own.owntv.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * N11 — which picture a stream that offers several (HLS / DASH variants) plays at.
 *
 * Two inputs: the Settings limit ([cap]: "Maximum video quality", and on a metered connection the
 * phone's mobile-data limit, the lower wins), and a pick in the player's Quality menu, which beats the
 * limit for the item it was made on and is forgotten at the next one. An engine's own layout limit
 * (Multiview tiles, the 720p preview pane on a 2 GB TV) is combined with [lower], so it can only
 * lower the result.
 */
object VideoQuality {

    /** The Settings limit in lines, or null for none. [metered]: the connection costs money. */
    fun cap(maxHeight: Int, mobileDataMaxHeight: Int, metered: Boolean): Int? =
        lower(maxHeight.takeIf { it > 0 }, mobileDataMaxHeight.takeIf { it > 0 && metered })

    /** The lower of two optional limits. */
    fun lower(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /** The heights a stream offers, highest first — empty unless there are two or more to choose from. */
    fun heights(offered: List<Int>): List<Int> =
        offered.filter { it > 0 }.distinct().sortedDescending().takeIf { it.size > 1 }.orEmpty()

    /** The heights among [tracks]' playable video tracks — see [heights]. */
    fun heightsOf(tracks: Tracks): List<Int> = heights(
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height }
        },
    )

    /** ExoPlayer: force the best-bitrate playable track of exactly [height], or null when none has it. */
    fun overrideFor(tracks: Tracks, height: Int): TrackSelectionOverride? {
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            val best = (0 until g.length)
                .filter { g.isTrackSupported(it) && g.getTrackFormat(it).height == height }
                .maxByOrNull { g.getTrackFormat(it).bitrate }
                ?: continue
            return TrackSelectionOverride(g.mediaTrackGroup, best)
        }
        return null
    }

    /**
     * mpv: which of the video tracks ([id] to height) to select — [pick] exactly when there is one,
     * else the tallest within [cap], else the smallest there is. Null = leave mpv's own choice (nothing
     * to choose between, or no limit and no pick).
     */
    fun mpvTrack(tracks: List<Pair<Int, Int>>, cap: Int?, pick: Int?): Int? {
        val sized = tracks.filter { it.second > 0 }
        if (sized.map { it.second }.distinct().size < 2) return null
        pick?.let { h -> sized.firstOrNull { it.second == h }?.let { return it.first } }
        val limit = cap ?: return null
        return (sized.filter { it.second <= limit }.maxByOrNull { it.second } ?: sized.minByOrNull { it.second })?.first
    }
}

/**
 * N19 — whether this device has a video decoder that can play tunneled (the TV's own hardware keeps
 * picture and sound in step). Asked once per process; the apps show the setting only when true.
 */
object Tunneling {
    val supported: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.startsWith("video/") && runCatching {
                        info.getCapabilitiesForType(type)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
                    }.getOrDefault(false)
                }
            }
        }.getOrDefault(false)
    }

    /** Set by the first failure this run; the stored setting is switched off at the same moment. */
    @Volatile var failedThisSession = false
}
