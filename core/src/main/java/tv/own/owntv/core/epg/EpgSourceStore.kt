package tv.own.owntv.core.epg

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
import org.json.JSONArray
import org.json.JSONObject

/** A standalone XMLTV EPG feed (not tied to a playlist). Guide data merges into the shared tables. */
data class EpgSource(
    val id: Long,            // synthetic, always negative so it never collides with a Room source id
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

private val Context.epgStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_epg_sources")

/**
 * Stores the user's EPG (XMLTV) sources as a JSON list in DataStore — NOT a Room table, because the
 * database does a destructive migration on schema changes (which would wipe all synced content). The
 * parsed guide still lands in the existing epg_channels / epg_programmes tables, keyed by each EPG
 * source's negative [EpgSource.id]; the guide query simply includes those ids alongside the playlist
 * ids, so channels match guide entries from any feed by their EPG id.
 */
class EpgSourceStore(private val context: Context) {

    private object Keys {
        val LIST = stringPreferencesKey("sources")
        val NEXT_ID = longPreferencesKey("next_id") // counts down: -1, -2, …
        val MIGRATED = longPreferencesKey("migrated_v1")
    }

    val sources: Flow<List<EpgSource>> = context.epgStore.data.map { prefs ->
        parse(prefs[Keys.LIST])
    }

    suspend fun getAll(): List<EpgSource> = parse(context.epgStore.data.first()[Keys.LIST])

    suspend fun add(name: String, url: String, userAgent: String? = null): EpgSource {
        var created: EpgSource? = null
        context.epgStore.edit { prefs ->
            val id = (prefs[Keys.NEXT_ID] ?: -1L)
            prefs[Keys.NEXT_ID] = id - 1
            val source = EpgSource(id = id, name = name.trim().ifBlank { "EPG" }, url = url.trim(), userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() })
            created = source
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]) + source)
        }
        return created!!
    }

    /**
     * Make sure [url] exists as an EPG source, adding it if it does not. Returns the existing or new
     * entry, and **never syncs anything** — used to put a Stalker portal's own guide on the Settings →
     * EPG list after the portal's catalog syncs, so the user can press Sync on it like any other feed.
     * Registering it is not downloading it, so EPG stays as opt-in as it has been since v2.2.0.
     */
    suspend fun ensure(name: String, url: String, userAgent: String? = null): EpgSource =
        getAll().firstOrNull { it.url == url } ?: add(name, url, userAgent)

    suspend fun update(source: EpgSource) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).map { if (it.id == source.id) source else it })
        }
    }

    suspend fun remove(id: Long) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).filterNot { it.id == id })
        }
    }

    /** Stamp a source after a sync attempt (success → error null; failure → error message). */
    suspend fun setSynced(id: Long, at: Long, error: String?) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).map { if (it.id == id) it.copy(lastSyncAt = at, lastError = error) else it })
        }
    }

    /**
     * Record a sync failure WITHOUT touching [EpgSource.lastSyncAt]. The staleness-based auto-refresh logic
     * treats `lastSyncAt` as the *last successful* sync, so a failed attempt must leave it unchanged —
     * otherwise a flaky network would falsely reset the threshold and stop retries. Only [EpgSource.lastError]
     * is updated here. Successes still go through [setSynced].
     */
    suspend fun markError(id: Long, message: String?) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).map { if (it.id == id) it.copy(lastError = message) else it })
        }
    }

    /** Has the one-time "playlist EPG → EPG source" migration already run? */
    suspend fun isMigrated(): Boolean = context.epgStore.data.first()[Keys.MIGRATED] == 1L
    suspend fun markMigrated() { context.epgStore.edit { it[Keys.MIGRATED] = 1L } }

    // --- backup / restore (the raw JSON list rides inside the sources section) ---

    suspend fun exportJson(): String = context.epgStore.data.first()[Keys.LIST] ?: "[]"

    suspend fun importJson(raw: String?) {
        context.epgStore.edit { prefs ->
            val list = parse(raw)
            if (list.isEmpty()) prefs.remove(Keys.LIST) else prefs[Keys.LIST] = write(list)
            // Keep the id counter below the lowest restored id so new sources never collide.
            prefs[Keys.NEXT_ID] = (list.minOfOrNull { it.id } ?: 0L).coerceAtMost(0L) - 1
        }
    }

    /**
     * Merge-restore (backup): adds the backup's EPG sources WITHOUT touching existing ones — an
     * incoming source whose URL already exists maps to the existing entry. Returns the id mapping
     * (backup id → device id) so the caller can remap the per-source auto-refresh selections.
     */
    suspend fun mergeJson(raw: String?): Map<Long, Long> {
        val incoming = parse(raw)
        if (incoming.isEmpty()) return emptyMap()
        val idMap = HashMap<Long, Long>()
        context.epgStore.edit { prefs ->
            val existing = parse(prefs[Keys.LIST]).toMutableList()
            var nextId = prefs[Keys.NEXT_ID] ?: -1L
            incoming.forEach { src ->
                val match = existing.firstOrNull { it.url.trim() == src.url.trim() }
                if (match != null) {
                    // Same feed already here: adopt the backup's name and user agent. Matching is by
                    // URL alone, so keeping the device's copy meant a restore silently discarded a
                    // renamed feed and any custom user agent it needed to fetch at all. Sync state
                    // (lastSyncAt/lastError) stays local — it describes THIS device.
                    existing[existing.indexOf(match)] =
                        match.copy(name = src.name, userAgent = src.userAgent ?: match.userAgent)
                    idMap[src.id] = match.id
                } else {
                    val added = src.copy(id = nextId, lastSyncAt = null, lastError = null)
                    nextId -= 1
                    existing += added
                    idMap[src.id] = added.id
                }
            }
            prefs[Keys.LIST] = write(existing)
            prefs[Keys.NEXT_ID] = nextId
        }
        return idMap
    }

    private fun parse(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EpgSource(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    userAgent = o.optString("ua").takeIf { it.isNotEmpty() },
                    lastSyncAt = if (o.has("at")) o.getLong("at") else null,
                    lastError = o.optString("err").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .apply {
                        s.userAgent?.let { put("ua", it) }
                        s.lastSyncAt?.let { put("at", it) }
                        s.lastError?.let { put("err", it) }
                    },
            )
        }
        return arr.toString()
    }
}
