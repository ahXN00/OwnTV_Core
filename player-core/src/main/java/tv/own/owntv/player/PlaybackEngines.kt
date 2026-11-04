package tv.own.owntv.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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

    /** How hard the OS is squeezing, from an `onTrimMemory` level. */
    enum class Pressure { NONE, LOW, CRITICAL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // From Android 14 the RUNNING_* trim levels are no longer delivered, so the only pressure
        // signal left is asking. Polled only while something plays; older Android keeps the callback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scope.launch {
                combine(player.isPlaying, livePreview.isPlaying, pool.inUse) { a, b, c -> a || b || c }
                    .distinctUntilChanged()
                    .collectLatest { playing -> if (playing) pollMemory() }
            }
        }
    }

    private suspend fun pollMemory() {
        val am = player.appContext.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        var applied = Pressure.NONE
        Log.i(TAG, "memory poll on")
        try {
            while (true) {
                am.getMemoryInfo(info)
                val pressure = pressureOf(info.lowMemory, info.availMem, info.threshold)
                // Once per pressure episode: act when it rises, re-arm when it is over.
                if (pressure > applied) {
                    Log.i(TAG, "memory pressure $pressure avail=${info.availMem shr 20}MiB threshold=${info.threshold shr 20}MiB")
                    applyPressure(pressure)
                }
                applied = if (pressure == Pressure.NONE) Pressure.NONE else maxOf(applied, pressure)
                delay(MEMORY_POLL_MS)
            }
        } finally {
            Log.i(TAG, "memory poll off")
        }
    }

    /** Forward `Application.onTrimMemory`. Levels that only mean "the UI is hidden" are ignored. */
    fun onTrimMemory(level: Int) = applyPressure(pressureOf(level))

    private fun applyPressure(pressure: Pressure) {
        if (pressure == Pressure.NONE) return
        player.onTrimMemory()
        if (pressure == Pressure.CRITICAL) {
            livePreview.onMemoryPressure()
            pool.onMemoryPressure()
        }
    }

    /** Television: Home / screensaver / another app. Every engine remembers what it had and frees it. */
    fun onAppBackgrounded() {
        player.onAppBackgrounded()
        livePreview.onAppBackgrounded()
        pool.onAppBackgrounded()
        heroPreview?.stop()
    }

    /** Television: back in front. Paired with [onAppBackgrounded]; a no-op on a fresh launch. */
    fun onAppForegrounded() {
        player.onAppForegrounded()
        livePreview.onAppForegrounded()
        pool.onAppForegrounded()
    }

    /**
     * Television: forget what [onAppForegrounded] would bring back, in every engine — the profile gate is
     * about to take the screen, and nothing of the session before it may play again behind it.
     */
    fun discardBackgroundRestore() {
        player.discardBackgroundRestore()
        livePreview.discardBackgroundRestore()
        pool.discardBackgroundRestore()
    }

    companion object {
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

        /**
         * The Android 14+ form, from `ActivityManager.MemoryInfo`: at or below the kill threshold
         * (`lowMemory`) is CRITICAL, within one more threshold of it is LOW — the same two steps the
         * trim levels gave.
         */
        fun pressureOf(lowMemory: Boolean, availMem: Long, threshold: Long): Pressure = when {
            lowMemory || availMem <= threshold -> Pressure.CRITICAL
            availMem <= threshold * 2 -> Pressure.LOW
            else -> Pressure.NONE
        }

        private const val MEMORY_POLL_MS = 5_000L
        private const val TAG = "OwnTV-Memory"
    }
}
