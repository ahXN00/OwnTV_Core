package tv.own.owntv.core.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Put a DASH recording's separate tracks back together into one playable file.
 *
 * DASH keeps video and audio in separate Representations, so a recording of one arrives as two
 * independent streams. Each temp file is a valid fragmented-MP4 stream on its own — an initialisation
 * segment followed by media fragments — but a file of only video and a file of only audio are not a
 * recording anyone can watch. This is the step HLS never needed, because an MPEG-TS segment already
 * carries both.
 *
 * **`MediaExtractor` and `MediaMuxer` only.** Both are plain `android.media` framework classes, so
 * this needs no FFmpeg and no new dependency — which matters, because FFmpeg and libmpv live in
 * `:player-core` and the dependency arrow runs `player-core → core`, never back. Recording lives
 * here, so reaching for them would break the module boundary outright.
 *
 * **No re-encoding.** Samples are copied across untouched: the picture is bit-for-bit what the
 * provider sent, and the cost is one pass over the file rather than a decode and encode.
 */
internal object DashRemux {

    /**
     * Mux [inputs] into [output], returning true only if a playable file came out.
     *
     * Inputs that are missing, empty, or hold nothing an extractor recognises are skipped rather than
     * failing the whole recording: a programme captured with its audio track broken is still worth
     * having as video, and refusing it would throw away everything.
     */
    fun mux(inputs: List<File>, output: File): Boolean {
        val usable = inputs.filter { it.isFile && it.length() > 0L }
        if (usable.isEmpty()) return false

        val sources = mutableListOf<Source>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var maxSampleBytes = DEFAULT_SAMPLE_BYTES
            for (file in usable) {
                val extractor = runCatching {
                    MediaExtractor().apply { setDataSource(file.absolutePath) }
                }.getOrNull() ?: continue
                var used = false
                for (index in 0 until extractor.trackCount) {
                    val format = runCatching { extractor.getTrackFormat(index) }.getOrNull() ?: continue
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith(VIDEO_PREFIX) && !mime.startsWith(AUDIO_PREFIX)) continue
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSampleBytes = maxOf(maxSampleBytes, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    val outputTrack = runCatching { muxer.addTrack(format) }.getOrNull() ?: continue
                    extractor.selectTrack(index)
                    sources += Source(extractor, outputTrack)
                    used = true
                }
                if (!used) extractor.release()
            }
            if (sources.isEmpty()) return false

            muxer.start()
            started = true
            copySamples(sources, muxer, maxSampleBytes.coerceAtMost(MAX_SAMPLE_BYTES))
            return true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "mux failed into ${output.name}: ${e.message}")
            return false
        } finally {
            sources.forEach { runCatching { it.extractor.release() } }
            muxer?.let { m ->
                // stop() throws if nothing was ever written, which is not worth losing the tracks over.
                if (started) runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    /**
     * Copy every sample across, oldest first.
     *
     * **Interleaved by presentation time, not file by file.** Each temp file holds one track, so
     * writing one and then the other would produce a file whose audio arrives in a single block at
     * the end — technically muxed, unplayable in practice. Taking whichever track is furthest behind
     * keeps the two in step, which is what a player expects to find.
     */
    private fun copySamples(sources: List<Source>, muxer: MediaMuxer, sampleBytes: Int) {
        val buffer = ByteBuffer.allocate(sampleBytes)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val next = sources
                .filter { it.extractor.sampleTrackIndex >= 0 }
                .minByOrNull { it.extractor.sampleTime }
                ?: return
            val size = next.extractor.readSampleData(buffer, 0)
            if (size < 0) {
                next.extractor.advance()
                continue
            }
            info.set(
                0,
                size,
                next.extractor.sampleTime.coerceAtLeast(0L),
                if (next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                },
            )
            muxer.writeSampleData(next.outputTrack, buffer, info)
            next.extractor.advance()
        }
    }

    private class Source(val extractor: MediaExtractor, val outputTrack: Int)

    private const val TAG = "DashRemux"
    private const val VIDEO_PREFIX = "video/"
    private const val AUDIO_PREFIX = "audio/"

    /** Enough for a standard-definition keyframe; grown from the track's own declared maximum. */
    private const val DEFAULT_SAMPLE_BYTES = 1 shl 20

    /** A ceiling, so a manifest claiming a nonsensical maximum cannot ask for an unallocatable buffer. */
    private const val MAX_SAMPLE_BYTES = 16 shl 20
}
