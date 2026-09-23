package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
 *
 * Since v44 stored in `playback_quirks`, like [ForceMpvStore]; the old DataStore file is copied in once.
 */
class VodEngineStore(
    private val context: Context,
    private val dao: tv.own.owntv.core.database.dao.PlaybackQuirkDao,
) {
    // The pre-v44 DataStore keys. Read once, by [copy], and never written again.
    private val mpvKey = stringSetPreferencesKey("mpv_urls")
    private val exoKey = stringSetPreferencesKey("exo_urls")
    private val movedKey = androidx.datastore.preferences.core.booleanPreferencesKey("moved_to_db_v44")

    private val copy = CopyOnce(
        isDone = { context.vodEngineStore.data.first()[movedKey] == true },
        markDone = { context.vodEngineStore.edit { it[movedKey] = true } },
        copy = {
            val prefs = context.vodEngineStore.data.first()
            (prefs[mpvKey] ?: emptySet()).forEach { write(it, ForceMpvStore.MPV) }
            (prefs[exoKey] ?: emptySet()).forEach { write(it, ForceMpvStore.EXO) }
        },
    )

    val mpvUrls: Flow<Set<String>> = pinned(ForceMpvStore.MPV)
    val exoUrls: Flow<Set<String>> = pinned(ForceMpvStore.EXO)

    private fun pinned(pin: String): Flow<Set<String>> = flow {
        copy.ensure()
        emitAll(dao.observePinned(pin, live = false).map { it.toSet() })
    }

    /** A URL-keyed item (no provider id) cannot say whether it is a film or an episode; either is
     *  "not live", which is the only distinction the pins need. */
    private suspend fun write(url: String, pin: String?) =
        dao.setEnginePin(url, sourceIdOfPinKey(url), mediaTypeOfPinKey(url) ?: "MOVIE", pin)

    /** Pin [url] to [engine] (the gear toggle's target), replacing any previous pin for it. */
    suspend fun pin(url: String, engine: VodEnginePin) {
        copy.ensure()
        write(url, engine.name)
    }

    /** Forget every per-item pin, so all movies/episodes follow the "Movies & Series player" setting
     *  again. Live's per-channel compatibility pins ([ForceMpvStore]) are kept. */
    suspend fun clearAll() {
        copy.ensure()
        dao.clearVodPins()
    }

    /** Migrate-on-read: an existing pin found under the legacy URL key moves to [stableKey],
     *  preserving which engine it was pinned to. */
    suspend fun migrateKey(legacyUrl: String, stableKey: String) {
        copy.ensure()
        dao.migrateKey(legacyUrl, stableKey, sourceIdOfPinKey(stableKey))
    }

    // --- Backup / restore (optional section; opaque keys, no id remapping needed) ---

    /** Current MPV-pinned URLs, for backup export. */
    suspend fun exportMpvUrls(): Set<String> = mpvUrls.first()

    /** Current EXO-pinned URLs, for backup export. */
    suspend fun exportExoUrls(): Set<String> = exoUrls.first()

    /**
     * Merge restored pins in — see [ForceMpvStore.mergePins]: a URL in both incoming lists (corrupt
     * backup) is dropped, and an incoming pin wins over this device's pin in the other direction.
     */
    suspend fun importUrls(mpvUrls: Collection<String>, exoUrls: Collection<String>) {
        val (mpv, exo) = ForceMpvStore.mergePins(this.mpvUrls.first(), this.exoUrls.first(), mpvUrls, exoUrls)
        mpv.forEach { write(it, ForceMpvStore.MPV) }
        exo.forEach { write(it, ForceMpvStore.EXO) }
    }
}
