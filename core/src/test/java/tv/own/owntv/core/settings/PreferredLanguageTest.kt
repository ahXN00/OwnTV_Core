package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** N13: each profile has its own preferred languages; one that never chose follows the shared setting. */
class PreferredLanguageTest {

    private val shared = SettingsRepository.Keys.PREF_AUDIO_LANG
    private val active = SettingsRepository.Keys.ACTIVE_PROFILE

    @Test
    fun `a profile with no choice follows the shared setting`() {
        val prefs = mutablePreferencesOf(active to 2L, shared to "deu")
        assertEquals("deu", profileLang(prefs, PREF_AUDIO_LANG_PREFIX, shared))
    }

    @Test
    fun `the active profile's own choice wins, blank included`() {
        val prefs = mutablePreferencesOf(
            active to 2L,
            shared to "deu",
            stringPreferencesKey("pref_audio_lang_2") to "",
            stringPreferencesKey("pref_audio_lang_3") to "fra",
        )
        assertEquals("", profileLang(prefs, PREF_AUDIO_LANG_PREFIX, shared))
        prefs[active] = 3L
        assertEquals("fra", profileLang(prefs, PREF_AUDIO_LANG_PREFIX, shared))
    }

    @Test
    fun `nothing stored means no preference`() {
        assertEquals("", profileLang(mutablePreferencesOf(), PREF_AUDIO_LANG_PREFIX, shared))
    }

    @Test
    fun `the write key belongs to the active profile`() {
        assertEquals("pref_sub_lang_5", activeProfileLangKey(mutablePreferencesOf(active to 5L), PREF_SUB_LANG_PREFIX).name)
    }
}
