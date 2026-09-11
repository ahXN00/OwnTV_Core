package tv.own.owntv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentHashProjection

/** A channel plus its category name, for richer global-search results ("category · #number"). */
data class ChannelSearchResult(
    @Embedded val channel: ChannelEntity,
    val categoryName: String?,
)

/** A channel plus when it was last watched — for the Home screen's recent/continue rows. */
data class ChannelWithWatchedAt(
    @Embedded val channel: ChannelEntity,
    val watchedAt: Long,
)

/**
 * Live TV channels. Big lists are exposed as [PagingSource]; totals come from indexed COUNT queries
 * (per the plan's count requirements). FTS search joins via `channels_fts.rowid = channels.id`.
 * Favorites/history join the profile-scoped user-data tables.
 */
@Dao
interface ChannelDao {
    /** Batch insert; the sync layer calls this in chunks (~500) inside a transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Update
    suspend fun updateAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    /** One-time cleanup of pre-stable-key M3U rows (remoteId was always NULL under clear-then-insert). */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND remoteId IS NULL")
    suspend fun deleteNullRemoteIds(sourceId: Long)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    // --- Stable-key lookups (Backup & Restore resolution: content ids change on re-sync) ---
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND remoteId = :remoteId LIMIT 1")
    suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND name = :name LIMIT 1")
    suspend fun findByName(sourceId: Long, name: String): ChannelEntity?

