package tv.own.owntv.core.settings

import tv.own.owntv.core.player.MiniPlayerPosition

/**
 * What a restored setting may be. The Settings screens can only ever write values in range, but a
 * backup is a file — written by another build, or edited by hand — and its values used to be applied
 * unchecked. A seek step of 0 broke seeking outright. Null means "skip it, keep this device's value".
 *
 * Keyed by preference name, the same name the backup file uses.
 */
internal object SettingsImportRules {

    /** The player's own audio-delay range (its HUD stepper stops at ±5 s). */
    const val AUDIO_DELAY_LIMIT_MS = 5_000

    /** Sanity bounds for an imported subtitle scale — far wider than any stepper, narrow enough that a
     *  corrupt value cannot draw subtitles invisibly small or larger than the screen. */
    const val SUB_SCALE_MIN = 0.25
    const val SUB_SCALE_MAX = 4.0

    fun int(name: String, v: Int): Int? = when (name) {
        "seek_step_sec" -> v.takeIf { it in SeekSteps.SEEK_CHOICES }
        "live_rewind_step_sec" -> v.takeIf { it in SeekSteps.LIVE_REWIND_CHOICES }
        "audio_delay_ms" -> v.coerceIn(-AUDIO_DELAY_LIMIT_MS, AUDIO_DELAY_LIMIT_MS)
        else -> v
    }

    /** Named presets must name one this build has; the readers would fall back anyway, but silently. */
    fun string(name: String, v: String): String? = when (name) {
        "live_latency_mode" -> v.takeIf { value -> LiveLatency.entries.any { it.name == value } }
        "mini_player_position" -> v.takeIf { value -> MiniPlayerPosition.entries.any { it.name == value } }
        else -> v
    }

    /** The subtitle scales: a zero, negative or non-finite multiplier draws nothing (or everything). */
    fun float(v: Double): Float? =
        v.takeIf { it.isFinite() && it > 0 }?.coerceIn(SUB_SCALE_MIN, SUB_SCALE_MAX)?.toFloat()
}
