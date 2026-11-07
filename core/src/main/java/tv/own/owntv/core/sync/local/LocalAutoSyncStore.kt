package tv.own.owntv.core.sync.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.BackupManager

private val Context.localAutoSyncStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_local_auto_sync")

/**
 * The automatic-sync agreements this device holds, one per paired device, and what goes with them:
 * a turn-off still to be told to the other side, the other side having turned it off, and the
 * "Keep auto-sync listening" switch. Device-local and never in a backup — an agreement is between two
 * devices, and the switch is about this device's network exposure.
 */
class LocalAutoSyncStore internal constructor(
    private val store: DataStore<Preferences>,
    private val paired: PairedDeviceStore?,
) {
    constructor(context: Context, paired: PairedDeviceStore) : this(context.applicationContext.localAutoSyncStore, paired)

    /** Every agreement with a device that is still paired. */
    val agreements: Flow<Map<String, AutoSyncAgreement>> =
        if (paired == null) store.data.map { read(it) }
        else combine(store.data, paired.devices) { prefs, devices ->
            val known = devices.mapTo(HashSet()) { it.id }
            read(prefs).filterKeys { it in known }
        }

    suspend fun current(): Map<String, AutoSyncAgreement> = agreements.first()

    suspend fun get(deviceId: String): AutoSyncAgreement? = current()[deviceId]

    /**
     * Writes the terms of [deviceId]'s agreement — what this side syncs, whose profiles, how often, and
     * the other side's sections as it last said them. The record of past tries is kept.
     */
    suspend fun saveTerms(
        deviceId: String,
        sections: Set<BackupManager.Section>,
        profileIds: Set<Long>,
        timing: LocalAutoSyncTiming,
        theirSections: Set<BackupManager.Section>? = null,
    ) {
        store.edit { prefs ->
            val before = prefs[agreementKey(deviceId)]?.let(::decode) ?: AutoSyncAgreement(deviceId)
            prefs[agreementKey(deviceId)] = encode(
                before.copy(
                    sections = sections,
                    profileIds = profileIds,
                    timing = timing,
                    theirSections = theirSections ?: before.theirSections,
                ),
            )
            prefs.remove(turnedOffKey(deviceId))
            prefs.remove(noticeKey(deviceId))
        }
    }

    /** The other side's sections, as its last answer said them. */
    suspend fun saveTheirSections(deviceId: String, sections: Set<BackupManager.Section>) {
        update(deviceId) { it.copy(theirSections = sections) }
    }

    /** One try with [deviceId]: only a success moves the last success; a failure counts toward the backoff. */
    suspend fun recordAttempt(deviceId: String, at: Long, success: Boolean) {
        update(deviceId) { it.afterAttempt(at, success) }
    }

    /**
     * Ends the agreement with [deviceId] here. [tellThem]: this side turned it off, so the other side is
     * told at the next contact (its next request is refused meanwhile, so it learns either way).
     */
    suspend fun remove(deviceId: String, tellThem: Boolean) {
        store.edit { prefs ->
            prefs.remove(agreementKey(deviceId))
            if (tellThem) prefs[noticeKey(deviceId)] = true else prefs.remove(noticeKey(deviceId))
        }
    }

    /** The other side turned it off: the agreement goes, and the row says so until a new one. */
    suspend fun turnedOffByThem(deviceId: String, at: Long) {
        store.edit { prefs ->
            prefs.remove(agreementKey(deviceId))
            prefs.remove(noticeKey(deviceId))
            prefs[turnedOffKey(deviceId)] = at
        }
    }

    /** Devices whose own side has turned the agreement off, by when. */
    val turnedOffByThem: Flow<Map<String, Long>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            k.name.takeIf { it.startsWith(TURNED_OFF_PREFIX) }?.removePrefix(TURNED_OFF_PREFIX)?.let { id -> (v as? Long)?.let { id to it } }
        }.toMap()
    }

    /** Devices still to be told that this side turned the agreement off. */
    suspend fun pendingNotices(): Set<String> = store.data.first().asMap().keys
        .mapNotNullTo(HashSet()) { k -> k.name.takeIf { it.startsWith(NOTICE_PREFIX) }?.removePrefix(NOTICE_PREFIX) }

    suspend fun noticeDelivered(deviceId: String) {
        store.edit { it.remove(noticeKey(deviceId)) }
    }

    /** Everything about [deviceId], for a device that is unpaired. */
    suspend fun forget(deviceId: String) {
        store.edit { prefs ->
            prefs.remove(agreementKey(deviceId))
            prefs.remove(noticeKey(deviceId))
            prefs.remove(turnedOffKey(deviceId))
        }
    }

    /** "Keep auto-sync listening": on = whenever OwnTV runs, off = only while Sync mode is on. Off by default. */
    val listenAlways: Flow<Boolean> = store.data.map { it[LISTEN_ALWAYS] == true }

    suspend fun setListenAlways(on: Boolean) {
        store.edit { if (on) it[LISTEN_ALWAYS] = true else it.remove(LISTEN_ALWAYS) }
    }

    private suspend fun update(deviceId: String, change: (AutoSyncAgreement) -> AutoSyncAgreement) {
        store.edit { prefs ->
            // No agreement, nothing to stamp: a try that raced a turn-off must not bring one back.
            val before = prefs[agreementKey(deviceId)]?.let(::decode) ?: return@edit
            prefs[agreementKey(deviceId)] = encode(change(before))
        }
    }

    private fun read(prefs: Preferences): Map<String, AutoSyncAgreement> = buildMap {
        prefs.asMap().forEach { (key, value) ->
            val id = key.name.takeIf { it.startsWith(AGREEMENT_PREFIX) }?.removePrefix(AGREEMENT_PREFIX) ?: return@forEach
            (value as? String)?.let(::decode)?.let { put(id, it) }
        }
    }

    internal companion object {
        const val AGREEMENT_PREFIX = "agreement:"
        const val NOTICE_PREFIX = "notice:"
        const val TURNED_OFF_PREFIX = "turned_off:"
        val LISTEN_ALWAYS = booleanPreferencesKey("auto_sync_listener_always")

        fun agreementKey(id: String) = stringPreferencesKey("$AGREEMENT_PREFIX$id")
        fun noticeKey(id: String) = booleanPreferencesKey("$NOTICE_PREFIX$id")
        fun turnedOffKey(id: String) = longPreferencesKey("$TURNED_OFF_PREFIX$id")

        fun encode(a: AutoSyncAgreement): String = JSONObject()
            .put("device", a.deviceId)
            .put("sections", JSONArray(a.sections.map { it.name }))
            .put("profiles", JSONArray(a.profileIds.toList()))
            .put("timing", a.timing.name)
            .put("theirSections", JSONArray(a.theirSections.map { it.name }))
            .put("lastAttemptAt", a.lastAttemptAt)
            .put("lastSuccessAt", a.lastSuccessAt)
            .put("failures", a.failures)
            .toString()

        /** A record back; a field this build does not know reads as its default rather than dropping the agreement. */
        fun decode(raw: String): AutoSyncAgreement? = runCatching {
            val o = JSONObject(raw)
            AutoSyncAgreement(
                deviceId = o.getString("device"),
                sections = sections(o.optJSONArray("sections")) ?: AutoSyncAgreement.DEFAULT_SECTIONS,
                profileIds = o.optJSONArray("profiles")?.let { a -> (0 until a.length()).mapNotNullTo(HashSet()) { (a.opt(it) as? Number)?.toLong() } }.orEmpty(),
                timing = LocalAutoSyncTiming.parse(o.optString("timing")),
                theirSections = sections(o.optJSONArray("theirSections")).orEmpty(),
                lastAttemptAt = o.optLong("lastAttemptAt"),
                lastSuccessAt = o.optLong("lastSuccessAt"),
                failures = o.optInt("failures"),
            )
        }.getOrNull()

        fun sections(a: JSONArray?): Set<BackupManager.Section>? = a?.let {
            (0 until it.length()).mapNotNullTo(HashSet()) { i -> BackupManager.Section.entries.firstOrNull { s -> s.name == it.optString(i) } }
        }
    }
}