    /** Channels that carry an EPG id (so the guide grid only lists channels that can have a schedule). */
    @Query(
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND epgChannelId IS NOT NULL AND epgChannelId != '' " +
            "ORDER BY number ASC, name ASC LIMIT :limit",
    )
    suspend fun channelsForGuide(sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    /** Every channel for these sources (incl. those with no/blank epg id) — drives bulk EPG auto-matching. */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) ORDER BY sourceId ASC, sortOrder ASC, name ASC LIMIT :limit")
    suspend fun allForSources(sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    /** Distinct normalised (lower+trim) tvg-ids across ALL channels — the set of EPG ids the user's
     *  channels actually reference, used to filter the bulk EPG sync down from the whole feed. */
    @Query("SELECT DISTINCT LOWER(TRIM(epgChannelId)) FROM channels WHERE epgChannelId IS NOT NULL AND epgChannelId != ''")
    suspend fun allEpgChannelIds(): List<String>

    /**
     * Normalised tvg-ids of this source's channels that currently have **no** guide data at all (S9).
     *
     * A guide sync filters the feed to the channels the user owns, and that set is snapshotted once
     * when it starts — so a catalog sync running at the same time (or any later one that adds
     * channels) leaves those channels' programmes permanently missing until the user re-syncs EPG by
     * hand. This finds exactly the channels in that hole, so they can be topped up from the cached
     * feed without another download.
     */
    @Query(
        "SELECT DISTINCT LOWER(TRIM(epgChannelId)) FROM channels " +
            "WHERE sourceId = :sourceId AND epgChannelId IS NOT NULL AND TRIM(epgChannelId) != '' " +
            "AND LOWER(TRIM(epgChannelId)) NOT IN (SELECT DISTINCT epgChannelId FROM epg_programmes)",
    )
    suspend fun epgChannelIdsWithoutProgrammes(sourceId: Long): List<String>

    /** Largest archive window (days) across these sources' catch-up channels — 0 if none have catch-up.
     *  Drives how far back the Guide extends so archived programmes are visible. */
    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1")
    suspend fun maxCatchupDays(sourceIds: List<Long>): Int

    /** How many channels advertise catch-up/archive — surfaced in the Guide so the user knows their
     *  provider supports it. */
    @Query("SELECT COUNT(*) FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1")
    suspend fun countCatchup(sourceIds: List<Long>): Int

    /**
     * Channels that actually HAVE programmes in the window — so the guide never wastes its row limit
     * on channels without data. The id match is case/whitespace-insensitive: XMLTV channel ids often
     * differ from the panel's epg_channel_id in case.
     */
    @Query(
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND epgChannelId IS NOT NULL AND epgChannelId != '' " +
            "AND (:query = '' OR name LIKE '%' || :query || '%') " +
            // "channels that have guide data": distinct epgChannelIds present in epg_programmes. The programme
            // table is already pruned to the retained window, so we don't time-filter here — that lets the
            // DISTINCT use the (epgChannelId, startMs) index as a fast skip-scan instead of scanning every
            // windowed row and running LOWER(TRIM) per row (was multi-second on big guides). epg_programmes
            // ids are stored normalized, so no LOWER(TRIM) on that side either.
            "AND LOWER(TRIM(epgChannelId)) IN (" +
            "  SELECT DISTINCT epgChannelId FROM epg_programmes WHERE sourceId IN (:sourceIds)" +
            ") ORDER BY number ASC, name ASC LIMIT :limit",
    )
    suspend fun channelsWithGuide(sourceIds: List<Long>, query: String, limit: Int): List<ChannelEntity>

    /** Channels matching these remoteIds (Xtream stream ids) — to resolve smart-matched channels in bulk
     *  without loading the whole channel table. */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND remoteId IN (:remoteIds)")
    suspend fun findByRemoteIds(sourceIds: List<Long>, remoteIds: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun findByRemoteIds(sourceId: Long, remoteIds: List<String>): List<ChannelEntity>

    @Query("SELECT remoteId FROM channels WHERE sourceId = :sourceId AND remoteId IS NOT NULL")
    suspend fun remoteIdsForSource(sourceId: Long): List<String>

    /** Any one stream id from this source — the "Test HLS support" probe needs a channel to ask about,
     *  and an already-synced playlist can supply one without a network round-trip. */
    @Query("SELECT remoteId FROM channels WHERE sourceId = :sourceId AND remoteId IS NOT NULL LIMIT 1")
    suspend fun anyRemoteId(sourceId: Long): String?

    /** Prune scope for the per-category sync fallback — see [MovieDao.remoteIdsInCategories]. */
    @Query("SELECT remoteId FROM channels WHERE sourceId = :sourceId AND categoryId IN (:categoryIds) AND remoteId IS NOT NULL")
    suspend fun remoteIdsInCategories(sourceId: Long, categoryIds: List<Long>): List<String>

    @Query("SELECT remoteId, id, contentHash, sortOrder FROM channels WHERE sourceId = :sourceId AND remoteId IS NOT NULL")
    suspend fun contentHashesForSource(sourceId: Long): List<ContentHashProjection>

    // --- Direct tune (channel-number lookup) ---
    /** All channels whose provider number matches [number] in the given sources. Non-unique because
     *  provider data can contain duplicates; the caller applies visibility and zap-context policy. */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND number = :number ORDER BY sourceId ASC, sortOrder ASC, name ASC, id ASC")
    suspend fun findByNumber(sourceIds: List<Long>, number: Int): List<ChannelEntity>

    /** Bounded window of channels in provider order AFTER [afterSortOrder]/[afterId] within a category.
     *  Combined with [channelsBeforeCategory] to build a neighbourhood around a tuned channel.
     *  Ordered by (sortOrder, id) to match the cursor predicate — avoids mismatch when sortOrder ties. */
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND (sortOrder > :afterSortOrder OR (sortOrder = :afterSortOrder AND id > :afterId)) ORDER BY sortOrder ASC, id ASC LIMIT :limit")
    suspend fun channelsAfterCategory(categoryId: Long, afterSortOrder: Int, afterId: Long, limit: Int): List<ChannelEntity>

    /** Bounded window of channels in provider order BEFORE [beforeSortOrder]/[beforeId] within a category. */
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND (sortOrder < :beforeSortOrder OR (sortOrder = :beforeSortOrder AND id < :beforeId)) ORDER BY sortOrder DESC, id DESC LIMIT :limit")
    suspend fun channelsBeforeCategory(categoryId: Long, beforeSortOrder: Int, beforeId: Long, limit: Int): List<ChannelEntity>

    /** Bounded window of channels in provider order AFTER [afterSortOrder]/[afterId] within a source. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND (sortOrder > :afterSortOrder OR (sortOrder = :afterSortOrder AND id > :afterId)) ORDER BY sortOrder ASC, id ASC LIMIT :limit")
    suspend fun channelsAfterSource(sourceId: Long, afterSortOrder: Int, afterId: Long, limit: Int): List<ChannelEntity>

    /** Bounded window of channels in provider order BEFORE [beforeSortOrder]/[beforeId] within a source. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND (sortOrder < :beforeSortOrder OR (sortOrder = :beforeSortOrder AND id < :beforeId)) ORDER BY sortOrder DESC, id DESC LIMIT :limit")
    suspend fun channelsBeforeSource(sourceId: Long, beforeSortOrder: Int, beforeId: Long, limit: Int): List<ChannelEntity>

    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteByRemoteIds(sourceId: Long, remoteIds: List<String>)

    // --- Browsing (each list has a playlist-order and an A–Z variant; the sort chip picks one) ---
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY sortOrder ASC, name ASC")
    fun pagingByCategory(categoryId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY name ASC")
    fun pagingByCategoryAlpha(categoryId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) ORDER BY name ASC")
    fun pagingAll(sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) ORDER BY sourceId ASC, sortOrder ASC, name ASC")
    fun pagingAllOriginal(sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Catch-up rail: every channel whose provider advertises an archive. A filter over the same
    //     rows as ALL, so it needs no sync work and stays correct after every re-sync. Deliberately
    //     independent of the guide: `catchup` comes from the playlist, so these lists are populated
    //     even for a user with no EPG at all (who is exactly the person looking for them).
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1 ORDER BY name ASC")
    fun pagingCatchup(sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1 ORDER BY sourceId ASC, sortOrder ASC, name ASC")
    fun pagingCatchupOriginal(sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Manual order (Move) — LEFT JOIN content_order; items with a row come first in their saved
    //     position, the rest fall back to provider/playlist order. ---
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.categoryId = :categoryId " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.sortOrder, c.name",
    )
    fun pagingByCategoryManual(categoryId: Long, profileId: Long, contextKey: String): PagingSource<Int, ChannelEntity>

    /** A–Z variant of [pagingByCategoryManual]: manually placed channels keep their saved position,
     *  the rest sort by name instead of falling back to provider order — the same "manual order wins,
     *  the rest goes A–Z" convention the folder list itself uses (see `alphaRest`). */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.categoryId = :categoryId " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.name",
    )
    fun pagingByCategoryManualAlpha(categoryId: Long, profileId: Long, contextKey: String): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE f.profileId = :profileId AND c.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, f.addedAt DESC",
    )
    fun pagingFavoritesManual(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    /** Bounded snapshot of a folder in manual order, for the Move session's in-memory reorder. */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.categoryId = :categoryId " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.sortOrder, c.name LIMIT :limit",
    )
    suspend fun snapshotByCategoryManual(categoryId: Long, profileId: Long, contextKey: String, limit: Int): List<ChannelEntity>

