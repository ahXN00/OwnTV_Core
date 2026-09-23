package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * A/V sync for the ExoPlayer engines (N1), which have no offset setting of their own.
 *
 * ExoPlayer plays video against the audio sink's clock. [DelayedClockAudioSink] reports that clock
 * [delayMs] ahead, so every video frame is released that much earlier than its sound — which is
 * exactly mpv's positive `audio-delay` ("sound later"). The samples themselves are never touched, so
 * it works the same on bitstreamed (passthrough) Dolby/DTS as on decoded PCM.
 *
 * Changing it mid-play is safe in both directions: a larger value jumps the clock and video drops the
 * frames in between; a smaller one leaves the clock where it is (the audio renderer never lets its
 * position go backwards) until the audio catches up — the picture holds for that long.
 *
 * One instance per engine, written on the main thread and read on the playback thread, and kept across
 * that engine's player rebuilds.
 */
class AudioDelayClock {
    @Volatile var delayMs: Int = 0

    /** [positionUs] as the video should see it; "no position yet" passes through untouched. */
    fun shift(positionUs: Long): Long =
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) positionUs else positionUs + delayMs * 1_000L
}

@UnstableApi
class DelayedClockAudioSink(sink: AudioSink, private val clock: AudioDelayClock) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = clock.shift(super.getCurrentPositionUs(sourceEnded))
}
