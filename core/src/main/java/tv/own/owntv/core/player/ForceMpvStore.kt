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

private val Context.forceMpvStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_force_mpv")

/**
 * Channels the user has pinned to one engine with the HUD "compatibility mode" toggle, because the other
 * can't play them cleanly — UHD-HEVC macroblocking on some VPUs, a stream only mpv decodes, or a panel
 * whose HLS only ExoPlayer opens. Self-learning: the user flips it once and the channel opens on that
 * engine forever after, whatever the global "Live TV player" setting says.
 *
 * Both directions are stored, because the global setting has both directions. While Live TV was always
 * ExoPlayer-first, "pinned" could only ever mean mpv and one list was enough; once a user can set mpv as
 * the starting engine — or as the *only* engine — the exception they need to record is just as often
 * "this one channel on ExoPlayer". The mpv list keeps its original preference key so pins made by every
 * previous build are read unchanged.
 *
 * Keyed by [enginePinKey] — sourceId + media type + provider remoteId — which is stable across
 * playlist re-syncs for all three source types. Channel rows are REPLACE-upserted on every sync, so a
 * column on the channel (or its Room id) would be wiped on refresh; the provider id is not. Pins made
 * by older builds are keyed by stream URL and still read (see [migrateKey] — P6).
 */
class ForceMpvStore(private val context: Context) {
    private val key = stringSetPreferencesKey("urls")
    private val exoKey = stringSetPreferencesKey("exo_urls")

    val urls: Flow<Set<String>> = context.forceMpvStore.data.map { it[key] ?: emptySet() }
    val exoUrls: Flow<Set<String>> = context.forceMpvStore.data.map { it[exoKey] ?: emptySet() }

    /** Pin [url] to one engine, clearing any pin it had to the other — the two lists are exclusive, and
     *  a channel in both would make the routing depend on which list happened to be read first. */
    suspend fun pin(url: String, onMpv: Boolean) {
        context.forceMpvStore.edit { prefs ->
            val mpv = prefs[key] ?: emptySet()
            val exo = prefs[exoKey] ?: emptySet()
            prefs[key] = if (onMpv) mpv + url else mpv - url
            prefs[exoKey] = if (onMpv) exo - url else exo + url
        }
    }

    /** Drop any pin for [url] in either direction, so the channel follows the global setting again. */
    suspend fun forget(url: String) {
        context.forceMpvStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) - url
            prefs[exoKey] = (prefs[exoKey] ?: emptySet()) - url
        }
    }

    /**
     * The engine a channel is pinned to — true = mpv, false = ExoPlayer, null = not pinned — read the
     * same way by both apps. The phone used to consult only the mpv list, so a channel pinned to
     * ExoPlayer on the television opened on mpv there. A pin found only under the legacy stream URL
     * (older builds) is migrated to [stableKey] on the way.
     */
    suspend fun pinFor(stableKey: String?, legacyUrl: String): Boolean? {
        val prefs = context.forceMpvStore.data.first()
        val lookup = pinOf(stableKey, legacyUrl, prefs[key] ?: emptySet(), prefs[exoKey] ?: emptySet())
            ?: return null
        if (lookup.legacy && stableKey != null) migrateKey(legacyUrl, stableKey)
        return lookup.onMpv
    }

    /** Migrate-on-read: an existing pin found under the legacy URL key moves to [stableKey],
     *  preserving which engine it was pinned to. */
    suspend fun migrateKey(legacyUrl: String, stableKey: String) {
        context.forceMpvStore.edit { prefs ->
            val mpv = prefs[key] ?: emptySet()
            val exo = prefs[exoKey] ?: emptySet()
            if (legacyUrl in mpv) prefs[key] = mpv - legacyUrl + stableKey
            if (legacyUrl in exo) prefs[exoKey] = exo - legacyUrl + stableKey
        }
    }

    // --- Backup / restore (optional section; keyed by stream URL, no id remapping needed) ---

    /** Current mpv-pinned keys, for backup export. */
    suspend fun exportUrls(): Set<String> =
        context.forceMpvStore.data.first()[key] ?: emptySet()

    /** Current ExoPlayer-pinned keys, for backup export. */
    suspend fun exportExoUrls(): Set<String> =
        context.forceMpvStore.data.first()[exoKey] ?: emptySet()

    /**
     * Merge restored pins into the current ones. A key present in both incoming lists is a corrupt
     * backup: it is dropped rather than guessed at, same as the VOD store. An incoming pin wins over
     * this device's pin in the other direction and is removed from that list — a plain union used to
     * leave such a channel in both, routed by whichever list happened to be read first.
     *
     * [exoUrls] is absent from backups written before Live had an ExoPlayer pin; that restores as an
     * empty list, which is exactly right — those users had no ExoPlayer pins to restore.
     */
    suspend fun importUrls(urls: Collection<String>, exoUrls: Collection<String> = emptyList()) {
        context.forceMpvStore.edit { prefs ->
            val (mpv, exo) = mergePins(prefs[key] ?: emptySet(), prefs[exoKey] ?: emptySet(), urls, exoUrls)
            prefs[key] = mpv
            prefs[exoKey] = exo
        }
    }

    /** [pinFor]'s answer, and whether it came from a legacy URL key. */
    data class PinLookup(val onMpv: Boolean, val legacy: Boolean)

    companion object {
        /**
         * A restore's pins merged into this device's: incoming keys found in both incoming lists are
         * dropped (corrupt file), and every other incoming pin wins over this device's pin in the other
         * direction, so no key ever ends up in both lists. Returns (mpv, exo). Shared with [VodEngineStore].
         */
        fun mergePins(
            currentMpv: Set<String>,
            currentExo: Set<String>,
            incomingMpv: Collection<String?>,
            incomingExo: Collection<String?>,
        ): Pair<Set<String>, Set<String>> {
            val mpv = incomingMpv.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            val exo = incomingExo.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.toSet()
            val conflicting = mpv intersect exo
            val mpvClean = mpv - conflicting
            val exoClean = exo - conflicting
            return (currentMpv - exoClean + mpvClean) to (currentExo - mpvClean + exoClean)
        }

        /** Stable key first, then the legacy stream URL; null when the channel is not pinned. */
        fun pinOf(stableKey: String?, legacyUrl: String, mpvPins: Set<String>, exoPins: Set<String>): PinLookup? = when {
            stableKey != null && stableKey in mpvPins -> PinLookup(onMpv = true, legacy = false)
            stableKey != null && stableKey in exoPins -> PinLookup(onMpv = false, legacy = false)
            legacyUrl in mpvPins -> PinLookup(onMpv = true, legacy = true)
            legacyUrl in exoPins -> PinLookup(onMpv = false, legacy = true)
            else -> null
        }
    }
}
