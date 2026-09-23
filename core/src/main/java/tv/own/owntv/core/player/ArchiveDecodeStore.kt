package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.archiveDecodeStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_archive_decode")

/**
 * Panels (`host:port`) whose catch-up archive has been caught needing a **software** video decoder.
 *
 * This is the one runtime quirk worth remembering across app starts. The others in
 * [tv.own.owntv.player.LiveStreamQuirks] are re-learned within seconds of the first play, but this
 * one costs a *silent failed archive open* to learn — audio plays, no picture, and the engine has to
 * time out before it retries in software. Paying that once per app run for a panel whose timeshift
 * server is permanently mid-GOP is the wrong trade; the fault is a property of the provider's
 * archive mux and does not come and go.
 *
 * Only the negative-cost direction is persisted: a host in here opens its archives in software from
 * the start. Nothing ever writes "this host is fine" — an absent host simply tries hardware first.
 * A lesson **expires [TTL_MS] after it was learned** (owner decision 8), so a provider that fixes its
 * server gets hardware decoding back on its own; Settings → "Forget learned stream fixes" ([clear])
 * does it at once. A panel that still needs software is re-learned at the cost of one failed open.
 *
 * Keyed by host, not by [enginePinKey]: there is no catalog row behind an archive URL, and the
 * lesson is panel-wide by construction.
 */
class ArchiveDecodeStore(private val context: Context) {

    private val key = stringSetPreferencesKey("software_archive_hosts")

    /**
     * Every host learned in the last [TTL_MS]. Read once at startup, off the main thread. Expired
     * entries are dropped from the store, and an entry from before expiry existed is dated now.
     */
    suspend fun hosts(nowMs: Long = System.currentTimeMillis()): Set<String> {
        var live: Map<String, Long> = emptyMap()
        context.archiveDecodeStore.edit { prefs ->
            val stored = prefs[key] ?: emptySet()
            live = active(stored, nowMs)
            val rewritten = encode(live)
            if (rewritten != stored) prefs[key] = rewritten
        }
        return live.keys
    }

    suspend fun remember(host: String, nowMs: Long = System.currentTimeMillis()) {
        context.archiveDecodeStore.edit { prefs ->
            prefs[key] = encode(active(prefs[key] ?: emptySet(), nowMs) + (host to nowMs))
        }
    }

    /** Forget every panel — Settings → "Forget learned stream fixes". */
    suspend fun clear() {
        context.archiveDecodeStore.edit { it.remove(key) }
    }

    companion object {
        /** Owner decision 8: fourteen days. */
        const val TTL_MS = 14L * 24 * 60 * 60 * 1000
        private const val SEPARATOR = '|'

        /**
         * The stored entries still in force at [nowMs], host → when learned. An entry written before
         * expiry existed (a bare host) counts as learned now, so an upgrade forgets nothing early.
         */
        fun active(stored: Set<String>, nowMs: Long): Map<String, Long> = stored.mapNotNull { entry ->
            val host = entry.substringBeforeLast(SEPARATOR)
            val at = if (SEPARATOR in entry) entry.substringAfterLast(SEPARATOR).toLongOrNull() else nowMs
            if (host.isBlank() || at == null || nowMs - at >= TTL_MS) null else host to at
        }.toMap()

        fun encode(entries: Map<String, Long>): Set<String> =
            entries.mapTo(linkedSetOf()) { (host, at) -> "$host$SEPARATOR$at" }
    }
}
