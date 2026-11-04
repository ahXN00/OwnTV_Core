package tv.own.owntv.player

import kotlin.math.abs

/**
 * The frame rate mpv measured, for a stream that declares none. A wrong rate would ask the TV for the
 * wrong display mode, so a reading counts only when the one before it agrees and both sit on a
 * broadcast rate: the start-up burst and a cache stall each skew a single reading.
 */
internal object MeasuredFps {
    /** The standard rate [previous] and [current] agree on, or null. */
    fun settle(previous: Float?, current: Float?): Float? {
        if (previous == null || current == null || abs(previous - current) > 0.5f) return null
        return OwnTVPlayer.STANDARD_FPS.minByOrNull { abs(it - current) }?.takeIf { abs(it - current) <= 0.6f }
    }
}
