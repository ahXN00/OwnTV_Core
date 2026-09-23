package tv.own.owntv.player

import kotlin.math.cbrt
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the HUD's volume number means, identically on every engine.
 *
 * - **0–100 %** is linear amplitude: 50 % is half, −6 dB. ExoPlayer's `volume` is already linear; mpv
 *   cubes its own `volume` property (`gain = (volume/100)³`), so it is handed the cube root.
 * - **100–150 %** is a boost of [BOOST_DB_PER_PERCENT] per percent, so 150 % ≈ +10 dB — ExoPlayer via
 *   the LoudnessEnhancer ([VolumeBoost]), mpv via the same cube root of the amplitude.
 *
 * Before this, mpv's 150 % was +10.6 dB and ExoPlayer's +3.5 dB, 50 % was −18 dB against −6 dB, and
 * a remembered "130 %" sounded different whenever the fallback ladder moved a channel between engines.
 */
internal object VolumeCurve {
    const val MAX_PERCENT = 150
    const val BOOST_DB_PER_PERCENT = 0.2

    /** Linear amplitude for a HUD [percent]. */
    fun amplitude(percent: Int): Double {
        val p = percent.coerceIn(0, MAX_PERCENT)
        return if (p <= 100) p / 100.0 else 10.0.pow((p - 100) * BOOST_DB_PER_PERCENT / 20.0)
    }

    /** mpv's `volume` property for a HUD [percent]. At most ≈146.8, inside `volume-max = 150`. */
    fun mpvVolume(percent: Int): Double = 100.0 * cbrt(amplitude(percent))

    /** LoudnessEnhancer target gain in millibels; 0 at or below 100 %. */
    fun boostMillibels(percent: Int): Int =
        ((percent.coerceIn(0, MAX_PERCENT) - 100).coerceAtLeast(0) * BOOST_DB_PER_PERCENT * 100).roundToInt()

    /** The HUD percent [db] decibels below (negative) or above [percent]. */
    fun shiftedByDb(percent: Int, db: Double): Int {
        val a = amplitude(percent) * 10.0.pow(db / 20.0)
        val p = if (a <= 1.0) a * 100.0 else 100.0 + 20.0 * log10(a) / BOOST_DB_PER_PERCENT
        return p.roundToInt().coerceIn(0, MAX_PERCENT)
    }
}
