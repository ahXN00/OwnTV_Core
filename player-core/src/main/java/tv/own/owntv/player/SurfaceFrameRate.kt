package tv.own.owntv.player

import android.os.Build
import android.view.Surface

/**
 * The `Surface.setFrameRate()` hint for an mpv video surface — both apps' auto frame rate for mpv.
 * (ExoPlayer sets its own through `setVideoChangeFrameRateStrategy`.)
 *
 * [seamlessOnly] is the phone's rule (owner decision 7): switch only where the panel can do it without
 * blanking, e.g. a 120 Hz phone dropping to 50 Hz for 25 fps. The television allows a real mode switch.
 * Below Android 12 the platform takes no strategy and treats the hint as seamless-only anyway.
 *
 * [fps] ≤ 0 clears the hint. Best-effort: a no-op below Android 11 and never throws.
 */
object SurfaceFrameRate {
    fun apply(surface: Surface, fps: Float, seamlessOnly: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        runCatching {
            if (fps <= 0f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    surface.clearFrameRate()
                } else {
                    // clearFrameRate() is API 34; on 11–13 a rate of 0 clears the hint (the API 30 contract).
                    @Suppress("DEPRECATION")
                    surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    if (seamlessOnly) Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS else Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
}
