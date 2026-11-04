package tv.own.owntv.player

import java.util.Locale

/**
 * Where mpv opens a file, and the seek that is the fallback.
 *
 * A resumed film used to open at 0 and seek after `FILE_LOADED`, to the whole second: a second HTTP request
 * (a reconnect on panels that do not keep the first one), the opening frames decoded for nothing, and a
 * landing up to 999 ms early. mpv's `start` option, set just before `loadfile`, opens it at the position in
 * one step. It is an option every mpv has, set for every load ("none" when there is no position) so one
 * film's start never carries over to the next load — unlike `loadfile … start=`, whose argument layout
 * changed between mpv versions and which the library's JNI would fail silently, loading nothing.
 */
object MpvLoadCommand {

    /** The value for mpv's `start` option. */
    fun startOption(startMs: Long): String = if (startMs > 0) seconds(startMs) else "none"

    /** The fallback when mpv did not take [startOption]. */
    fun seekTo(positionMs: Long): Array<String> = arrayOf("seek", seconds(positionMs), "absolute")

    // ROOT: mpv parses a decimal point; a German device would otherwise write "1,500".
    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
}
