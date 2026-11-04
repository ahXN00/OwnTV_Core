package tv.own.owntv.player

import kotlin.math.abs

/**
 * A video's frame rate measured from the presentation timestamps of the frames being rendered.
 *
 * Counting rendered frames against the wall clock ([FpsSample]) cannot tell 59.94 from 60 — they differ by
 * one frame in 17 seconds — so Auto frame rate picked a 60 Hz mode for 59.94 content and the panel dropped a
 * frame every 17 s. Timestamps carry the exact interval: the mean gap over a few seconds of frames resolves
 * the NTSC rates. A gap far from the median (a dropped frame) is left out, and a timestamp jump (a
 * feed that restarts its clock, the next channel) starts the window again, so neither bends the reading.
 *
 * Fed from a video frame listener on the playback thread and read on the main thread, hence synchronized.
 */
class PtsFrameRate {
    private val gaps = LongArray(WINDOW)
    private var count = 0
    private var next = 0
    private var lastUs = NONE

    @Synchronized
    fun add(presentationTimeUs: Long) {
        if (lastUs != NONE) {
            val gap = presentationTimeUs - lastUs
            if (gap in 1..MAX_GAP_US) {
                gaps[next] = gap
                next = (next + 1) % WINDOW
                if (count < WINDOW) count++
            } else {
                // A jump: the clock restarted, or these are the next channel's frames (unrelated clock).
                // Gaps from before it belong to other video.
                count = 0
                next = 0
            }
        }
        lastUs = presentationTimeUs
    }

    /** The rate, snapped to a standard one when it is that close; null until [MIN_SPAN_US] of video is in. */
    @Synchronized
    fun rate(): Float? {
        if (count == 0) return null
        val window = gaps.copyOf(count).also { it.sort() }
        val median = window[count / 2]
        // Within a quarter of the median: a dropped frame (twice the gap) stays out; the alternating
        // 1501/1502-tick gaps of 59.94 stay in.
        val steady = window.filter { it * 4 >= median * 3 && it * 4 <= median * 5 }
        if (steady.sum() < MIN_SPAN_US) return null
        val raw = (1_000_000.0 / steady.average()).toFloat()
        val nearest = STANDARD_RATES.minByOrNull { abs(it - raw) } ?: return raw
        return if (abs(nearest - raw) <= nearest * SNAP_FRACTION) nearest else raw
    }

    @Synchronized
    fun reset() {
        count = 0
        next = 0
        lastUs = NONE
    }

    private companion object {
        const val WINDOW = 600
        /**
         * Some providers stamp frames to the whole millisecond (gaps of 16 and 17 ms at 59.94). Rounding
         * does not add up across frames — the error is at most 1 ms over the whole span — so 4 s holds it to
         * 0.025 %, a quarter of the 0.1 % between 59.94 and 60. One second could not tell them apart.
         */
        const val MIN_SPAN_US = 4_000_000L
        /** Longer than any real frame interval (5 fps); a bigger gap is a jump, not a frame. */
        const val MAX_GAP_US = 200_000L
        const val NONE = Long.MIN_VALUE
        /** 59.94 and 60 are 0.1 % apart; over [MIN_SPAN_US] a reading is within 0.025 % of the true rate. */
        const val SNAP_FRACTION = 0.0004f
        val STANDARD_RATES = floatArrayOf(
            23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f,
        )
    }
}
