package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.Keys

/** The TV app's one-time Stage defaults (owner, 2026-10-02). */
class StageDefaultsTest {

    @Test
    fun `an update gets the Stage rail, glass and background, and keeps its Movies layout`() {
        val prefs = mutablePreferencesOf(
            Keys.ACTIVE_PROFILE to 1L,
            Keys.NAV_STYLE to "FLOATING", Keys.NAV_SIZE to "WIDE", Keys.NAV_LENGTH to "FULL",
            Keys.GLASS_SCOPE to 0, Keys.GLASS_ALPHA to 80,
            Keys.BG_STYLE to "PICTURE", Keys.BG_LOOK to "DARK", Keys.BG_DIM to 70,
        )
        SettingsRepository.stageDefaults(prefs, allSurfacesBits = 0b111)
        assertEquals("DOCKED", prefs[Keys.NAV_STYLE])
        assertEquals("COMPACT", prefs[Keys.NAV_SIZE])
        assertEquals("FIT", prefs[Keys.NAV_LENGTH])
        assertEquals(0b111, prefs[Keys.GLASS_SCOPE])
        assertEquals(50, prefs[Keys.GLASS_ALPHA])
        assertEquals("STAGE", prefs[Keys.BG_STYLE])
        assertNull(prefs[Keys.BG_LOOK])
        assertNull(prefs[Keys.BG_DIM])
        // No layout stored = the old default, Separate panels, written down so it does not change.
        assertEquals("SEPARATE", prefs[Keys.VOD_LAYOUT])
        assertTrue(prefs[Keys.STAGE_DEFAULTS_APPLIED] == true)
    }

    @Test
    fun `an update with a chosen Movies layout keeps it`() {
        val prefs = mutablePreferencesOf(Keys.ACTIVE_PROFILE to 1L, Keys.VOD_LAYOUT to "CINEMATIC")
        SettingsRepository.stageDefaults(prefs, allSurfacesBits = 1)
        assertEquals("CINEMATIC", prefs[Keys.VOD_LAYOUT])
    }

    @Test
    fun `a fresh install leaves Movies to the Cinematic default`() {
        val prefs = mutablePreferencesOf()
        SettingsRepository.stageDefaults(prefs, allSurfacesBits = 1)
        assertNull(prefs[Keys.VOD_LAYOUT])
        assertEquals("DOCKED", prefs[Keys.NAV_STYLE])
    }
}
