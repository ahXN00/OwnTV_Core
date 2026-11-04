package tv.own.owntv.player

/**
 * Sees ExoPlayer losing enough frames to judder, short of the slideshow [FrameDropWatch] acts on.
 *
 * A channel dropping a quarter of its frames reads as judder, not as a frozen or failing picture, so
 * nothing else in the live engine reacts to it and the user has no hint that the other engine may play it
 * cleanly. Fed the running totals once per health poll, this answers true — once, until [reset] — when over
 * the last [WINDOW_POLLS] polls (30 s) more than [MAX_DROP_PERCENT] % of the frames were dropped and at
 * least [MIN_DROPS] of them, so a burst while the channel joins never counts.
 */
internal class SmoothnessWatch {
    private val frames = LongArray(WINDOW_POLLS + 1)
    private val drops = LongArray(WINDOW_POLLS + 1)
    private var count = 0
    private var fired = false

    fun reset() {
        count = 0
        fired = false
    }

    fun poll(framesTotal: Long, droppedTotal: Long): Boolean {
        if (fired) return false
        // A counter that went backwards belongs to a new decoder session: start the window over.
        if (count > 0 && (framesTotal < frames[(count - 1) % frames.size] || droppedTotal < drops[(count - 1) % drops.size])) count = 0
        frames[count % frames.size] = framesTotal
        drops[count % drops.size] = droppedTotal
        count++
        if (count <= WINDOW_POLLS) return false
        val oldest = count % frames.size
        val dropped = droppedTotal - drops[oldest]
        val shown = framesTotal - frames[oldest]
        fired = dropped >= MIN_DROPS && dropped * 100 > (dropped + shown) * MAX_DROP_PERCENT
        return fired
    }

    companion object {
        const val WINDOW_POLLS = 12 // 12 × 2.5 s health polls
        const val MAX_DROP_PERCENT = 20
        const val MIN_DROPS = 150L
    }
}
