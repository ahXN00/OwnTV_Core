package tv.own.owntv.core.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.ContentOrderExportRow
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.CustomCategoryMemberExportRow
import tv.own.owntv.core.database.dao.SeriesSortOrderDao
import tv.own.owntv.core.database.dao.TombstoneDao
import tv.own.owntv.core.database.dao.SeriesSortOrderExportRow
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.UserDataExportRow
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.model.MediaType

/**
 * Records that could not be attached yet, kept on disk so they outlive the process and heal on a later
 * sync. Deliberately NOT in the database: the whole point is that the content tables are being
 * replaced when these are written.
 *
 * `internal` rather than private only so the instrumentation test can empty it between cases. A
 * Preferences DataStore is one instance per file per process, so a test cannot open its own — and
 * without a way to clear it, one test's unresolved records healed against the next test's fresh
 * database and every assertion about "how many favorites are there" was counting someone else's.
 */
internal val Context.pendingStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_pending_userdata")
internal val PENDING_KEY = stringPreferencesKey("entries")

/**
 * Backs up and restores the per-profile user data that lives on volatile content ids: favorites,
 * watch history, and resume positions. Content rows are clear-then-insert on every sync, so ids
 * can't be exported directly — instead each record is exported with a stable identity
 * (sourceId + provider remoteId, falling back to the name) and re-resolved against the content
 * tables AFTER the post-restore sync repopulates them. Unresolvable records stay pending and are
 * retried after every sync (and after a show's episodes load), so they heal as content appears.
 */
