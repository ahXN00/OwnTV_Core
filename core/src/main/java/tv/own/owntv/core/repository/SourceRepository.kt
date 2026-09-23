package tv.own.owntv.core.repository

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncManager
import tv.own.owntv.core.sync.SyncResult

/**
 * Adds/links sources to a profile and runs imports. The setup wizard (Phase 6) and playlist screen
 * (Phase 13) drive this; the actual parsing/inserting lives in [SyncManager].
 */
class SourceRepository(
    private val sourceDao: SourceDao,
    private val syncManager: SyncManager,
    private val userData: tv.own.owntv.core.backup.UserDataResolver,
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
    private val movieDao: tv.own.owntv.core.database.dao.MovieDao,
    private val seriesDao: tv.own.owntv.core.database.dao.SeriesDao,
    private val categoryDao: tv.own.owntv.core.database.dao.CategoryDao,
    private val playbackQuirkDao: tv.own.owntv.core.database.dao.PlaybackQuirkDao,
    private val playbackPrefsDao: tv.own.owntv.core.database.dao.PlaybackPrefsDao,
) {
    fun observeSources(profileId: Long): Flow<List<SourceEntity>> = sourceDao.observeForProfile(profileId)

    suspend fun getById(id: Long): SourceEntity? = sourceDao.getById(id)

    suspend fun addXtreamSource(
        profileId: Long, name: String, serverUrl: String, username: String, password: String,
        userAgent: String? = null, epgUrl: String? = null,
        syncLive: Boolean = true, syncMovies: Boolean = true, syncSeries: Boolean = true,
        preferHls: Boolean = false,
        httpReferer: String? = null,
    ): SourceEntity = addAndLink(
        profileId,
        SourceEntity(
            name = name, type = SourceType.XTREAM, url = serverUrl,
            username = username, password = password, userAgent = userAgent, epgUrl = epgUrl,
            syncLive = syncLive, syncMovies = syncMovies, syncSeries = syncSeries,
            preferHls = preferHls, httpReferer = httpReferer,
        ),
    )

    suspend fun addM3uSource(
        profileId: Long, name: String, url: String, userAgent: String? = null, epgUrl: String? = null,
        httpReferer: String? = null,
    ): SourceEntity = addAndLink(
        profileId,
        SourceEntity(name = name, type = SourceType.M3U, url = url, userAgent = userAgent, epgUrl = epgUrl, httpReferer = httpReferer),
    )

    suspend fun addStalkerSource(
        profileId: Long, name: String, portalUrl: String, mac: String,
        serialNumber: String? = null, deviceId: String? = null, deviceId2: String? = null,
        signature: String? = null, userAgent: String? = null,
        syncLive: Boolean = true, syncMovies: Boolean = true, syncSeries: Boolean = true,
        httpReferer: String? = null,
    ): SourceEntity = addAndLink(
        profileId,
        SourceEntity(
            name = name, type = SourceType.STALKER, url = portalUrl, mac = mac,
            stalkerSerialNumber = serialNumber, stalkerDeviceId = deviceId,
            stalkerDeviceId2 = deviceId2, stalkerSignature = signature, userAgent = userAgent,
            syncLive = syncLive, syncMovies = syncMovies, syncSeries = syncSeries,
            httpReferer = httpReferer,
        ),
    )

    private suspend fun addAndLink(profileId: Long, source: SourceEntity): SourceEntity {
        val id = sourceDao.insert(source)
        sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = id))
        return source.copy(id = id)
    }

    /**
     * Deletes the playlist and everything remembered for its items (owner decision 13): engine pins,
     * sound-only marks, audio delays, and every profile's zoom, volume and track choices. Those rows
     * are keyed by content, not by a foreign key, so without this they outlived the playlist forever.
     */
    suspend fun deleteSource(source: SourceEntity) {
        sourceDao.delete(source)
        runCatching { playbackQuirkDao.deleteBySource(source.id) }
        runCatching { playbackPrefsDao.deleteBySource(source.id) }
    }

    /**
     * Wipe a source's imported content (channels/movies/series + their categories) but KEEP the
     * source row and its credentials. Used when a backgrounded first import fails: deleting the
     * source would make the user's playlist silently vanish, while keeping the partial content
     * would duplicate rows on the next sync (a never-synced source takes the insertFresh path,
     * which assumes empty tables). Content before categories — same order the syncers clear in.
     */
    suspend fun clearSourceContent(sourceId: Long) {
        channelDao.clearSource(sourceId)
        movieDao.clearSource(sourceId)
        seriesDao.clearSource(sourceId) // seasons/episodes cascade
        listOf(tv.own.owntv.core.model.MediaType.LIVE, tv.own.owntv.core.model.MediaType.MOVIE, tv.own.owntv.core.model.MediaType.SERIES)
            .forEach { categoryDao.clear(sourceId, it) }
    }

    suspend fun updateSource(source: SourceEntity) = sourceDao.update(source)

    /**
     * [onProgress] is deliberately **last**: Kotlin binds a trailing lambda to the final parameter,
     * so while `forcePrune` sat there `sync(source) { … }` aimed the progress callback at a Boolean
     * and failed with *"'Boolean' was expected"*, which points at nothing useful. Every existing
     * caller already passed it by name, so moving it broke none of them and the natural spelling now
     * works too. If a parameter is ever added after this one, put it before [onProgress].
     */
    suspend fun sync(
        source: SourceEntity,
        contentTypes: SyncContentTypes = SyncContentTypes(),
        /** User-requested clean resync: allows this run to remove titles the provider no longer lists. */
        forcePrune: Boolean = false,
        onProgress: (ImportStage) -> Unit,
    ): SyncResult {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "sync wrapper start sourceId=${source.id} type=${source.type} contentTypes=$contentTypes")
        // Snapshot favorites/history/resume with stable keys BEFORE the sync clears content (their ids
        // change on every refresh, so they'd otherwise orphan — count badge set, list empty).
        val snapshotStartedAt = SystemClock.elapsedRealtime()
        val snapshot = runCatching { userData.exportForSource(source.id) }
            .onSuccess { Log.i(TAG, "userData export sourceId=${source.id} rows=${it.length()} ${it.typeCounts()} ms=${SystemClock.elapsedRealtime() - snapshotStartedAt}") }
            .onFailure { Log.w(TAG, "userData export failed sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - snapshotStartedAt}", it) }
            .getOrNull()
        val coreStartedAt = SystemClock.elapsedRealtime()
        val (result, _) = syncManager.sync(source, onProgress, contentTypes, forcePrune)
        Log.i(TAG, "core sync sourceId=${source.id} result=${result.name()} ms=${SystemClock.elapsedRealtime() - coreStartedAt}")
        // Always re-attach the snapshot to the new ids — a failed/cancelled sync can still have
        // rewritten some rows (M3U is clear-then-insert; Xtream REPLACE-upserts renumber ids), and
        // without a relink those favorites/history/resume entries turn invisible until a later
        // successful sync. Purging genuinely-gone rows is only allowed after a complete enabled
        // success: a partial content-type sync never touched the other types, and a failure proves
        // nothing. Off sections retain cache + user data, so their favorites stay resolvable.
        val effective = contentTypes.effectiveFor(source)
        val purge = result is SyncResult.Success && effective.isCompleteFor(SyncContentTypes.enabledFor(source))
        val relinkStartedAt = SystemClock.elapsedRealtime()
        // Serialized: parallel source syncs (startup refresh runs every source at once) must not
        // relink/purge against each other's mid-flight state.
        relinkMutex.withLock {
            runCatching { userData.relinkAfterSync(snapshot ?: org.json.JSONArray(), purge = purge) }
                .onSuccess { Log.i(TAG, "userData relink sourceId=${source.id} rows=${snapshot?.length() ?: 0} purge=$purge ms=${SystemClock.elapsedRealtime() - relinkStartedAt}") }
                .onFailure { Log.w(TAG, "userData relink failed sourceId=${source.id} ms=${SystemClock.elapsedRealtime() - relinkStartedAt}", it) }
        }
        // Episodes are fetched lazily on show-open, never by the syncer, so a resync would otherwise
        // leave every already-opened show frozen on the episode list it cached the first time (S8).
        // Zeroing the stamps makes the user's "I'll just resync" actually refresh them, on demand
        // and one show at a time — no episode fetch storm across a 46k-series catalog.
        if (result is SyncResult.Success && effective.series) {
            runCatching { seriesDao.invalidateEpisodeCaches(source.id) }
                .onFailure { Log.w(TAG, "episode cache invalidate failed sourceId=${source.id}", it) }
        }
        Log.i(TAG, "sync wrapper end sourceId=${source.id} result=${result.name()} totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return result
    }

    fun getLastSyncStats(sourceId: Long): tv.own.owntv.core.sync.SyncRunStats? =
        syncManager.getLastSyncStats(sourceId)

    /**
     * Is this source's catalogue whole, or is a lazy add still filling it in? (Plan N1f-4.)
     *
     * Not the same question as "has it synced" — a lazily-added Stalker source is stamped synced
     * while only its first page per category is stored.
     */
    suspend fun catalogueComplete(sourceId: Long): Boolean = syncManager.catalogueComplete(sourceId)

    /** Per-mediaType row counts of a snapshot, e.g. "types={LIVE=8, MOVIE=6}" — upgrade-path diagnostics. */
    private fun org.json.JSONArray.typeCounts(): String {
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until length()) {
            val t = optJSONObject(i)?.optString("t") ?: continue
            counts[t] = (counts[t] ?: 0) + 1
        }
        return "types=$counts"
    }

    private companion object {
        const val TAG = "SourceRepository"
        /** Class-wide: SourceRepository is a Koin singleton, but keep the lock global regardless. */
        val relinkMutex = Mutex()
    }
}

private fun SyncResult.name(): String = when (this) {
    is SyncResult.Success -> "Success"
    is SyncResult.Failed -> "Failed"
    SyncResult.Cancelled -> "Cancelled"
}
