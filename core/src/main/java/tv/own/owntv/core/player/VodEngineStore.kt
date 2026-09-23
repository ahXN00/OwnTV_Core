package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vodEngineStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_vod_engine")

/** A movie/episode the user manually pinned to an engine via the player's gear toggle. */
enum class VodEnginePin { MPV, EXO }

/**
 * Movies/episodes the user manually switched to a specific engine with the player's gear toggle —
 * the VOD counterpart of [ForceMpvStore] (Live's "compatibility mode"). Flip an item once and it
 * opens on that engine forever after, regardless of the global "Movies & Series player" setting.
 * Items never toggled keep following the setting.
 *
 * Every pin here is a deliberate user action. The player used to write pins by itself after a decode
 * failure, which silently retired the chosen engine for that item with no way to undo it; it no
 * longer does. Pins those builds already wrote are indistinguishable from manual ones, so
 * [clearAll] (Settings → Video Player) is how a user gets back to a clean slate.
 *
 * Keyed by [enginePinKey] — sourceId + media type + provider remoteId — which survives playlist
 * re-syncs on all three source types (Room ids don't, and a Stalker stream URL is minted fresh per
 * play, so the old URL key never matched there). Pins written by older builds are keyed by stream URL
 * and are still read, then rewritten under the stable key (see [migrateKey] — P6).
 */
class VodEngineStore(private val context: Context) {
    private val mpvKey = stringSetPreferencesKey("mpv_urls")
    private val exoKey = stringSetPreferencesKey("exo_urls")

    val mpvUrls: Flow<Set<String>> = context.vodEngineStore.data.map { it[mpvKey] ?: emptySet() }
    val exoUrls: Flow<Set<String>> = context.vodEngineStore.data.map { it[exoKey] ?: emptySet() }

    /** Pin [url] to [engine] (the gear toggle's target), replacing any previous pin for it. */
    suspend fun pin(url: String, engine: VodEnginePin) {
        context.vodEngineStore.edit { prefs ->
            val mpv = prefs[mpvKey] ?: emptySet()
            val exo = prefs[exoKey] ?: emptySet()
            prefs[mpvKey] = if (engine == VodEnginePin.MPV) mpv + url else mpv - url
            prefs[exoKey] = if (engine == VodEnginePin.EXO) exo + url else exo - url
        }
    }

    /** Forget every per-item pin, so all movies/episodes follow the "Movies & Series player" setting
     *  again. Live's per-channel compatibility pins ([ForceMpvStore]) are a separate list. */
    suspend fun clearAll() {
        context.vodEngineStore.edit { prefs ->
            prefs[mpvKey] = emptySet()
            prefs[exoKey] = emptySet()
        }
    }

    /** Migrate-on-read: an existing pin found under the legacy URL key moves to [stableKey],
     *  preserving which engine it was pinned to. */
    suspend fun migrateKey(legacyUrl: String, stableKey: String) {
        context.vodEngineStore.edit { prefs ->
            val mpv = prefs[mpvKey] ?: emptySet()
            val exo = prefs[exoKey] ?: emptySet()
            if (legacyUrl in mpv) prefs[mpvKey] = mpv - legacyUrl + stableKey
            if (legacyUrl in exo) prefs[exoKey] = exo - legacyUrl + stableKey
        }
    }

    // --- Backup / restore (optional section; opaque keys, no id remapping needed) ---

    /** Current MPV-pinned URLs, for backup export. */
    suspend fun exportMpvUrls(): Set<String> = context.vodEngineStore.data.first()[mpvKey] ?: emptySet()

    /** Current EXO-pinned URLs, for backup export. */
    suspend fun exportExoUrls(): Set<String> = context.vodEngineStore.data.first()[exoKey] ?: emptySet()

    /**
     * Merge restored pins in — see [ForceMpvStore.mergePins]: a URL in both incoming lists (corrupt
     * backup) is dropped, and an incoming pin wins over this device's pin in the other direction.
     */
    suspend fun importUrls(mpvUrls: Collection<String>, exoUrls: Collection<String>) {
        context.vodEngineStore.edit { prefs ->
            val (mpv, exo) = ForceMpvStore.mergePins(prefs[mpvKey] ?: emptySet(), prefs[exoKey] ?: emptySet(), mpvUrls, exoUrls)
            prefs[mpvKey] = mpv
            prefs[exoKey] = exo
        }
    }
}
