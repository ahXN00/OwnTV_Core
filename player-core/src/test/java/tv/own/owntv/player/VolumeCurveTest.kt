package tv.own.owntv.player

import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One HUD volume number, one loudness, whichever engine plays. mpv's 150 % used to be +10.6 dB and
 * ExoPlayer's +3.5 dB, and the audio-focus duck (a quarter of the HUD number) muted mpv outright.
 */
class VolumeCurveTest {

    private fun db(amplitude: Double) = 20 * log10(amplitude)

    /** What mpv actually plays for a `volume` property value: `(volume/100)³`. */
    private fun mpvGain(mpvVolume: Double) = (mpvVolume / 100).pow(3)

    @Test
    fun `up to 100 percent the number is linear amplitude`() {
        assertEquals(0.5, VolumeCurve.amplitude(50), 1e-9)
        assertEquals(1.0, VolumeCurve.amplitude(100), 1e-9)
        assertEquals(0.0, VolumeCurve.amplitude(0), 1e-9)
    }

    @Test
    fun `150 percent is about plus 10 dB`() {
        assertEquals(10.0, db(VolumeCurve.amplitude(150)), 0.01)
        assertEquals(1000, VolumeCurve.boostMillibels(150))
    }

    @Test
    fun `mpv plays the same loudness ExoPlayer does`() {
        for (p in listOf(10, 25, 50, 80, 100, 130, 150)) {
            val exo = VolumeCurve.amplitude(p)
            assertEquals("at $p %", exo, mpvGain(VolumeCurve.mpvVolume(p)), 1e-9)
        }
    }

    @Test
    fun `ExoPlayer's boost adds exactly the dB above unity`() {
        assertEquals(0, VolumeCurve.boostMillibels(100))
        assertEquals(0, VolumeCurve.boostMillibels(60))
        assertEquals(600, VolumeCurve.boostMillibels(130))
        assertEquals(db(VolumeCurve.amplitude(130)) * 100, VolumeCurve.boostMillibels(130).toDouble(), 1.0)
    }

    @Test
    fun `mpv never needs more than its volume-max`() {
        assertTrue(VolumeCurve.mpvVolume(150) <= 150.0)
    }

    @Test
    fun `the duck is the same drop from any starting volume`() {
        assertEquals(25, VolumeCurve.shiftedByDb(100, -12.0))
        assertEquals(20, VolumeCurve.shiftedByDb(80, -12.0))
        // From a boosted 150 % (+10 dB), −12 dB lands at −2 dB ≈ 79 %.
        assertEquals(79, VolumeCurve.shiftedByDb(150, -12.0))
        assertEquals(0, VolumeCurve.shiftedByDb(0, -12.0))
    }

    @Test
    fun `out-of-range input is clamped`() {
        assertEquals(VolumeCurve.amplitude(150), VolumeCurve.amplitude(400), 1e-9)
        assertEquals(0.0, VolumeCurve.amplitude(-5), 1e-9)
    }
}
