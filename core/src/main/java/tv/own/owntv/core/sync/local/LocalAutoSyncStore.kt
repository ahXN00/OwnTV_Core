package tv.own.owntv.core.sync.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupManager

private val Context.localAutoSyncStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_local_auto_sync")

/** One key per paired device, holding that device's whole answer. */
private const val DEVICE_PREFIX = "device:"

private fun deviceKey(deviceId: String) = stringPreferencesKey("$DEVICE_PREFIX$deviceId")

/**
 * One key per paired device that may sync here automatically. A value rather than a list so
 * granting or revoking one device rewrites one entry.
 */
private const val CONSENT_PREFIX = "consent:"

private fun consentKey(deviceId: String) = stringPreferencesKey("$CONSENT_PREFIX$deviceId")

/** Only the presence of the key matters; the value is never read. */
private const val CONSENT_VALUE = "1"

/**
 * The auto-sync settings, on disk: one record per paired device.
 *
 * Its own file, beside [PairedDeviceStore]'s rather than inside it: a pairing is a credential and
 * a sync schedule is a preference, and one of them must be able to be thrown away without touching
 * the other. It is read against [PairedDeviceStore] all the same, because an answer only means
 * something for a device that is still there — a record left behind by an unpaired device is kept
 * (re-pairing the same device should bring its schedule back) but never reaches the scheduler or a
 * pass.
 *
 * One key per device rather than a list in one value, so saving one device's settings rewrites one
 * record: the device the user is editing cannot roll back the last run of the one next to it.
 *
 * The same file holds **consents**: the paired devices this one has accepted to be synced with
 * automatically. A schedule says "this device syncs with that one"; a consent says "that one may
 * sync with this one". The device that runs a pass holds a schedule; the device that only answers
 * holds a consent. Both are read against the live pairing list, so neither outlives a pairing.
 */
class LocalAutoSyncStore(context: Context, private val paired: PairedDeviceStore) {

    private val store = context.applicationContext.localAutoSyncStore

    /** Every paired device's answer, kept up to date as either side changes. */
    val settings: Flow<LocalAutoSyncSettings> =
        combine(store.data, paired.devices) { prefs, devices ->
            read(prefs).only(devices.mapTo(mutableSetOf()) { it.id })
        }

    /** The answers as they are right now. */
    suspend fun current(): LocalAutoSyncSettings = settings.first()

    /**
     * Writes one device's answer.
     *
     * The last run is not written here: it belongs to the pass, not to the settings, and a save
     * that carried it along would let a settings screen roll the record back to whatever it was
     * before the last sync. Whatever is already recorded for this device stays recorded.
     */
    suspend fun save(deviceId: String, config: LocalAutoSyncConfig) {
        store.edit { prefs ->
            val key = deviceKey(deviceId)
            val recorded = prefs[key]?.let { decode(it) }
            prefs[key] = encode(
                config.copy(
                    lastRunAt = recorded?.lastRunAt ?: 0,
                    lastSuccessAt = recorded?.lastSuccessAt ?: 0,
                ),
            )
        }
    }

    /**
     * Notes that a pass with one device happened.
     *
     * A failed pass still moves [LocalAutoSyncConfig.lastRunAt] forward — it did happen, and the
     * user watching the screen afterwards should not be told it never ran — but it leaves
     * [LocalAutoSyncConfig.lastSuccessAt] alone, because nothing was synced.
     */
    suspend fun recordRun(
        deviceId: String,
        at: Long = System.currentTimeMillis(),
        success: Boolean,
    ) {
        store.edit { prefs ->
            val key = deviceKey(deviceId)
            // Nothing was ever configured for this device, so there is no record to stamp. A
            // device without settings is a device that never auto-syncs, and inventing an entry
            // for it would turn "off" into "off, but with a history".
            val config = prefs[key]?.let { decode(it) } ?: return@edit
            prefs[key] = encode(
                config.copy(
                    lastRunAt = at,
                    lastSuccessAt = if (success) at else config.lastSuccessAt,
                ),
            )
        }
    }

