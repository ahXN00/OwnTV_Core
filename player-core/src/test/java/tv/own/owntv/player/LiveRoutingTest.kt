package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.player.EnginePreference

/** The order of authority both apps used to hand-copy: DRM → pin → learned refusal → setting. */
class LiveRoutingTest {

    private fun decide(
        setting: EnginePreference = EnginePreference.EXO_FIRST,
        pin: Boolean? = null,
        drm: Boolean = false,
        refuses: Boolean = false,
    ) = LiveRouting.decide(setting, pin, drm, refuses)

    @Test
    fun `the setting decides an unpinned channel`() {
        val exo = decide(EnginePreference.EXO_FIRST)
        assertFalse(exo.onMpv)
        assertEquals(EnginePreference.EXO_FIRST, exo.preference)
        assertEquals("exoplayer (setting)", exo.why)
        val mpv = decide(EnginePreference.MPV_ONLY)
        assertTrue(mpv.onMpv)
        assertEquals(EnginePreference.MPV_ONLY, mpv.preference)
    }

    @Test
    fun `a pin outranks the setting, both ways`() {
        assertTrue(decide(EnginePreference.EXO_FIRST, pin = true).onMpv)
        assertFalse(decide(EnginePreference.MPV_FIRST, pin = false).onMpv)
        assertEquals("exoplayer (pinned)", decide(EnginePreference.MPV_FIRST, pin = false).why)
    }

    @Test
    fun `a pin against an only setting re-opens the handover`() {
        val r = decide(EnginePreference.EXO_ONLY, pin = true)
        assertTrue(r.onMpv)
        assertEquals(EnginePreference.MPV_FIRST, r.preference)
        // A pin that agrees keeps the only mode.
        assertEquals(EnginePreference.MPV_ONLY, decide(EnginePreference.MPV_ONLY, pin = true).preference)
    }

    @Test
    fun `drm outranks the pin and locks the ladder to ExoPlayer`() {
        val r = decide(EnginePreference.MPV_FIRST, pin = true, drm = true)
        assertFalse(r.onMpv)
        assertEquals(EnginePreference.EXO_ONLY, r.preference)
        assertEquals("exoplayer (drm)", r.why)
    }

    @Test
    fun `a learned refusal only counts while both engines are allowed and nothing is pinned`() {
        assertTrue(decide(EnginePreference.EXO_FIRST, refuses = true).onMpv)
        assertEquals("mpv (panel refuses segments)", decide(refuses = true).why)
        assertFalse(decide(EnginePreference.EXO_ONLY, refuses = true).onMpv)
        assertFalse(decide(EnginePreference.EXO_FIRST, pin = false, refuses = true).onMpv)
    }
}
