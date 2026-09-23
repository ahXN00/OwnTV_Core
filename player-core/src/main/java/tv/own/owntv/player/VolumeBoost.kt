package tv.own.owntv.player

import androidx.media3.common.C

/**
 * Software volume boost above 100% for the ExoPlayer engines.
 *
 * ExoPlayer's own `volume` is a 0–1 linear gain, so it cannot amplify past unity, and a gain
 * audio-processor broke the audio sink. 100–150% therefore rides on the platform LoudnessEnhancer, at
 * the same gain mpv gives the same number ([VolumeCurve]), so a quiet stream is lifted equally whichever engine owns it
 * instead of stopping dead at 100% the moment playback lands on ExoPlayer.
 *
 * The effect is bound to one audio session id, so [apply] re-attaches after a player rebuild and
 * releases outright at or below 100%: a normal stream never carries an audio effect it doesn't need.
 * Devices whose audio HAL refuses the effect (it is optional) simply stay at unity — the volume
 * readout still moves, nothing crashes.
 *
 * Not thread-safe; call from the player's thread (main, for every engine here).
 */
internal class VolumeBoost(private val onFailure: (String) -> Unit = {}) {

    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var session = C.AUDIO_SESSION_ID_UNSET

    /** Aim the effect at [percent] (0–[MAX_VOLUME]) for [audioSessionId], or drop it when not needed. */
    fun apply(audioSessionId: Int, percent: Int) {
        if (audioSessionId != session) release()
        val gainMb = VolumeCurve.boostMillibels(percent)
        if (gainMb <= 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) { release(); return }
        val fx = loudness ?: runCatching { android.media.audiofx.LoudnessEnhancer(audioSessionId) }
            .onFailure { onFailure("volume boost unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()?.also { loudness = it; session = audioSessionId } ?: return
        runCatching { fx.setTargetGain(gainMb); fx.enabled = true }
            .onFailure { onFailure("volume boost failed: ${it.javaClass.simpleName}") }
    }

    fun release() {
        runCatching { loudness?.release() }
        loudness = null
        session = C.AUDIO_SESSION_ID_UNSET
    }

    companion object {
        /** The HUD ceiling; 150 % ≈ +10 dB on every engine — see [VolumeCurve]. */
        const val MAX_VOLUME = VolumeCurve.MAX_PERCENT
    }
}
