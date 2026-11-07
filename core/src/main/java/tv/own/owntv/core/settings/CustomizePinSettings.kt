package tv.own.owntv.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
 * The two per-profile PINs kept in the settings file, each with its own job:
 *
 * - the **category lock PIN** ([customizePin]) opens locked categories;
 * - the **Settings lock PIN** ([settingsLockPin]) opens Settings, Backup & Restore and Local sync,
 *   and — when [settingsLockMenus] is on — the long-press changes (hide, move, rename, lock).
 *
 * Neither opens the other's door. Both are stored as salted hashes and travel in backups only
 * sealed with the backup passphrase (BackupManager `customizePins` / `settingsPins`).
 *
 * [SettingsRepository] implements this by delegation, so callers keep using `settings.customizePin(…)`.
 */
interface CustomizePinSettings {
    fun customizePin(profileId: Long): Flow<String?>

    /** null/blank clears the category lock PIN. The Settings lock is not touched. */
    suspend fun setCustomizePin(profileId: Long, pin: String?)

    /** The Settings lock PIN, a salted hash (or a pre-hashing value carried over); null when the lock is off. */
    fun settingsLockPin(profileId: Long): Flow<String?>

    /** A PIN turns the Settings lock on; null/blank turns it off, with its long-press switch. */
    suspend fun setSettingsLockPin(profileId: Long, pin: String?)

    /** Whether the long-press changes also ask for the Settings PIN. Off by default; means nothing while the lock is off. */
    fun settingsLockMenus(profileId: Long): Flow<Boolean>

    suspend fun setSettingsLockMenus(profileId: Long, enabled: Boolean)

    /** Exports all per-profile category lock PINs as { "<profileId>": "<hash>" }. */
    suspend fun exportCustomizePins(): org.json.JSONObject

    /** Restores category lock PINs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>)

    /** Exports all per-profile Settings lock PINs as { "<profileId>": "<hash>" }. */
    suspend fun exportSettingsLockPins(): org.json.JSONObject

    /** Restores Settings lock PINs (turning each lock on) for profile ids in [existingProfileIds]. */
    suspend fun importSettingsLockPins(o: org.json.JSONObject, existingProfileIds: Set<Long>)

    /** Exports every profile's long-press switch as { "<profileId>": true }. */
    suspend fun exportSettingsLockMenus(): org.json.JSONObject

    suspend fun importSettingsLockMenus(o: org.json.JSONObject, existingProfileIds: Set<Long>)

    /** Drops both of [profileId]'s PINs and switches, for a profile being deleted. */
    suspend fun forgetProfilePins(profileId: Long)
}

/**
 * [CustomizePinSettings] over the settings file, under dynamic keys: "customize_pin_<id>" (category
 * lock PIN), "settings_lock_pin_<id>" (Settings lock PIN), "settings_pin_<id>" (the Settings lock's
 * on/off) and "settings_lock_menus_<id>".
 *
 * Before the Settings lock had a PIN of its own, "settings_pin_<id>" on meant "Settings asks for the
 * category PIN". Such a profile keeps that PIN for Settings: until it gets a Settings PIN of its own,
 * [settingsLockPin] reads the category PIN, and the first change to the category PIN copies the old
 * one over first ([adoptLegacy]) — so changing one PIN never changes the other.
 */
internal class CustomizePinStore(private val dataStore: DataStore<Preferences>) : CustomizePinSettings {

    override fun customizePin(profileId: Long): Flow<String?> = dataStore.data.map { prefs ->
        prefs[customizeKey(profileId)]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged()

    override suspend fun setCustomizePin(profileId: Long, pin: String?) {
        dataStore.edit { prefs ->
            adoptLegacy(prefs, profileId)
            if (pin.isNullOrBlank()) prefs.remove(customizeKey(profileId)) else prefs[customizeKey(profileId)] = Pin.hash(pin.trim())
        }
    }

    override fun settingsLockPin(profileId: Long): Flow<String?> = dataStore.data.map { prefs ->
        lockPin(prefs, profileId)
    }.distinctUntilChanged()

    override suspend fun setSettingsLockPin(profileId: Long, pin: String?) {
        dataStore.edit { prefs ->
            if (pin.isNullOrBlank()) {
                prefs.remove(settingsLockPinKey(profileId))
                prefs.remove(settingsOnKey(profileId))
                prefs.remove(menusKey(profileId))
            } else {
                prefs[settingsLockPinKey(profileId)] = Pin.hash(pin.trim())
                prefs[settingsOnKey(profileId)] = true
            }
        }
    }

    override fun settingsLockMenus(profileId: Long): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[menusKey(profileId)] == true
    }.distinctUntilChanged()