    /**
     * The paired devices this one has accepted for automatic sync.
     *
     * What a consent *means* is decided by the pass that checks it
     * ([tv.own.owntv.core.sync.local.LocalSyncManager.runAutoSync] sends the marker, and the server
     * refuses an unconsented one); this is only where the answer is kept.
     */
    val consents: Flow<Set<String>> =
        combine(store.data, paired.devices) { prefs, devices ->
            val known = devices.mapTo(mutableSetOf()) { it.id }
            prefs.asMap().mapNotNull { (key, value) ->
                val name = key.name
                if (value !is String || !name.startsWith(CONSENT_PREFIX)) return@mapNotNull null
                name.removePrefix(CONSENT_PREFIX).takeIf { it in known }
            }.toSet()
        }

    /** Records that [deviceId] may sync with this device automatically. */
    suspend fun grantConsent(deviceId: String) {
        store.edit { prefs -> prefs[consentKey(deviceId)] = CONSENT_VALUE }
    }

    /** Withdraws [deviceId]'s permission. Its next automatic pass is refused. */
    suspend fun revokeConsent(deviceId: String) {
        store.edit { prefs -> prefs.remove(consentKey(deviceId)) }
    }

    /** The consents as they are right now. */
    suspend fun currentConsents(): Set<String> = consents.first()

    /** True when [deviceId] is still paired and this device has accepted it. */
    suspend fun allows(deviceId: String): Boolean = currentConsents().contains(deviceId)

    private fun read(prefs: Preferences): LocalAutoSyncSettings =
        LocalAutoSyncSettings(
            byDevice = buildMap {
                prefs.asMap().forEach { (key, value) ->
                    val name = key.name
                    if (!name.startsWith(DEVICE_PREFIX) || value !is String) return@forEach
                    val config = decode(value) ?: return@forEach
                    put(name.removePrefix(DEVICE_PREFIX), config)
                }
            },
        )

    private fun encode(config: LocalAutoSyncConfig): String = JSONObject().apply {
        put(FIELD_TIMING, config.timing.name)
        put(FIELD_SECTIONS, JSONArray(config.sections.map { it.name }))
        put(FIELD_PROFILES, JSONArray(config.profileIds.toList()))
        put(FIELD_LAST_RUN, config.lastRunAt)
        put(FIELD_LAST_SUCCESS, config.lastSuccessAt)
    }.toString()

    /**
     * One record back, or null when it cannot be read at all.
     *
     * A field that makes no sense falls back to the same reading an absent key gets — an unknown
     * timing is off, an unknown section is simply not one — rather than dropping the record: losing
     * the user's profiles because one enum was renamed would be worse than losing that one field.
     */
    private fun decode(raw: String): LocalAutoSyncConfig? = runCatching {
        val json = JSONObject(raw)
        LocalAutoSyncConfig(
            timing = json.optString(FIELD_TIMING)
                .let { name -> LocalAutoSyncTiming.entries.firstOrNull { it.name == name } }
                ?: LocalAutoSyncTiming.DEFAULT,
            // An absent list means "never saved", and that means everything. A list that IS there
            // is taken at its word, even when it is empty: "the user ticked nothing" and "the user
            // never said" are different answers, and reading the second one as the first would give
            // a user who unticked everything the opposite of what they asked for.
            sections = json.optJSONArray(FIELD_SECTIONS)?.let { array ->
                (0 until array.length())
                    .mapNotNull { index ->
                        val name = array.optString(index)
                        BackupManager.Section.entries.firstOrNull { it.name == name }
                    }
                    .toSet()
            } ?: BackupManager.Section.entries.toSet(),
            // The same reading as the sections above. A profile deleted since it was ticked simply
            // stops matching anything, which is the right answer — it no longer exists.
            profileIds = json.optJSONArray(FIELD_PROFILES)?.let { array ->
                (0 until array.length())
                    .mapNotNull { index -> (array.opt(index) as? Number)?.toLong() }
                    .toSet()
            } ?: emptySet(),
            lastRunAt = json.optLong(FIELD_LAST_RUN, 0),
            lastSuccessAt = json.optLong(FIELD_LAST_SUCCESS, 0),
        )
    }.getOrNull()

    private companion object {
        const val FIELD_TIMING = "timing"
        const val FIELD_SECTIONS = "sections"
        const val FIELD_PROFILES = "profiles"
        const val FIELD_LAST_RUN = "lastRunAt"
        const val FIELD_LAST_SUCCESS = "lastSuccessAt"
    }
}