    /** Bounded snapshot across all of a profile's playlists, in provider order — the in-player zap /
     *  channel-list fallback for a channel that has no category of its own. */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) ORDER BY sourceId ASC, sortOrder ASC, name ASC LIMIT :limit")
    suspend fun snapshotAll(sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    /** Bounded snapshot of Favorites in manual order, for the Move session's in-memory reorder. */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE f.profileId = :profileId AND c.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, f.addedAt DESC LIMIT :limit",
    )
    suspend fun snapshotFavoritesManual(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    // --- Counts ---
    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId")
    fun countByCategory(categoryId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId IN (:sourceIds)")
    fun countAll(sourceIds: List<Long>): Flow<Int>

    /** "All Channels" count with hidden categories excluded (matches the filtered ALL list). */
    @Query(
        "SELECT COUNT(*) FROM channels WHERE sourceId IN (:sourceIds) " +
            "AND (categoryId IS NULL OR categoryId NOT IN (:excludedCategoryIds))",
    )
    fun countAllExcluding(sourceIds: List<Long>, excludedCategoryIds: List<Long>): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSourceOnce(sourceId: Long): Int

    /** Observed catch-up channel count — drives the Live TV list count AND whether the Catch-up rail
     *  entry is offered at all (a provider with no archive must not get a permanently empty folder). */
    @Query("SELECT COUNT(*) FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1")
    fun observeCatchupCount(sourceIds: List<Long>): Flow<Int>

    // --- Search (FTS4) ---
    @Query(
        "SELECT * FROM channels WHERE sourceId IN (:sourceIds) " +
            "AND id IN (SELECT rowid FROM channels_fts WHERE channels_fts MATCH :query) ORDER BY name ASC",
    )
    fun search(query: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Inline folder-scoped search (substring LIKE, matches the user's expectation) ---
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchAll(query: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND name LIKE '%' || :query || '%' ORDER BY sortOrder ASC, name ASC")
    fun searchInCategory(query: String, categoryId: Long): PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND catchup = 1 AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchCatchup(query: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    /** Bounded list for global search (across all of a profile's sources). */
    @Query("SELECT * FROM channels WHERE sourceId IN (:sourceIds) AND name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT :limit")
    suspend fun searchList(query: String, sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    /** Global search with the channel's category name joined in — lets results show "category · #number"
     *  so near-identical feeds (e.g. several "ABC") are distinguishable. */
    @Query(
        "SELECT c.*, cat.name AS categoryName FROM channels c " +
            "LEFT JOIN categories cat ON c.categoryId = cat.id " +
            "WHERE c.sourceId IN (:sourceIds) AND c.name LIKE '%' || :query || '%' " +
            "ORDER BY c.name ASC LIMIT :limit",
    )
    suspend fun searchListDetailed(query: String, sourceIds: List<Long>, limit: Int): List<ChannelSearchResult>

    /** FTS-backed variant of [searchListDetailed] for global as-you-type search (see MovieDao.searchListFts). */
    @Query(
        "SELECT c.*, cat.name AS categoryName FROM channels c " +
            "LEFT JOIN categories cat ON c.categoryId = cat.id " +
            "WHERE c.sourceId IN (:sourceIds) " +
            "AND c.id IN (SELECT rowid FROM channels_fts WHERE channels_fts MATCH :ftsQuery) " +
            "ORDER BY c.name ASC LIMIT :limit",
    )
    suspend fun searchListDetailedFts(ftsQuery: String, sourceIds: List<Long>, limit: Int): List<ChannelSearchResult>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "WHERE f.profileId = :profileId AND c.sourceId IN (:sourceIds) AND c.name LIKE '%' || :query || '%' ORDER BY f.addedAt DESC",
    )
    fun searchFavorites(query: String, profileId: Long, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId AND c.sourceId IN (:sourceIds) AND c.name LIKE '%' || :query || '%' ORDER BY h.watchedAt DESC",
    )
    fun searchHistory(query: String, profileId: Long, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    // --- Favorites / History (profile-scoped) ---
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "WHERE f.profileId = :profileId ORDER BY f.addedAt DESC",
    )
    fun pagingFavorites(profileId: Long): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.itemId = c.id AND f.mediaType = 'LIVE' " +
            "WHERE f.profileId = :profileId ORDER BY LOWER(c.name) ASC, c.id ASC",
    )
    fun favoritesListAlpha(profileId: Long): Flow<List<ChannelEntity>>

    // Counts via the same join the list uses, so the badge can't show favorites whose channel id went
    // stale on a re-sync (the old "(2) but the folder is empty" bug) before the relink purges them.
    @Query(
        "SELECT COUNT(*) FROM favorites f INNER JOIN channels c ON c.id = f.itemId " +
            "WHERE f.profileId = :profileId AND f.mediaType = 'LIVE' AND c.sourceId IN (:sourceIds)",
    )
    fun countFavorites(profileId: Long, sourceIds: List<Long>): Flow<Int>

    /** History rail count, joined to channels so it can honor the active-playlist filter. */
    @Query(
        "SELECT COUNT(*) FROM watch_history h INNER JOIN channels c ON c.id = h.itemId " +
            "WHERE h.profileId = :profileId AND h.mediaType = 'LIVE' AND c.sourceId IN (:sourceIds)",
    )
    fun countHistory(profileId: Long, sourceIds: List<Long>): Flow<Int>

    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId AND c.sourceId IN (:sourceIds) ORDER BY h.watchedAt DESC",
    )
    fun pagingHistory(profileId: Long, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    /** Recently-watched row at the top of Live TV. */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId ORDER BY h.watchedAt DESC LIMIT :limit",
    )
    fun recentlyWatched(profileId: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.*, h.watchedAt FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId ORDER BY h.watchedAt DESC LIMIT :limit",
    )
    fun recentlyWatchedWithTimestamp(profileId: Long, limit: Int): Flow<List<ChannelWithWatchedAt>>

    /** Recently-watched live channels, filtered to the active playlist ids before the LIMIT is applied. */
    @Query(
        "SELECT c.*, h.watchedAt FROM channels c " +
            "INNER JOIN watch_history h ON h.itemId = c.id AND h.mediaType = 'LIVE' " +
            "WHERE h.profileId = :profileId AND c.sourceId IN (:sourceIds) " +
            "ORDER BY h.watchedAt DESC LIMIT :limit",
    )
    fun recentlyWatchedWithTimestampFiltered(
        profileId: Long,
        sourceIds: List<Long>,
        limit: Int,
    ): Flow<List<ChannelWithWatchedAt>>
}
