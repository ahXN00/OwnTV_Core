package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.EpgHashProjection
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgChannelIcon
import tv.own.owntv.core.database.entity.EpgChannelName
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** EPG storage + now/next lookups. Programmes are kept to a rolling window and pruned. */
@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgrammes(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    /** Hash snapshot for one channel — an indexed prefix query on the natural key, loaded lazily per channel. */
    @Query("SELECT id, epgChannelId, startMs, contentHash FROM epg_programmes WHERE sourceId = :sourceId AND epgChannelId = :epgChannelId")
    suspend fun epgHashesForChannel(sourceId: Long, epgChannelId: String): List<EpgHashProjection>

    @Query("DELETE FROM epg_programmes WHERE id IN (:ids)")
    suspend fun deleteProgrammesByIds(ids: List<Long>)

    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId AND epgChannelId IN (:epgChannelIds)")
    suspend fun deleteProgrammesForChannels(sourceId: Long, epgChannelIds: List<String>)

    /** Drop programmes that have already finished, to bound storage. */
    @Query("DELETE FROM epg_programmes WHERE stopMs < :before")
    suspend fun prune(before: Long)

    /** Drop programmes beyond the stored horizon — what lowering "Guide days to keep" frees. */
    @Query("DELETE FROM epg_programmes WHERE startMs > :after")
    suspend fun pruneFuture(after: Long)

    /**
     * Collapse a programme that two feeds both carry, at write time instead of on every read.
     *
     * Guide rows are keyed by `epgChannelId` across every feed, so when two of them cover one channel
     * the same programme is stored twice — the unique key is `(sourceId, epgChannelId, startMs)`, so
     * it cannot prevent it. [tv.own.owntv.core.epg.EpgDedupe] has always hidden that on read; this
     * stops it being stored in the first place.
     *
     * The test and the tie-break are **[tv.own.owntv.core.epg.EpgDedupe]'s, exactly**: same title and
     * overlapping time (both halves — overlapping alone is two feeds disagreeing, and a repeated
     * title without overlap is a real repeat), keeping the longest span so the grid draws no gap, and
     * the lowest id where the spans are equal. If the two ever disagreed, a row would vanish from
     * storage that the read path would have kept.
     *
     * The read-side collapse stays as the safety net: a feed can still be re-synced under a second
     * store id between writes, and that is what makes the gap invisible until this runs again.
     */
    @Query(
        "DELETE FROM epg_programmes WHERE id IN (" +
            "SELECT a.id FROM epg_programmes a JOIN epg_programmes b " +
            "ON a.epgChannelId = b.epgChannelId AND a.id <> b.id AND a.title = b.title " +
            "AND a.startMs < b.stopMs AND b.startMs < a.stopMs " +
            "AND ((b.stopMs - b.startMs) > (a.stopMs - a.startMs) " +
            "OR ((b.stopMs - b.startMs) = (a.stopMs - a.startMs) AND b.id < a.id))" +
            ")",
    )
    suspend fun collapseDuplicateProgrammes(): Int

    /** Rows of one store outside the window a full re-crawl just served — i.e. ones it did not replace.
     *  Used by the Stalker portal guide, where the portal's answer is the whole truth for that store. */
    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId AND (stopMs <= :from OR startMs >= :to)")
    suspend fun pruneOutsideWindow(sourceId: Long, from: Long, to: Long)

    /** Drop all programmes for one EPG channel id (used to re-fill it from cache after a smart-match). */
    @Query("DELETE FROM epg_programmes WHERE epgChannelId = :epgChannelId")
    suspend fun clearChannel(epgChannelId: String)

    /**
     * Drop programmes for one EPG channel id only within the given sources. Used by the cache re-fill so
     * we delete only what we're about to repopulate — a source whose cache isn't fresh keeps its data
     * instead of being wiped and left empty (which would drop the channel out of the guide).
     */
    @Query("DELETE FROM epg_programmes WHERE epgChannelId = :epgChannelId AND sourceId IN (:sourceIds)")
    suspend fun clearChannelForSources(epgChannelId: String, sourceIds: List<Long>)

    /** The programme airing at [now] on a given EPG channel. */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgChannelId AND startMs <= :now AND stopMs > :now ORDER BY startMs DESC LIMIT 1")
    suspend fun nowPlaying(epgChannelId: String, now: Long): EpgProgrammeEntity?

    /** The most recently FINISHED programme on a channel (the "Before" slot in the player overlay). */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgChannelId AND stopMs <= :now ORDER BY stopMs DESC LIMIT 1")
    suspend fun previousProgramme(epgChannelId: String, now: Long): EpgProgrammeEntity?

    /** Now + upcoming programmes for a channel (now/next and a short guide). */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgChannelId AND stopMs > :now ORDER BY startMs ASC LIMIT :limit")
    fun upcoming(epgChannelId: String, now: Long, limit: Int): Flow<List<EpgProgrammeEntity>>

    /** Total guide coverage for a channel, in whole days (latest stop − earliest start). Null when there
     *  is no stored guide for the channel. Used for the "EPG · Nd" hint in the Live preview metadata. */
    @Query("SELECT (MAX(stopMs) - MIN(startMs)) / 86400000 FROM epg_programmes WHERE epgChannelId = :epgChannelId")
    suspend fun coverageDays(epgChannelId: String): Int?

    // --- Guide reads: keyed by epgChannelId ALONE ------------------------------------------------
    //
    // None of the four queries below filters by sourceId, and that is the fix for a bug that outlived
    // two attempts at it. A channel's guide is identified by its `epgChannelId`; which playlist or EPG
    // feed happened to deliver a row is not part of that identity. Filtering on it meant that a row
    // whose source had since been deleted, or re-added and given a new id, became invisible — while
    // `nowPlaying`/`upcoming` above, which never filtered, still found it. So the preview pane showed a
    // full guide and the grid and the channel row beside it stayed blank, permanently, for every
    // affected channel.
    //
    // The list had already been widened once (playlists PLUS EPG feeds) after the same class of report.
    // Widening it a third time would only move the boundary; removing it is what makes all three reads
    // answer identically. Two feeds carrying one channel now both come back, which is what
    // [tv.own.owntv.core.epg.EpgDedupe] collapses.

    // `programmesInWindowPage` used to live here — the keyset-paged read behind GuideReader.window().
    // Both are gone. It could never use an index: keysetting on `id` walks the table in rowid order,
    // so drawing eight rows examined every programme in the database. See the note in GuideReader.

    // Every windowed read below carries `startMs > :from - 86400000` as well as the obvious
    // `stopMs > :from`. Without it there is no lower bound on startMs, so SQLite cannot seek into the
    // (epgChannelId, startMs) index — it walks every row the channel has ever had and tests each one.
    // With it the read becomes a range seek over exactly the span asked for.
    //
    // 86400000 is twenty-four hours, and it is an assumption, stated here so it can be found: a
    // programme that started MORE than a day before the window and is still running would be missed.
    // Nothing in a television schedule runs that long; a feed that claims otherwise is malformed.

    /** One programme's synopsis, loaded on demand for the detail dialog (the grid load drops it). */
    @Query("SELECT description FROM epg_programmes WHERE id = :programmeId LIMIT 1")
    suspend fun programmeDescription(programmeId: Long): String?

    /**
     * One guide row's programmes, loaded lazily when the row scrolls into view. [epgKey] must be the
     * normalized (trim+lowercase) id — programmes are stored normalized, so this hits the
     * (epgChannelId, startMs) index and stays instant even with 100k+ stored programmes.
     */
    @Query("SELECT * FROM epg_programmes WHERE epgChannelId = :epgKey " +
            "AND startMs > :from - 86400000 AND stopMs > :from AND startMs < :to ORDER BY startMs ASC")
    suspend fun programmesForChannel(epgKey: String, from: Long, to: Long): List<EpgProgrammeEntity>

    /** Lightweight version for Guide row rendering; avoids CursorWindow pressure from descriptions. */
    @Query(
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, 0 AS contentHash " +
            "FROM epg_programmes WHERE epgChannelId = :epgKey " +
            "AND startMs > :from - 86400000 AND stopMs > :from AND startMs < :to ORDER BY startMs ASC",
    )
    suspend fun programmeSummariesForChannel(epgKey: String, from: Long, to: Long): List<EpgProgrammeEntity>

    /** Lightweight rows for several Home On Now channels at once. */
    @Query(
        "SELECT id, sourceId, epgChannelId, startMs, stopMs, title, NULL AS description, 0 AS contentHash " +
            "FROM epg_programmes WHERE epgChannelId IN (:epgKeys) " +
            "AND startMs > :from - 86400000 AND stopMs > :from AND startMs < :to ORDER BY epgChannelId ASC, startMs ASC",
    )
    suspend fun programmeSummariesForChannels(epgKeys: List<String>, from: Long, to: Long): List<EpgProgrammeEntity>

    /** How many programmes are stored for these sources (to tell "no guide yet" from "empty window"). */
    @Query("SELECT COUNT(*) FROM epg_programmes WHERE sourceId IN (:sourceIds)")
    suspend fun countForSources(sourceIds: List<Long>): Int

    /** Current/upcoming programmes for one (normalized) EPG channel id — 0 means the feed has nothing
     *  scheduled from now on, so a freshly-matched channel would show an empty guide row. */
    @Query("SELECT COUNT(*) FROM epg_programmes WHERE epgChannelId = :epgChannelId AND stopMs > :now")
    suspend fun countUpcomingForChannel(epgChannelId: String, now: Long): Int


    /** Live programme count for one source — drives the EPG status shown on the source row. */
    @Query("SELECT COUNT(*) FROM epg_programmes WHERE sourceId = :sourceId")
    fun countForSource(sourceId: Long): Flow<Int>

    /** How many distinct channels actually have guide data (for the Guide's status line). */
    @Query("SELECT COUNT(DISTINCT epgChannelId) FROM epg_programmes WHERE sourceId IN (:sourceIds)")
    suspend fun countGuideChannels(sourceIds: List<Long>): Int

    // --- Guide candidates: also keyed by epgChannelId alone -------------------------------------
    //
    // The two queries below are the picker's and auto-match's half of the same decision the block
    // above records, and they are read together by [tv.own.owntv.core.epg.GuideCandidates].
    //
    // Neither filters by sourceId. The guide reads stopped doing so; these did not, which left the
    // grid drawing programmes for channels the picker would not list — reported, correctly, as "EPG
    // matching stopped working". Neither is enough on its own either: `epg_channels` is empty for a
    // feed that carries `<programme>` without `<channel>`, and `epg_programmes` has no display name,
    // so a candidate list needs both.
    //
    // No ORDER BY. The caller ranks by name similarity, which is the order that matters, and an
    // `ORDER BY displayName` here would make [limit] truncate the end of the alphabet — silently
    // losing every candidate from some letter onwards.

    /** Every named guide channel across every feed. Duplicates are expected: two feeds, one channel. */
    @Query("SELECT epgChannelId, displayName, normName, normId FROM epg_channels LIMIT :limit")
    suspend fun guideChannelNames(limit: Int): List<EpgChannelName>

    /** Every guide id that actually has programmes stored — including ids no `<channel>` entry names. */
    @Query("SELECT DISTINCT epgChannelId FROM epg_programmes LIMIT :limit")
    suspend fun guideProgrammeChannelIds(limit: Int): List<String>

    /**
     * Channel `<icon src>` logos from the EPG sources the user enabled "Use this guide's logos" on.
     * Only rows with an icon are returned, so a feed without icons costs nothing. Ids are already
     * stored normalized (trim+lowercase), which is the key the override map is looked up by.
     */
    @Query("SELECT epgChannelId, iconUrl FROM epg_channels WHERE sourceId IN (:sourceIds) AND iconUrl IS NOT NULL AND iconUrl != ''")
    fun observeChannelIcons(sourceIds: List<Long>): Flow<List<EpgChannelIcon>>

    @Query("SELECT epgChannelId FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun epgChannelIdsForSource(sourceId: Long): List<String>

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId AND epgChannelId IN (:epgChannelIds)")
    suspend fun deleteChannelsByEpgIds(sourceId: Long, epgChannelIds: List<String>)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun clearChannelsForSource(sourceId: Long)
}
