package tv.own.owntv.player

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.player.PlaybackSession.FocusPolicy
import tv.own.owntv.player.PlaybackSession.FocusReaction

/**
 * What a transient loss of audio focus does to playback. A television never pauses; a phone pauses for
 * a call and keeps playing, quieter, under a notification sound.
 */
class FocusLossTest {

    private val callModes = listOf(
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
    )

    @Test
    fun `a television ducks a transient loss itself and leaves a can-duck loss to the platform`() {
        for (mode in callModes + AudioManager.MODE_NORMAL) {
            assertEquals(FocusReaction.DUCK, FocusPolicy.DUCK.onTransientLoss(canDuck = false, audioMode = mode))
            assertEquals(FocusReaction.NOTHING, FocusPolicy.DUCK.onTransientLoss(canDuck = true, audioMode = mode))
        }
    }

    @Test
    fun `a phone pauses for a transient loss whatever the mode`() {
        for (mode in callModes + AudioManager.MODE_NORMAL) {
            assertEquals(FocusReaction.PAUSE, FocusPolicy.PAUSE.onTransientLoss(canDuck = false, audioMode = mode))
        }
    }

    @Test
    fun `a phone keeps playing under a notification sound`() {
        assertEquals(
            FocusReaction.NOTHING,
            FocusPolicy.PAUSE.onTransientLoss(canDuck = true, audioMode = AudioManager.MODE_NORMAL),
        )
    }

    @Test
    fun `a call that never takes focus still pauses a phone, never a television`() {
        // A VoIP app can ring without requesting focus at all; the mode change is the only signal.
        for (mode in callModes) {
            assertEquals(true, FocusPolicy.PAUSE.pausesForMode(mode))
            assertEquals(false, FocusPolicy.DUCK.pausesForMode(mode))
        }
        assertEquals(false, FocusPolicy.PAUSE.pausesForMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun `a phone pauses for a ringing or connected call even when it is only asked to duck`() {
        for (mode in callModes) {
            assertEquals(FocusReaction.PAUSE, FocusPolicy.PAUSE.onTransientLoss(canDuck = true, audioMode = mode))
        }
    }
}