    override suspend fun setSettingsLockMenus(profileId: Long, enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) prefs[menusKey(profileId)] = true else prefs.remove(menusKey(profileId))
        }
    }

    override suspend fun exportCustomizePins(): org.json.JSONObject = exportPrefixed(CUSTOMIZE_PREFIX)

    override suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val pin = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                adoptLegacy(prefs, pid)
                prefs[customizeKey(pid)] = normalize(pin)
            }
        }
    }

    override suspend fun exportSettingsLockPins(): org.json.JSONObject {
        val prefs = dataStore.data.first()
        val out = org.json.JSONObject()
        profileIdsWithPrefix(prefs, SETTINGS_ON_PREFIX).forEach { pid ->
            lockPin(prefs, pid)?.let { out.put(pid.toString(), it) }
        }
        return out
    }

    override suspend fun importSettingsLockPins(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
                val pin = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                prefs[settingsLockPinKey(pid)] = normalize(pin)
                prefs[settingsOnKey(pid)] = true
            }
        }
    }

    override suspend fun exportSettingsLockMenus(): org.json.JSONObject {
        val prefs = dataStore.data.first()
        val out = org.json.JSONObject()
        profileIdsWithPrefix(prefs, MENUS_PREFIX).forEach { pid -> if (prefs[menusKey(pid)] == true) out.put(pid.toString(), true) }
        return out
    }

    override suspend fun importSettingsLockMenus(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull()?.takeIf { it in existingProfileIds } ?: return@forEach
                if (o.optBoolean(key, false)) prefs[menusKey(pid)] = true else prefs.remove(menusKey(pid))
            }
        }
    }

    override suspend fun forgetProfilePins(profileId: Long) {
        dataStore.edit { prefs ->
            prefs.remove(customizeKey(profileId))
            prefs.remove(settingsLockPinKey(profileId))
            prefs.remove(settingsOnKey(profileId))
            prefs.remove(menusKey(profileId))
        }
    }

    /** The Settings lock's PIN: its own, or — for a lock switched on before it had one — the category PIN. */
    private fun lockPin(prefs: Preferences, profileId: Long): String? {
        if (prefs[settingsOnKey(profileId)] != true) return null
        return prefs[settingsLockPinKey(profileId)]?.takeIf { it.isNotBlank() }
            ?: prefs[customizeKey(profileId)]?.takeIf { it.isNotBlank() }
    }

    /** Gives a Settings lock still reading the category PIN its own copy, before that PIN changes. */
    private fun adoptLegacy(prefs: MutablePreferences, profileId: Long) {
        if (prefs[settingsOnKey(profileId)] != true || prefs[settingsLockPinKey(profileId)] != null) return
        val legacy = prefs[customizeKey(profileId)]?.takeIf { it.isNotBlank() }
        if (legacy != null) prefs[settingsLockPinKey(profileId)] = normalize(legacy) else prefs.remove(settingsOnKey(profileId))
    }

    private suspend fun exportPrefixed(prefix: String): org.json.JSONObject {
        val out = org.json.JSONObject()
        dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String && v.isNotBlank()) out.put(k.name.removePrefix(prefix), v)
        }
        return out
    }

    private fun profileIdsWithPrefix(prefs: Preferences, prefix: String): List<Long> =
        prefs.asMap().keys.mapNotNull { k -> k.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull() }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        return if (Pin.isHashed(trimmed)) trimmed else Pin.hash(trimmed)
    }

    private fun customizeKey(profileId: Long) = stringPreferencesKey("$CUSTOMIZE_PREFIX$profileId")
    private fun settingsLockPinKey(profileId: Long) = stringPreferencesKey("$SETTINGS_LOCK_PIN_PREFIX$profileId")
    private fun settingsOnKey(profileId: Long) = booleanPreferencesKey("$SETTINGS_ON_PREFIX$profileId")
    private fun menusKey(profileId: Long) = booleanPreferencesKey("$MENUS_PREFIX$profileId")

    private companion object {
        const val CUSTOMIZE_PREFIX = "customize_pin_"
        const val SETTINGS_LOCK_PIN_PREFIX = "settings_lock_pin_"
        const val SETTINGS_ON_PREFIX = "settings_pin_"
        const val MENUS_PREFIX = "settings_lock_menus_"
    }
}
