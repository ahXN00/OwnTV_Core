package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.CorePerf
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.EpgDedupe
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StreamUrlResolver

/** Archive window assumed when the playlist doesn't say how many days it keeps. One shared value:
 *  the catch-up picker used to assume 1 day while live rewind assumed 7, so the same channel
 *  offered a day of programmes in the list and a week of rewinding on the timeline. */
const val DEFAULT_CATCHUP_DAYS = 7

/** Upper bound on a playlist's `catchup-days`. Guards the arithmetic that turns it into seconds. */
const val MAX_CATCHUP_DAYS = 31

// How far back the Live TV catch-up picker may look. Must stay within EpgRepository's retention (7
// days) and matches the Guide's own cap, so the same channel offers the same archive from either
// screen — this used to be 48h, left over from when only ~2 days of guide were retained, which meant
// a programme the Guide happily replayed was not even listed in Live TV.
private const val CATCHUP_LOOKBACK_CAP_MS = 7L * 24 * 60 * 60 * 1000

// How far past a finished catch-up programme to look for the next one. Beyond this the guide has a
// gap rather than a next programme, and auto-play stops instead of leaping hours ahead.
private const val NEXT_PROGRAMME_GAP_CAP_MS = 3L * 60 * 60 * 1000

private const val LOG_TAG = "OwnTVHome"

/**
 * How long a resolved now/next stays usable. Long enough that scrolling back to a channel costs
 * nothing, short enough that a programme changing over is noticed.
 */
private const val CACHE_TTL_MS = 5 * 60_000L

/** How many entries the provider's short-EPG endpoint is asked for. Its own practical maximum. */
private const val SHORT_EPG_LIMIT = 8

/**
 * How many stored rows to read for Next/Later. More than the four Later shows, because duplicates
 * from a second feed are collapsed afterwards and would otherwise eat the list.
 */
private const val UPCOMING_LIMIT = 12

/**
 * Every guide read Live TV performs: now/next for the focused channel, the batched "what's on now" for a
 * list of channels, the catch-up programme list, and the "Match EPG" picker.
 *
 * Split out of the TV app's Live view model, which kept none of this state beyond the now/next cache this class now
 * owns. Nothing here touches playback, the engine ladder or the tune state machine — it reads the
 * database (falling back to the provider's short-EPG API) and maps rows. The guide shift is passed in
 * rather than observed, so the caller stays the single place that knows a customization changed.
 */
