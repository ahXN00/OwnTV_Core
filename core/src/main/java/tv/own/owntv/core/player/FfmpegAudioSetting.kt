package tv.own.owntv.core.player

import android.content.Context

/**
 * "FFmpeg audio decoder": whether ExoPlayer may decode sound with the engine's FFmpeg, for formats the
 * device has no decoder for (DTS, TrueHD, MP2 on many boxes). Off by default — ExoPlayer then uses only
 * the device's own audio decoders and passthrough, exactly the audio path it had before FFmpeg audio.
 *
 * Read once per process, at app start ([load]), and fixed for the life of the process: a change only
 * takes effect after a restart, so no player ever runs half on one audio path and half on the other.
 * Kept per device in its own preferences file — it is about what this device's decoders do, so it is not
 * part of backups or local sync.
 */
object FfmpegAudioSetting {

    private const val PREFS_NAME = "owntv_audio_engine"
    private const val KEY_FFMPEG_AUDIO = "ffmpeg_audio"

    /** What this process plays with. False until [load] runs, which means "off". */
    @Volatile
    var active: Boolean = false
        private set

    /** Called by each app in `Application.onCreate`, before Koin starts and before any player exists. */
    fun load(context: Context) {
        active = saved(context)
    }

    /** The saved choice — what the next start of the app will use. */
    fun saved(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FFMPEG_AUDIO, false)

    /** Saves the choice for the next start. Written synchronously: the caller restarts the app right after. */
    fun save(context: Context, on: Boolean): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FFMPEG_AUDIO, on).commit()
}
