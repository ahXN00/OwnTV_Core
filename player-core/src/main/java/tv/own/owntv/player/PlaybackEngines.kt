package tv.own.owntv.player

import android.content.ComponentCallbacks2
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every playback engine an app holds, so the app's lifecycle reaches all of them from one call
 * instead of a hand-kept list per app — the list that forgot the Multiview pool on Home, and that
 * never reached the ExoPlayer engines on memory pressure at all.
 *
 * - [onTrimMemory] is for every host: memory pressure means the same thing on a phone and a TV.
 * - [onAppBackgrounded] / [onAppForegrounded] are the **television's** policy — an IPTV box has no
 *   background playback, so Home or the screensaver frees every stream and brings it back after. A
 *   phone plays on with the screen off by design and keeps its own, finer policy; it must not call these.
 *
 * Main thread only, like the engines.
 */
@UnstableApi
class PlaybackEngines(
    private val player: OwnTVPlayer,
    private val livePreview: LivePreviewEngine,
    private val pool: LiveEnginePool,
    private val heroPreview: HeroPreviewEngine? = null,
) {

    private val backgroundListeners = CopyOnWriteArrayList<() -> Unit>()
    private val foregroundHandlers = CopyOnWriteArrayList<() -> Boolean>()

    /**
     * Called from [onAppBackgrounded] before any engine is stopped. Used to cancel server switches
     * that would otherwise open a stream while the app is invisible.
     */
    fun addBackgroundListener(l: () -> Unit) {
        backgroundListeners.add(l)
    }

    fun removeBackgroundListener(l: () -> Unit) {
        backgroundListeners.remove(l)
    }

    /**
     * Called from [onAppForegrounded] before engines restore. A live tune is resumed by its controller
     * so failover state and the in-use server apply. Return true when that handler resumed the channel.
     */
    fun addForegroundHandler(h: () -> Boolean) {
        foregroundHandlers.add(h)
    }

    fun removeForegroundHandler(h: () -> Boolean) {
        foregroundHandlers.remove(h)
    }

    /** How hard the OS is squeezing, from an `onTrimMemory` level. */
    enum class Pressure { NONE, LOW, CRITICAL }

    /** Forward `Application.onTrimMemory`. Levels that only mean "the UI is hidden" are ignored. */
    fun onTrimMemory(level: Int) {
        val pressure = pressureOf(level)
        if (pressure == Pressure.NONE) return
        player.onTrimMemory()
        if (pressure == Pressure.CRITICAL) {
            livePreview.onMemoryPressure()
            pool.onMemoryPressure()
        }
    }

    /** Television: Home / screensaver / another app. Every engine remembers what it had and frees it. */
    fun onAppBackgrounded() {
        for (l in backgroundListeners) runCatching { l() }
        player.onAppBackgrounded()
        livePreview.onAppBackgrounded()
        pool.onAppBackgrounded()
        heroPreview?.stop()
    }

    /**
     * Television: back in front. Paired with [onAppBackgrounded]; a no-op on a fresh launch.
     * A live tune is resumed by its controller so failover state and the in-use server apply.
     * When that handler took the resume, neither engine reopens its saved stream — the handler
     * only schedules start(), so discard the saved reopen before anything else can open it.
     */
    fun onAppForegrounded() {
        val handled = foregroundHandlers.map { runCatching { it() }.getOrDefault(false) }.any { it }
        val plan = resumePlan(handled)
        if (plan.restorePlayer) player.onAppForegrounded() else player.discardBackgroundRestore()
        if (plan.restorePreview) livePreview.onAppForegrounded() else livePreview.discardBackgroundRestore()
        pool.onAppForegrounded()
    }

    companion object {
        /** Whether each engine should reopen its saved stream. A handled resume restores neither. */
        internal data class ResumePlan(val restorePlayer: Boolean, val restorePreview: Boolean)

        internal fun resumePlan(handled: Boolean) = ResumePlan(!handled, !handled)

        /**
         * `RUNNING_LOW` shrinks mpv's cache; `RUNNING_CRITICAL` and `COMPLETE` also move the ExoPlayer
         * engines to the low-RAM buffer. `UI_HIDDEN` fires on every Home press and is deliberately
         * not pressure — it used to trim mpv's cache for the rest of the session. `BACKGROUND` and
         * `MODERATE` arrive while nothing is playing (the television frees its streams on Home).
         */
        @Suppress("DEPRECATION") // the RUNNING_* levels are still the only signal on older TV builds
        fun pressureOf(level: Int): Pressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> Pressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Pressure.LOW
            else -> Pressure.NONE
        }
    }
}
