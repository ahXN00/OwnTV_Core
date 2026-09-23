package tv.own.owntv.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A restored value the Settings screens could never have written is skipped or brought into range. */
class SettingsImportRulesTest {

    @Test
    fun `a seek step outside the choices is skipped`() {
        assertNull(SettingsImportRules.int("seek_step_sec", 0))
        assertNull(SettingsImportRules.int("seek_step_sec", -10))
        assertEquals(30, SettingsImportRules.int("seek_step_sec", 30))
        assertNull(SettingsImportRules.int("live_rewind_step_sec", 7))
        assertEquals(120, SettingsImportRules.int("live_rewind_step_sec", 120))
    }

    @Test
    fun `audio delay is clamped to the player's range`() {
        assertEquals(5_000, SettingsImportRules.int("audio_delay_ms", 60_000))
        assertEquals(-5_000, SettingsImportRules.int("audio_delay_ms", -60_000))
        assertEquals(250, SettingsImportRules.int("audio_delay_ms", 250))
    }

    @Test
    fun `unknown presets are skipped, known ones kept`() {
        assertNull(SettingsImportRules.string("live_latency_mode", "TURBO"))
        assertEquals("BALANCED", SettingsImportRules.string("live_latency_mode", "BALANCED"))
        assertNull(SettingsImportRules.string("mini_player_position", "MIDDLE"))
    }

    @Test
    fun `subtitle scales must be a real, sane multiplier`() {
        assertNull(SettingsImportRules.float(0.0))
        assertNull(SettingsImportRules.float(-1.0))
        assertNull(SettingsImportRules.float(Double.NaN))
        assertEquals(4.0f, SettingsImportRules.float(50.0))
        assertEquals(1.25f, SettingsImportRules.float(1.25))
    }

    @Test
    fun `keys without a rule pass through`() {
        assertEquals(42, SettingsImportRules.int("ui_zoom_pct", 42))
        assertEquals("dark", SettingsImportRules.string("theme_mode", "dark"))
    }
}