class LiveEpgReader(
    private val epgDao: EpgDao,
    private val epgSourceStore: EpgSourceStore,
    private val sourceDao: SourceDao,
    private val xtreamClient: XtreamClient,
    private val streamUrlResolver: StreamUrlResolver,
) {
    private data class CachedEpg(val at: Long, val data: EpgNowNext)

    /** Written from the IO dispatcher (the now/next fetch) and cleared from Main (EPG mapping/shift
     *  changes), so it cannot be a plain HashMap — concurrent structural modification of one is
     *  undefined, up to a corrupt table or an infinite loop inside `get`. */
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, CachedEpg>()

    private data class CachedRows(val at: Long, val rows: List<EpgProgrammeEntity>)

    /** Provider-fetched guide rows per channel, so the grid and the preview share one request. */
    private val providerRows = java.util.concurrent.ConcurrentHashMap<Long, CachedRows>()

    /** Drop every cached now/next — the global guide offset moved, so all of them are stale. */
    fun clearCache() {
        cache.clear()
    }

    /** Drop one channel's cached now/next (its EPG match or per-channel shift changed). */
    fun invalidate(channelId: Long) {
        cache.remove(channelId)
    }

    /** Now/next for [ch], cached ~5 min. Prefers a manual EPG match / stored bulk guide, then falls
     *  back to Xtream's short EPG API. */
    suspend fun nowNext(ch: ChannelEntity, cust: SectionCustomizations, globalShiftMinutes: Int): EpgNowNext? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            cache[ch.id]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withContext it.data }

            // 1) Bulk guide via the effective EPG id (manual match overrides the channel's own id).
            val epgKey = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            // Guide shift (global or per-channel): the stored rows keep the feed's own clock, so we look
            // up the SHIFTED "now" and move what comes back — display and catch-up then agree.
            val shift = EpgShift.minutesFor(cust, ch, globalShiftMinutes)
            if (epgKey != null) {
                val at = EpgShift.toStored(now, shift)
                val nowProg = epgDao.nowPlaying(epgKey, at)
                // Collapsed first: two feeds covering one channel list every programme twice, which
                // put the same title in Next AND at the head of Later.
                val future = EpgDedupe.collapse(epgDao.upcoming(epgKey, at, UPCOMING_LIMIT).first())
                    .filter { it.startMs > (nowProg?.startMs ?: 0) }
                val nextProg = future.firstOrNull()
                if (nowProg != null || nextProg != null) {
                    val prevProg = epgDao.previousProgramme(epgKey, nowProg?.startMs ?: at)
                    // Days of stored guide — accurate for bulk-guide channels (the short-EPG API path
                    // leaves this null, since its ~8 entries only span a few hours).
                    val days = runCatching { epgDao.coverageDays(epgKey) }.getOrNull()?.takeIf { it > 0 }
                    fun EpgProgrammeEntity.shifted() = EpgShift.apply(this, shift).toXt()
                    val result = EpgNowNext(nowProg?.shifted(), nextProg?.shifted(), future.drop(1).take(4).map { it.shifted() }, previous = prevProg?.shifted(), coverageDays = days)
                    cache[ch.id] = CachedEpg(now, result)
                    return@withContext result
                }
            }

            // 2) Provider short-EPG API fallback (Xtream get_short_epg / Stalker get_short_epg, Phase E §5.5).
            val entries = providerEntries(ch, shift) ?: return@withContext null
            // A gap in the provider's own guide data around "now" (nothing covers this instant) must leave
            // current null — picking the next entry that simply hasn't ended yet would mislabel an upcoming
            // programme as live (issue #68). "Next"/"Later" are computed independently below, so a genuine
            // gap correctly shows no "Now" while the upcoming programme still appears as "Next".
            val current = entries.firstOrNull { it.startMs <= now && it.stopMs > now }
            val future = entries.filter { it.startMs > (current?.startMs ?: now) }.sortedBy { it.startMs }
            // Short-EPG responses sometimes include the just-finished programme — surface it as "Before".
            val previous = entries.filter { it.stopMs <= (current?.startMs ?: now) }.maxByOrNull { it.stopMs }
            val result = EpgNowNext(current, future.firstOrNull(), future.drop(1).take(4), previous = previous)
            cache[ch.id] = CachedEpg(now, result)
            result
        }

    /**
     * The channel's guide as the **provider** reports it right now, on the user's clock.
     *
     * This is the second of the two guides OwnTV reads, and the reason the preview pane could show a
     * programme while the grid and the channel row beside it stayed blank: these entries are fetched
     * live and are never written to `epg_programmes`, so anything reading only the table sees nothing.
     * Pulled out of [nowNext] so the guide grid and the channel rows can read exactly the same answer.
     *
     * Null — as distinct from empty — means there is no provider guide to ask for at all: no stream
     * id, no source, or a source type with no short-EPG endpoint. Callers use that to tell "the
     * provider says nothing is on" from "there was nobody to ask".
     *
     * **It is only ever a few hours.** `get_short_epg` returns about eight entries, so this fills now
     * and the rest of the evening, never a whole scrollable day.
     */
    private suspend fun providerEntries(ch: ChannelEntity, shift: Int): List<XtEpgEntry>? {
        val streamId = ch.remoteId ?: return null
        val source = sourceDao.getById(ch.sourceId) ?: return null
        val raw = when (source.type) {
            SourceType.XTREAM -> runCatching { xtreamClient.getShortEpg(source, streamId, limit = SHORT_EPG_LIMIT) }
                .getOrNull().orEmpty()
            SourceType.STALKER -> runCatching {
                streamUrlResolver.shortEpg(source, streamId)
                    .map { XtEpgEntry(title = it.title, description = it.description, startMs = it.startMs, stopMs = it.stopMs) }
            }.getOrNull().orEmpty()
            else -> return null
        }
        // The provider's own guide needs the same shift as the stored one — same channel, same clock.
        return if (shift == 0) raw else raw.map {
            it.copy(startMs = it.startMs + shift * 60_000L, stopMs = it.stopMs + shift * 60_000L)
        }
    }

    /**
     * The provider's guide for [channel] as guide rows, ready to draw — the grid's half of the same
     * fallback the preview pane has always had.
     *
     * The rows are **synthetic** — they exist nowhere in the database — so anything that looks a
     * programme up by id must fall back to the row it already holds, which is why the description
     * travels with it rather than being fetched later.
     *
     * Their ids are the **negated start time**: negative, so they can never collide with a real row's
     * (Room generates those upwards from 1), and distinct within a channel, so a caller that gathers
     * ids into a set — the grid marks its catch-up cells that way — cannot have one synthetic row
     * stand for all of them.
     *
     * Cached per channel for [CACHE_TTL_MS], so a row scrolling in and out of view, and the list's
     * own periodic refresh, cost one request rather than one per look.
     */
    suspend fun providerProgrammes(
        channel: ChannelEntity,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
    ): List<EpgProgrammeEntity> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        providerRows[channel.id]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return@withContext it.rows }
        val shift = EpgShift.minutesFor(cust, channel, globalShiftMinutes)
        val key = (cust.epgMatchResolver.epgIdFor(channel) ?: channel.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: channel.streamUrl
        val rows = providerEntries(channel, shift).orEmpty().map {
            EpgProgrammeEntity(
                id = -it.startMs,
                sourceId = channel.sourceId,
                epgChannelId = key,
                startMs = it.startMs,
                stopMs = it.stopMs,
                title = it.title,
                description = it.description,
            )
        }.sortedBy { it.startMs }
        providerRows[channel.id] = CachedRows(now, rows)
        rows
    }

    /**
     * The programme currently airing on each of [channels] (channel id → title), looked up in ONE batch
     * against the stored bulk guide — same query the Home "On Now" rail uses. This powers the small
     * "current programme" subtitle under each channel row in the Live list and the in-player channel
     * overlay. Returns only channels that actually have something airing right now.
     *
     * **Two passes, because there are two guides.** The stored bulk guide answers first, in one query,
     * and covers most channels for the cost of a single round trip. Whatever it cannot answer then goes
     * through [nowNext] — *the very function that fills the preview pane* — which checks its own cache,
     * then the stored guide, then falls back to the provider's short-EPG API.
     *
     * That second pass is the whole point. Those short-EPG programmes are never written to the table, so
     * a channel served that way showed a full guide in the preview pane and a blank second line in the
     * list right beside it, permanently — the two were reading different guides. Now there is one answer
     * for one channel, and the row and the pane agree by construction rather than by coincidence: if the
     * preview has a programme the row shows it, and if the preview has none the row is blank too.
     *
     * **Nothing here goes to the network.** That is a hard rule, not an optimisation: see the note at
     * the second pass for what filling a list of hundreds by asking the provider actually did.
     */
    suspend fun nowPlayingFor(
        channels: List<ChannelEntity>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        // Every playlist plus every EPG feed — NOT just the sources the visible page happens to come
        // from. Guide rows are keyed by epgChannelId and routinely live under a different source than
        // the channel showing them (one playlist's xmltv.php covering another's channels), and
        // [nowNext] — which fills the preview pane — applies no source filter at all. Narrowing this to
        // the page's own sources made the two disagree: the preview showed a programme while the row
        // under it stayed blank, until an EPG re-sync happened to rewrite the rows under an id the
        // filter included.
        val epgIds = epgSourceStore.getAll().map { it.id }
        val sourceIds = (sourceDao.allSourceIds() + epgIds).distinct()
        // Manual EPG-match overrides take precedence over the channel's own epgChannelId, mirroring nowNext.
        // Shifted channels look up a different instant, so the batch is grouped by shift — with no
        // offsets configured (the normal case) that's still exactly one query group.
        val channelKeys = channels.mapNotNull { ch ->
            val key = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)
                ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (key != null) Triple(ch.id, key, EpgShift.minutesFor(cust, ch, globalShiftMinutes)) else null
        }
        if (channelKeys.isEmpty()) return@withContext emptyMap()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var chunks = 0
        val result = HashMap<Long, String>()
        for ((shift, group) in channelKeys.groupBy { it.third }) {
            val at = EpgShift.toStored(now, shift)
            val rowsByKey = group
                .map { it.second }.distinct()
                .chunked(400)
                .flatMap { keys ->
                    chunks++
                    epgDao.programmeSummariesForChannels(keys, at, at + 1)
                }
                .groupBy { it.epgChannelId }
            for ((channelId, epgKey, _) in group) {
                rowsByKey[epgKey]
                    ?.firstOrNull { at in it.startMs until it.stopMs }
                    ?.let { result[channelId] = it.title }
            }
        }
        // Second pass: whatever the preview pane has already resolved for a channel the stored guide
        // could not answer — **from cache, never from the network**.
        //
        // Fetching here was tried and was a mistake worth recording. A list holds hundreds of
        // channels, so "ask the provider for the ones we cannot answer" became hundreds of
        // `get_short_epg` requests, most of them returning nothing because that provider has no short
        // guide for those channels at all. Worse, the batch could not return until the last one
        // finished, so the list that used to fill from one query instantly now filled from nothing:
        // EVERY row went blank, which is the opposite of the bug it set out to fix.
        //
        // The provider is still asked — just never in bulk. The channel under the cursor is fetched
        // by the preview pane itself, and a guide row fetches as it scrolls into view. Both land in
        // this cache, so the list fills in behind them for free.
        val fromStored = result.size
        for (ch in channels) {
            if (ch.id in result) continue
            cachedNowTitle(ch.id, now)?.let { result[ch.id] = it }
        }
        CorePerf.log {
            "live_nowplaying channels=${channels.size} keyed=${channelKeys.size} " +
                "keys=${channelKeys.map { it.second }.distinct().size} chunks=$chunks " +
                "fromStored=$fromStored fromCache=${result.size - fromStored} " +
                "totalMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
        }
        result
    }

    /**
     * The current programme already resolved for [channelId], if it is still fresh — from either
     * cache, because the preview pane and the guide grid fill different ones.
     *
     * Deliberately never fetches. See the note in [nowPlayingFor] for what happened when it did.
     */
    private fun cachedNowTitle(channelId: Long, now: Long): String? {
        cache[channelId]?.takeIf { now - it.at < CACHE_TTL_MS }?.data?.now?.title
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return providerRows[channelId]
            ?.takeIf { now - it.at < CACHE_TTL_MS }
            ?.rows?.firstOrNull { it.startMs <= now && it.stopMs > now }
            ?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * What was on [ch] at [atMs], and what followed it — the archive counterpart of [nowNext], for the
     * "PLAYING / THEN" card shown while a recording is playing.
     *
     * Stored-guide only, deliberately: the provider short-EPG APIs report *now and upcoming*, so they
     * can say nothing about yesterday afternoon. A channel whose guide comes only from that API gets
     * no archive card, which is correct — there is genuinely no data for the moment being watched.
     *
     * Returns null when nothing is known at [atMs], so the caller can simply omit the card.
     */
    suspend fun nowNextAt(
        ch: ChannelEntity,
        atMs: Long,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
    ): EpgNowNext? = withContext(Dispatchers.IO) {
        val epgKey = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        // Same shift dance as [nowNext]: stored rows keep the feed's own clock, so look up the shifted
        // instant and move what comes back, or the card would disagree with the archive it describes.
        val shift = EpgShift.minutesFor(cust, ch, globalShiftMinutes)
        val at = EpgShift.toStored(atMs, shift)
        val playing = epgDao.nowPlaying(epgKey, at)
        val then = epgDao.upcoming(epgKey, at, 4).first()
            .firstOrNull { it.startMs > (playing?.startMs ?: 0) }
        if (playing == null && then == null) return@withContext null
        fun EpgProgrammeEntity.shifted() = EpgShift.apply(this, shift).toXt()
        EpgNowNext(playing?.shifted(), then?.shifted())
    }

    /** Recent (already-aired) programmes for a catch-up channel, newest first — drives the Live TV
     *  catch-up picker. Bounded to the EPG we retain (7 days) and the channel's archive window. */
    suspend fun catchupProgrammes(
        ch: ChannelEntity,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        liveSourceIds: List<Long>,
    ): List<EpgProgrammeEntity> = withContext(Dispatchers.IO) {
        if (!ch.catchup) return@withContext emptyList()
        val epgKey = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        val days = ch.catchupDays.takeIf { it > 0 } ?: DEFAULT_CATCHUP_DAYS
        val windowMs = (days * 24L * 60 * 60 * 1000).coerceAtMost(CATCHUP_LOOKBACK_CAP_MS)
        val ids = liveSourceIds + epgSourceStore.getAll().map { it.id }
        // Shifted guide → shifted window, and the rows come back on the corrected clock, so the
        // archive URL built from the picked programme asks for the time it really aired.
        val shift = EpgShift.minutesFor(cust, ch, globalShiftMinutes)
        val at = EpgShift.toStored(now, shift)
        epgDao.programmesForChannel(epgKey, at - windowMs, at + 60 * 60 * 1000)
            .filter { it.startMs <= at }           // already started → catch-up applies
            .sortedByDescending { it.startMs }      // most recent first
            .take(80)
            .map { EpgShift.apply(it, shift) }
    }

    /**
     * The guide entry that follows [afterStopMs] on [ch] — what a finished catch-up programme rolls
     * into. Null when the guide stops there, or when the gap to the next entry is longer than
     * [NEXT_PROGRAMME_GAP_CAP_MS]: a channel whose guide resumes tomorrow morning has nothing to
     * continue with tonight, and jumping hours forward is not "the next programme".
     *
     * [afterStopMs] is on the clock the user sees; the result comes back on it too, so the archive URL
     * built from it asks for the time the programme really aired.
     */
    suspend fun programmeAfter(
        ch: ChannelEntity,
        afterStopMs: Long,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        liveSourceIds: List<Long>,
    ): EpgProgrammeEntity? = withContext(Dispatchers.IO) {
        val epgKey = (cust.epgMatchResolver.epgIdFor(ch) ?: ch.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val shift = EpgShift.minutesFor(cust, ch, globalShiftMinutes)
        val from = EpgShift.toStored(afterStopMs, shift)
        val ids = liveSourceIds + epgSourceStore.getAll().map { it.id }
        epgDao.programmesForChannel(epgKey, from, from + NEXT_PROGRAMME_GAP_CAP_MS)
            // The query keeps anything still running at [from]; the next programme is the one that
            // starts at or after it.
            .firstOrNull { it.startMs >= from }
            ?.let { EpgShift.apply(it, shift) }
    }

    /** Full description for a programme picked in the catch-up dialog. The list query drops it to stay
     *  under the CursorWindow limit, so the detail popup fetches it on demand (same as the Guide). */
    suspend fun programmeDescription(programmeId: Long): String? =
        withContext(Dispatchers.IO) { runCatching { epgDao.programmeDescription(programmeId) }.getOrNull() }

    // The "Match EPG" picker used to be answered from here, filtered to the profile's own sources.
    // It is now [tv.own.owntv.core.epg.GuideCandidates], which filters by no source at all — the same
    // rule every guide read already followed, and the half of it that was missing.

    private fun EpgProgrammeEntity.toXt() =
        XtEpgEntry(title = title, description = description, startMs = startMs, stopMs = stopMs)
}

/**
 * The archive URLs Live TV builds: one for a picked catch-up programme, one for a live-rewind offset.
 * Both are pure construction — no state, no playback — kept beside the guide reads they follow on from.
 */
class LiveArchiveUrls(
    private val sourceDao: SourceDao,
    private val xtreamClient: XtreamClient,
    private val streamUrlResolver: StreamUrlResolver,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {
    /** The archive URL for [programme] on [ch], or null when it can't be built. */
    suspend fun forProgramme(ch: ChannelEntity, programme: EpgProgrammeEntity): String? =
        withContext(Dispatchers.IO) {
            val source = sourceDao.getById(ch.sourceId) ?: return@withContext null
            // Stalker archive URLs are minted per-play via create_link (Phase E §5.6); the others
            // are pure string templates handled by CatchupUrl.
            if (source.type == SourceType.STALKER) {
                ch.remoteId?.let { rid ->
                    runCatching { streamUrlResolver.resolveCatchup(source, rid, programme.startMs, programme.stopMs) }
                        .onFailure { Log.w(LOG_TAG, "Stalker catch-up resolve failed channelId=${ch.id}", it) }
                        .getOrNull()
                }
            } else {
                tv.own.owntv.core.epg.CatchupUrl.forSource(ch, programme, source, settings.resolveCatchupTimeZone(), xtreamClient)
            }
        }

    /** The rolling-archive URL for watching [ch] [offsetSec] behind the live edge. */
    suspend fun forTimeshift(
        ch: ChannelEntity,
        source: tv.own.owntv.core.database.entity.SourceEntity,
        startMs: Long,
        offsetSec: Int,
        tz: java.util.TimeZone,
    ): String? {
        val durationMin = (offsetSec / 60 + 5).coerceAtLeast(1) // rewound window + buffer to play up to live
        return when (source.type) {
            SourceType.XTREAM -> ch.remoteId?.let {
                // Always `.ts` — live rewind reads the same timeshift server as catch-up, and "Prefer
                // HLS" deliberately does not apply to it. See [CatchupUrl.forSource] for why: the
                // archive has no HLS repackager, so `.m3u8` there answers with an error page.
                xtreamClient.timeshiftUrl(source, it, startMs, durationMin, tz, "ts")
            }
            // No HLS swap here: [resolveStreamUrl] is Xtream-only, so it would be a no-op on M3U.
            SourceType.M3U -> tv.own.owntv.core.epg.CatchupUrl.forM3u(
                ch.streamUrl,
                ch.catchupType,
                ch.catchupSource,
                startMs,
                startMs + durationMin * 60_000L,
                tz,
            )
            // Stalker rewind = the same per-play archive create_link as catch-up (Phase E §5.6).
            SourceType.STALKER -> ch.remoteId?.let { rid ->
                runCatching { streamUrlResolver.resolveCatchup(source, rid, startMs, startMs + durationMin * 60_000L) }
                    .onFailure { Log.w(LOG_TAG, "Stalker rewind resolve failed channelId=${ch.id}", it) }
                    .getOrNull()
            }
            else -> null
        }
    }
}
