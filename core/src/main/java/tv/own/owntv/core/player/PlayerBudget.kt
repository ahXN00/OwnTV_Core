package tv.own.owntv.core.player

import android.app.ActivityManager
import android.content.Context

/**
 * Device-scaled memory budget for the player. The old fixed 256 MiB demuxer cache was tuned on the
 * Android Studio emulator (which borrows the host PC's RAM) and OOM-killed real TVs: a budget
 * 4K Google TV gives a foreground app only a few hundred MB before the low-memory killer fires
 * (observed: PSS 389–620 MB at death on a TCL G10, all reason=LOW_MEMORY).
 */
data class PlayerBudget(
    /** mpv demuxer-max-bytes (forward read-ahead cache). */
    val demuxerMaxBytes: String,
    /** mpv demuxer-max-back-bytes (kept-behind cache for small back-seeks). */
    val demuxerBackBytes: String,
    /** mpv demuxer-readahead-secs. */
    val readaheadSecs: String,
    /** mpv cache-secs. */
    val cacheSecs: String,
    /** True on TV-class / low-RAM devices: also use cheaper rgba8 framebuffers (a 4K rgba16f
     *  intermediate is ~64 MB each; rgba8 halves that, invisible on 8-bit panels). */
    val lowSpec: Boolean,
    /**
     * The most a film's forward buffer may hold when the user picked a Film buffer (Settings → Player
     * → Streaming), in either engine; ExoPlayer is held lower still, to a quarter of the Java heap, because it
     * buffers there rather than in native memory (`SmartBuffer.exoHeapCapBytes`). Auto never reaches it:
     * Auto keeps exactly [demuxerMaxBytes] on mpv and ExoPlayer's tier target. 0 (the default) means "no raise", so the tier's own bytes stay the ceiling
     * for any caller that doesn't set it. See `SmartBuffer` in player-core.
     */
    val filmCeilingBytes: Long = 0L,
) {
    companion object {
        /** Aggressive shrink target applied live when the OS signals critical memory pressure. */
        const val TRIM_DEMUXER_BYTES = "24MiB"

        private const val MIB = 1024L * 1024L

        /**
         * Film ceilings are twice each tier's mpv cache, the same 2x headroom the live buffer allows a
         * TV-class device (LiveBuffer.targetBufferBytes). On a 2 GB TV that keeps a film under ~100 MB of
         * buffer, still well inside the ~180 MB player budget described above.
         */
        const val LOW_FILM_CEILING_BYTES = 96 * MIB
        const val MID_FILM_CEILING_BYTES = 256 * MIB
        const val HIGH_FILM_CEILING_BYTES = 512 * MIB

        // Total RAM never changes, so the answer is worked out once per process and shared by every
        // engine build, the diagnostics log and the settings repository.
        @Volatile private var cached: PlayerBudget? = null

        fun of(context: Context): PlayerBudget = cached ?: compute(context).also { cached = it }

        private fun compute(context: Context): PlayerBudget {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
            return when {
                am.isLowRamDevice || totalGb < 3.0 -> PlayerBudget(
                    // TV-class (1.5–2.5 GB total): keep the whole player well under ~180 MB PSS.
                    demuxerMaxBytes = "48MiB", demuxerBackBytes = "16MiB",
                    readaheadSecs = "10", cacheSecs = "30", lowSpec = true,
                    filmCeilingBytes = LOW_FILM_CEILING_BYTES,
                )
                totalGb < 6.0 -> PlayerBudget(
                    demuxerMaxBytes = "128MiB", demuxerBackBytes = "32MiB",
                    readaheadSecs = "30", cacheSecs = "60", lowSpec = false,
                    filmCeilingBytes = MID_FILM_CEILING_BYTES,
                )
                else -> PlayerBudget(
                    // RAM-rich devices (emulators, high-end boxes) keep the deep 4K/8K read-ahead.
                    demuxerMaxBytes = "256MiB", demuxerBackBytes = "64MiB",
                    readaheadSecs = "60", cacheSecs = "120", lowSpec = false,
                    filmCeilingBytes = HIGH_FILM_CEILING_BYTES,
                )
            }
        }
    }
}
