package tv.own.owntv.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.util.Pin

/**
 * Customize Categories & Items: optional per-profile PIN lock on the screen (so hidden items can't
 * be unhidden by someone else). Exported/imported in backups as a salted SHA-256 hash (see
 * [exportCustomizePins] / [importCustomizePins] → BackupManager `customizePins`), so the PIN value
 * itself never travels in a readable form.
 *
 * The same PIN can also guard Settings ([settingsPinEnabled]); that switch means nothing without a
 * PIN, so clearing the PIN turns it off too.
 *
 * [SettingsRepository] implements this by delegation, so callers keep using `settings.customizePin(…)`.
 */
interface CustomizePinSettings {
    fun customizePin(profileId: Long): Flow<String?>

    /** null/blank clears the lock (and the Settings lock that depends on it). */
    suspend fun setCustomizePin(profileId: Long, pin: String?)

    /** Whether opening Settings asks for this profile's Customize PIN. Off by default. */
    fun settingsPinEnabled(profileId: Long): Flow<Boolean>

    suspend fun setSettingsPinEnabled(profileId: Long, enabled: Boolean)

    /** Exports all per-profile Customize PINs as { "<profileId>": "<pin>" }. */
    suspend fun exportCustomizePins(): org.json.JSONObject

    /** Restores Customize PINs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>)
}

/**
 * [CustomizePinSettings] over the settings file, under dynamic "customize_pin_<id>" and
 * "settings_pin_<id>" keys.
 */
internal class CustomizePinStore(private val dataStore: DataStore<Preferences>) : CustomizePinSettings {

    override fun customizePin(profileId: Long): Flow<String?> = dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("customize_pin_$profileId")]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged()

    override suspend fun setCustomizePin(profileId: Long, pin: String?) {
        dataStore.edit { prefs ->
            val k = stringPreferencesKey("customize_pin_$profileId")
            if (pin.isNullOrBlank()) {
                prefs.remove(k)
                prefs.remove(settingsPinKey(profileId))
            } else {
                prefs[k] = Pin.hash(pin.trim())
            }
        }
    }

    override fun settingsPinEnabled(profileId: Long): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[settingsPinKey(profileId)] == true
    }.distinctUntilChanged()

    override suspend fun setSettingsPinEnabled(profileId: Long, enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) prefs[settingsPinKey(profileId)] = true else prefs.remove(settingsPinKey(profileId))
        }
    }

    private fun settingsPinKey(profileId: Long) = booleanPreferencesKey("settings_pin_$profileId")

    override suspend fun exportCustomizePins(): org.json.JSONObject {
        val prefix = "customize_pin_"
        val out = org.json.JSONObject()
        dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String && v.isNotBlank()) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    override suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val pin = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                prefs[stringPreferencesKey("customize_pin_$pid")] = normalizeCustomizePin(pin)
            }
        }
    }

    private fun normalizeCustomizePin(value: String): String {
        val trimmed = value.trim()
        return if (Pin.isHashed(trimmed)) trimmed else Pin.hash(trimmed)
    }
}
