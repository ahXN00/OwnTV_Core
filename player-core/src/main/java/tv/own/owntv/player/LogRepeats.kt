package tv.own.owntv.player

/**
 * Collapses a burst of repeated lines into each line once plus a count. mpv can say the same thing on
 * every frame ("MediaCodec stalled", a demuxer warning), and each line kept is a diagnostics write.
 * A repeat need not be back to back: on a stream joined mid-way the decoder's warnings take turns
 * between a few lines, so any line among the burst's last [WINDOW] distinct ones counts as a repeat.
 *
 * One caller thread (mpv's log callback); not thread-safe.
 */
class LogRepeats {
    private val recent = ArrayDeque<String>()
    private var held = 0

    /** The lines to write for [line]: nothing while it repeats the current burst. */
    fun next(line: String): List<String> {
        if (line in recent) {
            held++
            return emptyList()
        }
        val out = flush() + line
        recent.addLast(line)
        if (recent.size > WINDOW) recent.removeFirst()
        return out
    }

    /** The count of repeats still held back, if any; it also ends the burst. */
    private fun flush(): List<String> {
        if (held == 0) return emptyList()
        val out = listOf("(repeated ×$held more)")
        held = 0
        recent.clear()
        return out
    }

    private companion object {
        const val WINDOW = 4
    }
}
