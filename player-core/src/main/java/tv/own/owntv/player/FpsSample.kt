package tv.own.owntv.player

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Give each independently-timed poller its own instance to avoid corruption.
 */
class FpsSample {
    var renderedCount = 0
    var atMs = 0L
    var lastFps: Float? = null

    /** True once TWO consecutive windows have landed on the SAME standard rate. One window is not
     *  enough: 24 and 25 are only 4% apart, so a window that caught a brief stall reads 24.4 on a real
     *  25fps channel, snaps to 24, and looks every bit as "matched" as the right answer. Callers use
     *  this as the signal to stop sampling. */
    var confident = false
        private set

    /** The standard rate the previous window landed on, so [confident] can require a repeat. */
    private var lastSnapped: Float? = null

    /** Takes one sample via [p]'s decoder counters and immediately publishes it to [lastFps]. */
    fun sample(p: ExoPlayer): Float? {
        peek(p)?.let { lastFps = it }
        return lastFps
    }

    /** Like [sample], but returns the fresh reading (if any) without publishing it to [lastFps] — for a
     *  caller that wants to judge [confident] before deciding whether to [publish] it. */
    fun peek(p: ExoPlayer): Float? {
        val counters = p.videoDecoderCounters ?: return null
        counters.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        if (rendered == 0) return null
        val now = SystemClock.elapsedRealtime()
        var fresh: Float? = null
        if (atMs > 0) {
            val dFrames = rendered - renderedCount
            val dSecs = (now - atMs) / 1000f
            if (dFrames > 0 && dSecs > 0) {
                fresh = accept(dFrames / dSecs)
            }
        }
        renderedCount = rendered
        atMs = now
        return fresh
    }

    /** Snap one window's raw rate and update [confident]. Split out of [peek] so the part that decides
     *  between 24 and 25 can be tested without an ExoPlayer behind it. */
    internal fun accept(raw: Float): Float {
        val snapped = nearestStandardRate(raw)
        confident = snapped != null && snapped == lastSnapped
        lastSnapped = snapped
        return snapped ?: raw
    }

    fun publish(fps: Float) {
        lastFps = fps
    }

    /** Fresh window on the next measurement; keeps [lastFps] so a display doesn't blank mid-remeasure.
     *  The agreement history goes, though — a rate carried over from the previous tune would confirm the
     *  new one after a single window, which is the very thing [confident] exists to prevent. */
    fun resetWindow() {
        atMs = 0L
        confident = false
        lastSnapped = null
    }

    /** Full reset for a genuine channel/file change. */
    fun resetAll() {
        renderedCount = 0
        atMs = 0L
        lastFps = null
        confident = false
        lastSnapped = null
    }

    private companion object {
        val STANDARD_FRAME_RATES = floatArrayOf(24f, 25f, 30f, 50f, 60f, 90f, 120f)

        /** The standard rate [fps] belongs to, or null when it is nowhere near one. Deliberately NOT
         *  "the value changed": a window that lands exactly on 50.0 has still identified a standard
         *  rate, and treating that as unrecognised was why a clean reading counted for nothing. */
        fun nearestStandardRate(fps: Float): Float? {
            val nearest = STANDARD_FRAME_RATES.minByOrNull { abs(it - fps) } ?: return null
            return nearest.takeIf { abs(it - fps) <= it * 0.05f }
        }
    }
}
