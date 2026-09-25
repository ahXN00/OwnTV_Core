package tv.own.owntv.player

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Night mode (N9) and volume levelling (N10) on the ExoPlayer path, and the passthrough rule. */
class AudioDynamicsTest {

    private val rate = 48_000

    /** [secs] of a 440 Hz stereo tone at [dbfs] peak. */
    private fun tone(dbfs: Double, secs: Double): ShortArray {
        val amp = 32767 * Math.pow(10.0, dbfs / 20)
        val frames = (rate * secs).toInt()
        return ShortArray(frames * 2) { i -> (amp * sin(2 * PI * 440 * (i / 2) / rate)).toInt().toShort() }
    }

    private fun rmsDb(s: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until s.size) { val v = s[i] / 32768.0; sum += v * v }
        return 20 * log10(sqrt(sum / (s.size - from)))
    }

    private fun run(s: ShortArray, night: Boolean, level: Boolean): ShortArray {
        val d = AudioDynamics(rate, 2)
        s.asList().chunked(4096).fold(0) { at, chunk ->
            val buf = chunk.toShortArray()
            d.process(buf, buf.size, night, level)
            buf.copyInto(s, at)
            at + buf.size
        }
        return s
    }

    @Test
    fun `both off leaves the samples untouched`() {
        val input = tone(-10.0, 0.5)
        assertArrayEquals(input.copyOf(), run(input, night = false, level = false))
    }

    @Test
    fun `levelling brings a quiet and a loud programme close together`() {
        val quiet = rmsDb(run(tone(-40.0, 20.0), night = false, level = true).let { it.copyOfRange(it.size / 2, it.size) })
        val loud = rmsDb(run(tone(-6.0, 20.0), night = false, level = true).let { it.copyOfRange(it.size / 2, it.size) })
        // Before: 34 dB apart. After: the quiet one is lifted by the full 12 dB, the loud one lowered.
        assertTrue("quiet $quiet loud $loud", loud - quiet < 34 - 20)
    }

    @Test
    fun `levelling holds its gain in silence`() {
        val out = run(tone(-80.0, 5.0), night = false, level = true)
        assertEquals(rmsDb(tone(-80.0, 5.0)), rmsDb(out), 0.5)
    }

    @Test
    fun `night mode narrows the range between loud and quiet`() {
        val quiet = rmsDb(run(tone(-45.0, 2.0), night = true, level = false), from = rate)
        val loud = rmsDb(run(tone(-3.0, 2.0), night = true, level = false), from = rate)
        assertTrue("range ${loud - quiet} dB", loud - quiet < 42 - 15)
    }

    @Test
    fun `output never clips`() {
        val out = run(tone(-40.0, 10.0).also { it.fill(30_000, it.size / 2) }, night = true, level = true)
        assertTrue(out.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
    }

    @Test
    fun `either switch stops passthrough`() {
        assertTrue(AudioDynamics.passthroughAllowed(setting = true, night = false, levelling = false))
        assertFalse(AudioDynamics.passthroughAllowed(setting = true, night = true, levelling = false))
        assertFalse(AudioDynamics.passthroughAllowed(setting = true, night = false, levelling = true))
        assertFalse(AudioDynamics.passthroughAllowed(setting = false, night = false, levelling = false))
    }
}