class UserDataResolver(
    private val context: Context,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val profileDao: ProfileDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val contentOrderDao: ContentOrderDao,
    private val customCategoryDao: CustomCategoryDao,
    private val seriesSortOrderDao: SeriesSortOrderDao,
    private val tombstoneDao: TombstoneDao,
    private val db: tv.own.owntv.core.database.OwnTVDatabase,
) {

    // --- deletions (v36) -------------------------------------------------------------------------
    //
    // Local sync merges and never clobbers, so an absent row is indistinguishable from one the other
    // device has not heard about yet — and the merge puts it back. These four functions are the other
    // half: a deletion the user meant is remembered as a fact with a timestamp, travels in the sync
    // payload exactly like a favorite does, and beats any older copy of the row on the far side.

    /**
     * The stable content key of a record, with its fields in a fixed order so the same item always
     * produces the same string. That string is the tombstone's identity, and it is compared as text
     * against a unique index — two spellings of the same item would be two different deletions.
     *
     * [record] is a record as [describe] builds it (or as a sync payload carries it, already remapped
     * to this device's source ids). Everything identifying is copied; the bookkeeping fields
     * (`p`/`kind`/`at`/`oid`/`pos`/`dur`) are not, because they say when and by whom, not what.
     */
    fun canonicalIdentity(record: JSONObject): String = JSONObject().apply {
        put("t", record.optString("t"))
        put("src", record.optLong("src", -1))
        record.optStringOrNull("rid")?.let { put("rid", it) }
        record.optStringOrNull("name")?.let { put("name", it) }
        record.optStringOrNull("srid")?.let { put("srid", it) }
        record.optStringOrNull("sname")?.let { put("sname", it) }
        if (record.has("season")) put("season", record.optInt("season"))
        if (record.has("ep")) put("ep", record.optInt("ep"))
        // Membership is per custom category: removing a film from one list is not removing it from
        // another, so the category has to be part of what was deleted.
        record.optStringOrNull("ctx")?.let { put("ctx", it) }
    }.toString()

    /** The stable content key of a live row, or null when its content row is already gone. */
    suspend fun identityOf(type: MediaType, itemId: Long): JSONObject? = describe(type, itemId)

    /**
     * Remembers that [profileId] deleted one row, so the deletion survives a sync.
     *
     * Call it BEFORE the delete, inside the same transaction: [describe] reads the content row the
     * record points at, and after the delete there may be nothing left to describe. A row whose
     * content is already missing records nothing at all — that is an orphan being tidied up, not a
     * choice the user made, and propagating it would delete the item on a device where it is fine.
     */
    suspend fun recordDeletion(
        profileId: Long,
        kind: String,
        type: MediaType,
        itemId: Long,
        contextKey: String? = null,
        at: Long = System.currentTimeMillis(),
    ) {
        val record = describe(type, itemId) ?: return
        contextKey?.let { record.put("ctx", it) }
        tombstoneDao.record(profileId, kind, canonicalIdentity(record), at)
    }

    /** Trims the tombstone table to [MAX_TOMBSTONES]. Called once after a bulk deletion, not per row. */
    suspend fun pruneTombstones() {
        if (tombstoneDao.count() > MAX_TOMBSTONES) tombstoneDao.prune(MAX_TOMBSTONES)
    }

    /** The deletions to put in a sync payload, as records of the same shape [exportAll] produces. */
    suspend fun exportTombstones(kinds: Set<String>, profileIds: Set<Long>? = null): JSONArray {
        val out = JSONArray()
        tombstoneDao.getAllOnce().forEach { row ->
            if (row.kind !in kinds) return@forEach
            if (profileIds != null && row.profileId !in profileIds) return@forEach
            val record = runCatching { JSONObject(row.identity) }.getOrNull() ?: return@forEach
            out.put(record.put("p", row.profileId).put("kind", row.kind).put("at", row.deletedAt))
        }
        return out
    }

    /**
     * Applies deletions that arrived from another device: each one removes the matching local row
     * **only when that row is older than the deletion**, so a favorite re-added after the other
     * device deleted it survives. The tombstone is then recorded here too — both so a third device
     * hears about it, and so the same deletion cannot be undone by an older copy of the row arriving
     * later in the very same payload.
     */
    suspend fun applyTombstones(entries: JSONArray?): Int {
        if (entries == null || entries.length() == 0) return 0
        var applied = 0
        var i = 0
        while (i < entries.length()) {
            val end = minOf(i + RESOLVE_CHUNK, entries.length())
            db.withTransaction {
                for (j in i until end) {
                    val e = entries.getJSONObject(j)
                    if (runCatching { applyTombstone(e) }.getOrDefault(false)) applied++
                }
            }
            i = end
        }
        pruneTombstones()
        return applied
    }

    private suspend fun applyTombstone(e: JSONObject): Boolean {
        val kind = e.optString("kind")
        if (kind !in TOMBSTONE_KINDS) return false
        val pid = e.optLong("p", -1)
        if (pid < 0 || profileDao.getById(pid) == null) return false
        val at = e.optLong("at", 0)
        // Recorded first: the row may not even exist here (nothing to delete), but a third device
        // still has to learn that it was deleted, and an older copy of it may arrive later.
        tombstoneDao.record(pid, kind, canonicalIdentity(e), at)
        val type = runCatching { MediaType.valueOf(e.getString("t")) }.getOrNull() ?: return false
        val itemId = locate(type, e) ?: return false
        val deleted = when (kind) {
            "fav" -> favoriteDao.removeIfOlderThan(pid, type, itemId, at)
            "his" -> historyDao.removeIfOlderThan(pid, type, itemId, at)
            "prog" -> progressDao.removeIfOlderThan(pid, type, itemId, at)
            // Memberships carry no timestamp of their own — the row is a position in a list, not an
            // event — so the deletion simply wins. Re-adding the item locally afterwards writes a
            // fresh row, and the stale tombstone only ever suppresses records older than itself.
            "member" -> {
                customCategoryDao.deleteItem(pid, type, e.optString("ctx"), itemId)
                1
            }
            else -> 0
        }
        return deleted > 0
    }

    /** Exports the chosen kinds ("fav" / "his" / "prog" / "order" / "sort" / "member") as stable-key records for the backup file. */
    suspend fun exportAll(kinds: Set<String> = setOf("fav", "his", "prog", "order", "sort", "member")): JSONArray {
        val out = JSONArray()
        if ("fav" in kinds) favoriteDao.getAllOnce().forEach { f ->
            describe(f.mediaType, f.itemId)?.let { out.put(it.put("p", f.profileId).put("kind", "fav").put("at", f.addedAt)) }
        }
        if ("his" in kinds) historyDao.getAllOnce().forEach { h ->
            describe(h.mediaType, h.itemId)?.let { out.put(it.put("p", h.profileId).put("kind", "his").put("at", h.watchedAt)) }
        }
        if ("prog" in kinds) progressDao.getAllOnce().forEach { pr ->
            describe(pr.mediaType, pr.itemId)?.let {
                out.put(it.put("p", pr.profileId).put("kind", "prog").put("at", pr.updatedAt).put("pos", pr.positionMs).put("dur", pr.durationMs))
            }
        }
        if ("order" in kinds) contentOrderDao.getAllOnce().forEach { o ->
            describe(o.mediaType, o.itemId)?.let {
                out.put(it.put("p", o.profileId).put("kind", "order").put("ctx", o.contextKey).put("pos", o.position))
            }
        }
        if ("member" in kinds) customCategoryDao.getAllOnce().forEach { m ->
            describe(m.mediaType, m.itemId)?.let {
                out.put(it.put("p", m.profileId).put("kind", "member").put("ctx", m.contextKey).put("pos", m.position))
            }
        }
        // Per-series season/episode order. Always MediaType.SERIES, so it re-resolves through the
        // ordinary SERIES branch of [resolveAndInsert].
        if ("sort" in kinds) seriesSortOrderDao.getAllOnce().forEach { o ->
            describe(MediaType.SERIES, o.seriesId)?.let {
                out.put(it.put("p", o.profileId).put("kind", "sort").put("sdesc", o.seasonsDescending).put("edesc", o.episodesDescending))
            }
        }
        return out
    }

    /**
     * Exports only the rows attached to [sourceId], so a single-source re-sync starts promptly.
     *
     * "order" is in the default set (B1): manual Move positions orphan on a resync exactly like
     * favorites do — content is clear-then-insert, so every itemId in `content_order` goes stale —
     * and leaving them out of the snapshot silently threw away the user's hand-arranged folders.
     */
    suspend fun exportForSource(sourceId: Long, kinds: Set<String> = setOf("fav", "his", "prog", "order", "sort", "member")): JSONArray {
        val out = JSONArray()
        if ("fav" in kinds) favoriteDao.exportRowsForSource(sourceId).forEach { row -> row.toJson("fav")?.let { out.put(it) } }
        if ("his" in kinds) historyDao.exportRowsForSource(sourceId).forEach { row -> row.toJson("his")?.let { out.put(it) } }
        if ("prog" in kinds) progressDao.exportRowsForSource(sourceId).forEach { row -> row.toJson("prog")?.let { out.put(it) } }
        if ("order" in kinds) contentOrderDao.exportRowsForSource(sourceId).forEach { row -> row.toJson()?.let { out.put(it) } }
        if ("sort" in kinds) seriesSortOrderDao.exportRowsForSource(sourceId).forEach { row -> row.toJson()?.let { out.put(it) } }
        if ("member" in kinds) customCategoryDao.exportRowsForSource(sourceId).forEach { row -> row.toJson()?.let { out.put(it) } }
        return out
    }

    private fun ContentOrderExportRow.toJson(): JSONObject? {
        val itemName = name ?: return null
        return JSONObject().put("t", mediaType.name).put("src", sourceId).putOpt("rid", remoteId).put("name", itemName)
            .put("p", profileId).put("kind", "order").put("ctx", contextKey).put("pos", position)
            .put("oid", itemId)
    }

    private fun CustomCategoryMemberExportRow.toJson(): JSONObject? {
        val itemName = name ?: return null
        return JSONObject().put("t", mediaType.name).put("src", sourceId).putOpt("rid", remoteId).put("name", itemName)
            .put("p", profileId).put("kind", "member").put("ctx", contextKey).put("pos", position)
            .put("oid", itemId)
    }

    private fun SeriesSortOrderExportRow.toJson(): JSONObject? {
        val itemName = name ?: return null
        return JSONObject().put("t", MediaType.SERIES.name).put("src", sourceId).putOpt("rid", remoteId).put("name", itemName)
            .put("p", profileId).put("kind", "sort").put("sdesc", seasonsDescending).put("edesc", episodesDescending)
            .put("oid", seriesId)
    }

    private fun UserDataExportRow.toJson(kind: String): JSONObject? {
        val json = when (mediaType) {
            MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES -> {
                val itemName = name ?: return null
                JSONObject().put("t", mediaType.name).put("src", sourceId).putOpt("rid", remoteId).put("name", itemName)
            }
            MediaType.EPISODE -> {
                val showName = seriesName ?: return null
                JSONObject().put("t", mediaType.name).put("src", sourceId)
                    .putOpt("srid", seriesRemoteId).put("sname", showName)
                    .putOpt("rid", remoteId).put("season", seasonNumber ?: 0).put("ep", episodeNumber ?: 0)
            }
        }
        json.put("p", profileId).put("kind", kind).put("at", at).put("oid", itemId)
        if (kind == "prog") json.put("pos", positionMs).put("dur", durationMs)
        return json
    }

    /**
     * Heals favorites/history/resume across a source re-sync. Content rows are clear-then-insert, so
     * their ids change every refresh and the user-data rows (keyed on the old ids) orphan — the count
     * badge still showed them, but the join returned nothing. Capture [exportAll] BEFORE the sync (ids
     * still valid → stable keys), then call this AFTER it: re-resolve each record to the new ids, and
     * (only when [purge] is true) drop the now-orphaned rows so counts and lists agree. Keep anything
     * still unresolvable (e.g. not-yet-loaded episodes) pending for a later sync / show-open.
     *
     * [purge] must be false when the sync didn't fully succeed (e.g. it failed partway through a
     * chunked import): the clear-then-insert is deferred per chunk, so a partial import can leave
     * content rows missing that are still valid — purging in that case would permanently delete
     * favorites for content that's simply not re-synced yet, instead of leaving them to heal on the
     * next successful sync.
     */
    suspend fun relinkAfterSync(snapshot: JSONArray, purge: Boolean = true) {
        val unresolved = resolveAllChunked(snapshot)
        // Purge is strictly snapshot-scoped: only rows this snapshot captured (by their old ids) may
        // be dropped, and only when their content row is genuinely gone. An EMPTY snapshot must never
        // purge — the old fallback ran a GLOBAL orphan purge across ALL sources, which could delete
        // another source's favorites while that source's own sync had its content mid-rewrite
        // (M3U clear-then-insert / Xtream stale-prune run concurrently on startup refresh).
        if (purge && snapshot.hasSourceSnapshotIds()) {
            purgeSnapshotOrphans(snapshot)
        }
        if (unresolved.length() > 0) addPending(unresolved)
        resolvePending() // also retries any in-flight backup restore
    }

    private fun JSONArray.hasSourceSnapshotIds(): Boolean =
        length() > 0 && (0 until length()).all { getJSONObject(it).has("oid") }

    private suspend fun purgeSnapshotOrphans(snapshot: JSONArray) = db.withTransaction {
        for (i in 0 until snapshot.length()) {
            val e = snapshot.getJSONObject(i)
            val type = runCatching { MediaType.valueOf(e.getString("t")) }.getOrNull() ?: continue
            val profileId = e.getLong("p")
            val itemId = e.getLong("oid")
            when (e.optString("kind")) {
                "fav" -> favoriteDao.purgeSnapshotOrphan(profileId, type, itemId)
                "his" -> historyDao.purgeSnapshotOrphan(profileId, type, itemId)
                "prog" -> progressDao.purgeSnapshotOrphan(profileId, type, itemId)
                "order" -> contentOrderDao.purgeSnapshotOrphan(profileId, type, itemId)
                "member" -> customCategoryDao.purgeSnapshotOrphan(profileId, type, itemId)
                "sort" -> seriesSortOrderDao.purgeSnapshotOrphan(profileId, itemId)
            }
        }
    }

    /** Appends records to the pending set (de-duplicated by content), so they heal on a later resolve. */
    private suspend fun addPending(extra: JSONArray) {
        context.pendingStore.edit { prefs ->
            val existing = prefs[PENDING_KEY]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            val seen = HashSet<String>()
            for (i in 0 until existing.length()) seen.add(existing.getJSONObject(i).toString())
            for (i in 0 until extra.length()) {
                val s = extra.getJSONObject(i).toString()
                if (seen.add(s)) existing.put(extra.getJSONObject(i))
            }
            prefs[PENDING_KEY] = existing.toString()
        }
    }

    /** Merge-restore (backup): appends the backup's records to the pending set (deduplicated) and
     *  tries resolving — never drops records already pending for profiles not in the backup. */
    suspend fun importAll(entries: JSONArray?) {
        if (entries != null && entries.length() > 0) addPending(entries)
        resolvePending()
    }

    /**
     * Tries to attach pending records to current content rows. Called after every successful source
     * sync and after a show's episodes load; resolved records are inserted (idempotently — the user
     * data tables have unique (profile, type, item) indices) and removed from the pending set.
     */
    suspend fun resolvePending() {
        val raw = context.pendingStore.data.first()[PENDING_KEY] ?: return
        val entries = runCatching { JSONArray(raw) }.getOrNull() ?: return
        if (entries.length() == 0) return

        val remaining = resolveAllChunked(entries)
        context.pendingStore.edit { prefs ->
            if (remaining.length() == 0) prefs.remove(PENDING_KEY) else prefs[PENDING_KEY] = remaining.toString()
        }
    }

    /**
     * Resolves and inserts records in chunked transactions (B3): each record used to be 2–4 lookups
     * plus a single-row insert in its OWN write transaction (one fsync each) — a restore of
     * thousands of favorites/history rows became thousands of fsyncs. Per-record failures are
     * still caught individually (a bad record never aborts its chunk); chunking keeps any single
     * transaction short so sync/UI writers aren't starved. Returns the records that didn't resolve.
     */
    private suspend fun resolveAllChunked(entries: JSONArray): JSONArray {
        val unresolved = JSONArray()
        // Asked once, not once per record: on a device that has never synced — and after every
        // ordinary playlist refresh, which relinks thousands of rows through here — the table is
        // empty, and an extra indexed lookup per record is a cost paid for nothing.
        val tombstonesPresent = tombstoneDao.count() > 0
        var i = 0
        while (i < entries.length()) {
            val end = minOf(i + RESOLVE_CHUNK, entries.length())
            db.withTransaction {
                for (j in i until end) {
                    val e = entries.getJSONObject(j)
                    val ok = runCatching { resolveAndInsert(e, tombstonesPresent) }.getOrDefault(false)
                    if (!ok) unresolved.put(e)
                }
            }
            i = end
        }
        return unresolved
    }

    // --- export side: content row → stable identity ---

    private suspend fun describe(type: MediaType, itemId: Long): JSONObject? = when (type) {
        MediaType.LIVE -> channelDao.getById(itemId)?.let {
            JSONObject().put("t", type.name).put("src", it.sourceId).putOpt("rid", it.remoteId).put("name", it.name)
        }
        MediaType.MOVIE -> movieDao.getById(itemId)?.let {
            JSONObject().put("t", type.name).put("src", it.sourceId).putOpt("rid", it.remoteId).put("name", it.name)
        }
        MediaType.SERIES -> seriesDao.getSeriesById(itemId)?.let {
            JSONObject().put("t", type.name).put("src", it.sourceId).putOpt("rid", it.remoteId).put("name", it.name)
        }
        MediaType.EPISODE -> {
            val ep = seriesDao.getEpisodeById(itemId) ?: return null
            val show = seriesDao.getSeriesById(ep.seriesId) ?: return null
            JSONObject().put("t", type.name).put("src", show.sourceId)
                .putOpt("srid", show.remoteId).put("sname", show.name)
                .putOpt("rid", ep.remoteId).put("season", ep.seasonNumber).put("ep", ep.episodeNumber)
        }
    }

    // --- restore side: stable identity → current content row ---

    /**
     * Would this incoming record add something this device does not have? The dry run's question,
     * answered without writing anything.
     *
     * A record whose content has not been downloaded here yet counts as new: it will be held pending
     * and attach itself when the catalogue arrives, which is a change the user should be told about.
     * A record a local deletion already outranks does not, because applying it would do nothing.
     */
    suspend fun wouldAdd(profileId: Long, kind: String, e: JSONObject): Boolean {
        val at = e.optLong("at", 0)
        if (tombstoneDao.deletedAt(profileId, kind, canonicalIdentity(e))?.let { it >= at } == true) return false
        val type = runCatching { MediaType.valueOf(e.getString("t")) }.getOrNull() ?: return false
        val itemId = locate(type, e) ?: return true
        val ctx = e.optString("ctx")
        return when (kind) {
            "fav" -> !favoriteDao.exists(profileId, type, itemId)
            "his" -> !historyDao.exists(profileId, type, itemId)
            "prog" -> progressDao.get(profileId, type, itemId) == null
            "order" -> !contentOrderDao.exists(profileId, type, ctx, itemId)
            "member" -> !customCategoryDao.exists(profileId, type, ctx, itemId)
            "sort" -> seriesSortOrderDao.findRowId(profileId, itemId) == null
            else -> false
        }
    }

    /** Would this incoming deletion actually remove a row that is here now? */
    suspend fun wouldRemove(profileId: Long, e: JSONObject): Boolean {
        val kind = e.optString("kind")
        if (kind !in TOMBSTONE_KINDS) return false
        val type = runCatching { MediaType.valueOf(e.getString("t")) }.getOrNull() ?: return false
        val itemId = locate(type, e) ?: return false
        val at = e.optLong("at", 0)
        return when (kind) {
            "fav" -> favoriteDao.exists(profileId, type, itemId) && favoriteDao.addedAt(profileId, type, itemId).let { it != null && it <= at }
            "his" -> historyDao.watchedAt(profileId, type, itemId).let { it != null && it <= at }
            "prog" -> progressDao.get(profileId, type, itemId)?.let { it.updatedAt <= at } == true
            "member" -> customCategoryDao.exists(profileId, type, e.optString("ctx"), itemId)
            else -> false
        }
    }

    /** The current local id of the content a record points at, or null while it is not (yet) here. */
    private suspend fun locate(type: MediaType, e: JSONObject): Long? {
        val src = e.getLong("src")
        val rid = e.optStringOrNull("rid")
        return when (type) {
            MediaType.LIVE -> (rid?.let { channelDao.findByRemote(src, it) } ?: channelDao.findByName(src, e.getString("name")))?.id
            MediaType.MOVIE -> (rid?.let { movieDao.findByRemote(src, it) } ?: movieDao.findByName(src, e.getString("name")))?.id
            MediaType.SERIES -> (rid?.let { seriesDao.findSeriesByRemote(src, it) } ?: seriesDao.findSeriesByName(src, e.getString("name")))?.id
            MediaType.EPISODE -> {
                val srid = e.optStringOrNull("srid")
                val show = (srid?.let { seriesDao.findSeriesByRemote(src, it) } ?: seriesDao.findSeriesByName(src, e.getString("sname")))
                    ?: return null
                (rid?.let { seriesDao.findEpisodeByRemote(show.id, it) }
                    ?: seriesDao.findEpisodeByNumber(show.id, e.getInt("season"), e.getInt("ep")))?.id
            }
        }
    }

    private suspend fun resolveAndInsert(e: JSONObject, tombstonesPresent: Boolean): Boolean {
        val type = runCatching { MediaType.valueOf(e.getString("t")) }.getOrNull() ?: return true // drop garbage
        val itemId: Long = locate(type, e) ?: return false

        // The record's own profile or nothing. This used to fall back to whichever profile happened to
        // be first, which is right for "the active profile was deleted, show me something" but wrong
        // here: it dumps a deleted profile's favorites, history and resume positions into another
        // person's account — a Kids profile's content least of all. BackupManager already maps profile
        // ids itself and skips records with no home on this device; this is the same rule one layer
        // down. Returns true ("handled") so such a record is dropped rather than retried for ever
        // against a profile that is never coming back.
        val pid = e.getLong("p")
        if (pid < 0 || profileDao.getById(pid) == null) return true
        val at = e.optLong("at", System.currentTimeMillis())
        // The other half of the merge rule: a record older than a deletion of the same row loses to
        // it. Without this, a merge sync hands back every favorite the user has ever removed, because
        // the far device's copy of the row is perfectly valid — it just predates the removal.
        // Returns true ("handled") so the record is dropped rather than retried for ever.
        if (tombstonesPresent && tombstoneDao.deletedAt(pid, e.optString("kind"), canonicalIdentity(e))?.let { it >= at } == true) {
            return true
        }
        return runCatching {
            when (e.getString("kind")) {
                "fav" -> favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = type, itemId = itemId, addedAt = at))
                // Newest wins, both of them. An incoming record is a fact with a time on it exactly
                // like a deletion is, and the later fact is the true one — so these insert when the
                // row is absent and then move it forward only if the incoming copy is actually newer.
                // A plain REPLACE would let the other device's older state win purely by arriving
                // second, which is the difference between a merge and a coin toss.
                "his" -> {
                    historyDao.insertIfAbsent(WatchHistoryEntity(profileId = pid, mediaType = type, itemId = itemId, watchedAt = at))
                    historyDao.bumpIfNewer(pid, type, itemId, at)
                }
                "prog" -> {
                    val positionMs = e.optLong("pos", 0)
                    val durationMs = e.optLong("dur", 0)
                    progressDao.insertIfAbsent(
                        PlaybackProgressEntity(
                            profileId = pid, mediaType = type, itemId = itemId,
                            positionMs = positionMs, durationMs = durationMs, updatedAt = at,
                        ),
                    )
                    progressDao.updateIfNewer(pid, type, itemId, positionMs, durationMs, at)
                }
                "order" -> contentOrderDao.insertAll(
                    listOf(ContentOrderEntity(profileId = pid, mediaType = type, contextKey = e.getString("ctx"), itemId = itemId, position = e.getInt("pos"))),
                )
                "member" -> customCategoryDao.insertAll(
                    listOf(CustomCategoryMemberEntity(profileId = pid, mediaType = type, contextKey = e.getString("ctx"), itemId = itemId, position = e.getInt("pos"))),
                )
                "sort" -> seriesSortOrderDao.setOrder(
                    profileId = pid, seriesId = itemId,
                    seasonsDescending = e.optBoolean("sdesc", false), episodesDescending = e.optBoolean("edesc", false),
                )
            }
            true
        }.getOrDefault(false)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        /** Records per write transaction in [resolveAllChunked]. */
        private const val RESOLVE_CHUNK = 500

        /** Deletions that travel in a sync payload. Reorder positions are not among them: a position
         *  is overwritten by the newer one, never "missing", so it needs no marker. */
        val TOMBSTONE_KINDS = setOf("fav", "his", "prog", "member")

        /** Newest deletions kept. "Clear watch history" writes one per row, and a deletion is only
         *  useful until every device has seen it. */
        private const val MAX_TOMBSTONES = 20_000
    }
}
