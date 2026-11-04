package tv.own.owntv.player

/**
 * Sees a live picture that ExoPlayer is throwing away faster than it shows it.
 *
 * Fed the running totals of rendered and dropped frames once per health poll, it answers true when, over
 * the last [WINDOW_POLLS] polls, more than [MIN_DROPS] frames were dropped *and* more were dropped than
 * shown. Measured on an Android 11 TCL whose `OMX.realtek.video.decoder` hands reordered 1080p59.94
 * pictures back in decode order: ExoPlayer discards most of them as late, ~500 dropped against ~20 shown
 * per 2.5 s poll — a 7.5 fps slideshow. ExoPlayer cannot reorder a decoder's output, and mpv plays the
 * same stream cleanly on the same TV, so the answer is the other engine. A healthy 60 fps channel shows
 * ~150 frames per poll and drops at most a few dozen while it joins, so it never qualifies.
 */
internal class FrameDropWatch {
    private val frames = LongArray(WINDOW_POLLS + 1)
    private val drops = LongArray(WINDOW_POLLS + 1)
    private var count = 0

    fun reset() { count = 0 }

    fun poll(framesTotal: Long, droppedTotal: Long): Boolean {
        // A counter that went backwards belongs to a new decoder session: start the window over.
        if (count > 0 && (framesTotal < frames[(count - 1) % frames.size] || droppedTotal < drops[(count - 1) % drops.size])) count = 0
        frames[count % frames.size] = framesTotal
        drops[count % drops.size] = droppedTotal
        count++
        if (count <= WINDOW_POLLS) return false
        val oldest = count % frames.size
        val dropped = droppedTotal - drops[oldest]
        val shown = framesTotal - frames[oldest]
        return dropped > MIN_DROPS && dropped > shown
    }

    companion object {
        const val WINDOW_POLLS = 4   // 4 × 2.5 s health polls
        const val MIN_DROPS = 100L
    }
}
