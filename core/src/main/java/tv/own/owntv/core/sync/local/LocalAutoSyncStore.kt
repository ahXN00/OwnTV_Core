package tv.own.owntv.core.sync.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.backup.BackupManager

private val Context.localAutoSyncStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_local_auto_sync")

private val TIMING_KEY = stringPreferencesKey("timing")
private val SECTIONS_KEY = stringPreferencesKey("sections")
private val DIRECTION_KEY = stringPreferencesKey("direction")
private val DEVICES_KEY = stringPreferencesKey("devices")
private val LAST_RUN_KEY = longPreferencesKey("lastRunAt")
private val LAST_SUCCESS_KEY = longPreferencesKey("lastSuccessAt")

/**
 * The auto-sync settings, on disk.
 *
 * Its own file, beside [PairedDeviceStore]'s rather than inside it: a pairing is a credential and
 * a sync schedule is a preference, and one of them must be able to be thrown away without touching
 * the other. DataStore rather than the settings repository for the same reason the pairings are not
 * in the database — this is a handful of values with no query behind them.
 */
class LocalAutoSyncStore(context: Context) {

    private val store = context.applicationContext.localAutoSyncStore

    /** The saved settings, read once and kept up to date. */
    val config: Flow<LocalAutoSyncConfig> = store.data.map { read(it) }

    /** The saved settings as they are right now. */
    suspend fun current(): LocalAutoSyncConfig = read(store.data.first())

    /**
     * Writes the settings.
     *
     * The last run is not written here: it belongs to the pass, not to the settings, and a save
     * that carried it along would let a settings screen roll the record back to whatever it was
     * before the last sync.
     */
    suspend fun save(config: LocalAutoSyncConfig) {
        store.edit { prefs ->
            prefs[TIMING_KEY] = config.timing.name
            prefs[SECTIONS_KEY] = config.sections.joinToString(",") { it.name }
            prefs[DIRECTION_KEY] = config.direction.name
            prefs[DEVICES_KEY] = config.deviceIds.joinToString(",")
        }
    }

    /**
     * Notes that a pass happened.
     *
     * A failed pass still moves [lastRunAt] forward — it did happen, and the user watching the
     * screen afterwards should not be told it never ran — but it leaves [lastSuccessAt] alone,
     * because nothing was synced.
     */
    suspend fun recordRun(at: Long = System.currentTimeMillis(), success: Boolean) {
        store.edit { prefs ->
            prefs[LAST_RUN_KEY] = at
            if (success) prefs[LAST_SUCCESS_KEY] = at
        }
    }

    private fun read(prefs: Preferences): LocalAutoSyncConfig {
        val timing = prefs[TIMING_KEY]
            ?.let { name -> LocalAutoSyncTiming.entries.firstOrNull { it.name == name } }
            ?: LocalAutoSyncTiming.DEFAULT
        // An absent key means "never saved", and that means everything. A key that IS there is
        // taken at its word, even when it is empty: "the user ticked nothing" and "the user never
        // said" are different answers, and reading the second one as the first would give a user
        // who unticked everything the opposite of what they asked for.
        val sections = prefs[SECTIONS_KEY]?.let { raw ->
            raw.split(',')
                .mapNotNull { name -> BackupManager.Section.entries.firstOrNull { it.name == name } }
                .toSet()
        } ?: BackupManager.Section.entries.toSet()
        val direction = prefs[DIRECTION_KEY]
            ?.let { name -> SyncDirection.entries.firstOrNull { it.name == name } }
            ?: SyncDirection.MERGE
        val devices = prefs[DEVICES_KEY]
            ?.let { raw -> raw.split(',').filter { it.isNotBlank() }.toSet() }
            ?: emptySet()
        return LocalAutoSyncConfig(
            timing = timing,
            sections = sections,
            direction = direction,
            deviceIds = devices,
            lastRunAt = prefs[LAST_RUN_KEY] ?: 0,
            lastSuccessAt = prefs[LAST_SUCCESS_KEY] ?: 0,
        )
    }
}
