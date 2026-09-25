package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Night mode (N9) and volume levelling (N10) on 16-bit PCM, for ExoPlayer. mpv gets neither: the bundled
 * libmpv's FFmpeg is built with `--disable-filters`, so an `af=lavfi=[…]` makes mpv play silence (no error
 * is logged). Measured on the TCL, 2026-09-25.
 *
 * - **Levelling** is a slow automatic gain: the loudness of the last few seconds is brought towards one
 *   target, at most ±[LEVEL_MAX_DB], so a quiet channel and a loud one end up alike. Silence holds the
 *   gain where it is instead of turning the noise floor up.
 * - **Night mode** is a compressor with make-up gain: passages above [NIGHT_THRESHOLD_DB] are squeezed
 *   3:1, and everything is lifted so dialogue stays audible at a low volume.
 *
 * Gains move once per [BLOCK_FRAMES] frames and are ramped across the block, so there are no clicks,
 * and the cost is a few multiplications per sample (one `pow` per block) — cheap on an A53. The output is
 * clamped, never wrapped. Both switches are read live, so turning one on or off needs no player rebuild.
 */
class AudioDynamics(private val sampleRate: Int, private val channels: Int) {

    private var levelGain = 1f
    private var levelMeanSquare = -1.0 // < 0: nothing measured yet
    private var envelope = 0f
    private var compGain = 1f
    private var lastGain = 1f

    private val blockSecs = BLOCK_FRAMES.toDouble() / sampleRate
    private val levelMeasure = coefficient(LEVEL_MEASURE_SECS)
    private val levelDown = coefficient(LEVEL_DOWN_SECS)
    private val levelUp = coefficient(LEVEL_UP_SECS)
    private val nightAttack = coefficient(NIGHT_ATTACK_SECS)
    private val nightRelease = coefficient(NIGHT_RELEASE_SECS)

    private fun coefficient(secs: Double): Float = exp(-blockSecs / secs).toFloat()

    /** Process interleaved [samples] in place, first [count] values. */
    fun process(samples: ShortArray, count: Int, night: Boolean, levelling: Boolean) {
        var i = 0
        val blockLen = BLOCK_FRAMES * channels
        while (i < count) {
            val end = minOf(i + blockLen, count)
            var peak = 0f
            var sumSquares = 0.0
            for (j in i until end) {
                val v = samples[j] / FULL_SCALE
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                sumSquares += v * v
            }
            val target = gainFor(peak, sumSquares / (end - i), night, levelling)
            // Ramp from the previous block's gain to this one's, frame by frame — but never above what
            // keeps this block's peak under full scale, or a sudden loud onset would clip while ramping.
            val ceiling = if (peak > 0f) CEILING / peak else Float.MAX_VALUE
            val frames = (end - i) / channels
            val step = if (frames > 0) (target - lastGain) / frames else 0f
            var g = lastGain
            var j = i
            while (j < end) {
                g += step
                val gain = if (g > ceiling) ceiling else g
                for (c in 0 until channels) {
                    if (j + c >= end) break
                    val out = samples[j + c] * gain
                    samples[j + c] = when {
                        out > Short.MAX_VALUE -> Short.MAX_VALUE
                        out < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> out.toInt().toShort()
                    }
                }
                j += channels
            }
            lastGain = target
            i = end
        }
    }

    private fun gainFor(peak: Float, meanSquare: Double, night: Boolean, levelling: Boolean): Float {
        if (levelling) {
            if (meanSquare > GATE_MEAN_SQUARE) {
                levelMeanSquare = if (levelMeanSquare < 0) meanSquare
                else levelMeasure * levelMeanSquare + (1 - levelMeasure) * meanSquare
                val want = (TARGET_RMS / sqrt(levelMeanSquare)).toFloat().coerceIn(LEVEL_MIN, LEVEL_MAX)
                val k = if (want < levelGain) levelDown else levelUp
                levelGain = k * levelGain + (1 - k) * want
            }
        } else {
            levelGain = 1f; levelMeanSquare = -1.0
        }
        val levelled = peak * levelGain
        if (night) {
            val k = if (levelled > envelope) nightAttack else nightRelease
            envelope = k * envelope + (1 - k) * levelled
            val over = envelope / NIGHT_THRESHOLD
            compGain = if (over > 1f) over.toDouble().pow(-(1.0 - 1.0 / NIGHT_RATIO)).toFloat() else 1f
        } else {
            envelope = 0f; compGain = 1f
        }
        var gain = levelGain * (if (night) compGain * NIGHT_MAKEUP else 1f)
        // Never push this block's peak past full scale: the clamp below would flatten it audibly.
        if (peak * gain > CEILING) gain = max(CEILING / peak, 0f)
        return gain
    }

    companion object {
        const val BLOCK_FRAMES = 32
        private const val FULL_SCALE = 32768f
        private const val CEILING = 0.98f

        const val LEVEL_MAX_DB = 12.0
        private val LEVEL_MAX = db(LEVEL_MAX_DB)
        private val LEVEL_MIN = db(-LEVEL_MAX_DB)
        /** −20 dBFS RMS: roughly where broadcast programme loudness sits. */
        private val TARGET_RMS = db(-20.0).toDouble()
        /** Below −55 dBFS RMS is silence or noise floor: the gain holds. */
        private val GATE_MEAN_SQUARE = db(-55.0).toDouble().pow(2)
        private const val LEVEL_MEASURE_SECS = 3.0
        private const val LEVEL_DOWN_SECS = 1.0
        private const val LEVEL_UP_SECS = 4.0

        const val NIGHT_THRESHOLD_DB = -30.0
        private val NIGHT_THRESHOLD = db(NIGHT_THRESHOLD_DB)
        private const val NIGHT_RATIO = 3f
        private val NIGHT_MAKEUP = db(8.0)
        private const val NIGHT_ATTACK_SECS = 0.005
        private const val NIGHT_RELEASE_SECS = 0.2

        private fun db(v: Double): Float = 10.0.pow(v / 20).toFloat()

        /** Settings → night mode / volume levelling, pushed in by [PlaybackStartup]; read on every buffer. */
        @Volatile var nightMode = false
        @Volatile var levelling = false

        /** Whether ExoPlayer may bitstream: the N8 setting, and neither switch on (a processor cannot
         *  touch an encoded stream, so either would silently do nothing). */
        fun passthroughAllowed(setting: Boolean, night: Boolean, levelling: Boolean): Boolean =
            setting && !night && !levelling
    }
}

/** [AudioDynamics] as a Media3 processor. Active on 16-bit PCM only (what the sink converts to); an
 *  encoded (passthrough) stream never reaches a processor, which is why [OwnTVRenderersFactory] stops
 *  passthrough while either switch is on. */
@UnstableApi
class AudioDynamicsProcessor : BaseAudioProcessor() {
    private var dynamics: AudioDynamics? = null
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        dynamics = AudioDynamics(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun onReset() {
        dynamics = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val night = AudioDynamics.nightMode
        val level = AudioDynamics.levelling
        val d = dynamics
        if (d == null || (!night && !level)) {
            out.put(inputBuffer)
        } else {
            val count = bytes / 2
            if (scratch.size < count) scratch = ShortArray(count)
            inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, count)
            inputBuffer.position(inputBuffer.position() + count * 2)
            d.process(scratch, count, night, level)
            out.asShortBuffer().put(scratch, 0, count)
            out.position(count * 2)
        }
        out.flip()
    }
}
