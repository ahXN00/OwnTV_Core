package tv.own.owntv.core.live

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
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

/** Guide rows are looked up by epg id in chunks, to stay inside SQLite's variable limit. */
private const val KEY_CHUNK = 400

/**
 * Reading a span of guide, rather than a single channel's now/next.
 *
 * The Guide asks a different question from Live TV: not "what is on this channel", but "what is on
 * every one of these channels, between these two instants". That is the app's heaviest query, and
 * getting it wrong is not slow but fatal — a large lineup holds far more rows than a low-RAM
 * television has heap for — so it lives here, once, rather than in each app's guide screen.
 *
 * Every read here is bounded by **what the caller asked about**: one channel ([row]) or a named list
 * of them ([slice]). There is deliberately no "give me everything in this window" — that existed, and
 * it is what an OutOfMemoryError looks like on a real catalogue.
 *
 * Nothing here caches: what to keep and when to drop it depends on how the screen scrolls, which is
 * the caller's business. The guide shift is passed in for the same reason [LiveEpgReader] takes it —
 * the caller is the one place that knows a customization changed.
 */
class GuideReader(
    private val epgDao: EpgDao,
    private val epgSourceStore: EpgSourceStore,
    private val sourceDao: SourceDao,
    /**
     * The second guide. [row] falls back to its provider fetch when the stored table has nothing for
     * a channel in the window, so the grid shows what the preview pane has always shown.
     */
    private val liveEpgReader: LiveEpgReader,
) {
    /**
     * Every playlist plus every EPG feed. Guide rows are keyed by epg id and routinely live under a
     * different source than the channel showing them — one playlist's `xmltv.php` covering another's
     * lineup — so narrowing this to the channels' own sources loses rows that are really there.
     */
    suspend fun guideSourceIds(): List<Long> = withContext(Dispatchers.IO) {
        (sourceDao.allSourceIds() + epgSourceStore.getAll().map { it.id }).distinct()
    }

    // A `window(from, to)` used to live here: it paged the WHOLE guide window into one ArrayList and
    // grouped it by channel, so the grid could be drawn from one batch. It is deleted, not fixed,
    // because the shape was the fault.
    //
    // Measured on the owner's television (2026-09-18), it read **349,077 programme rows in a single
    // list** over a 166-hour window — seven days of catch-up lookback for all 3,585 guide channels —
    // to draw the eight rows on screen. That is ~100 MB against a 192 MB heap before anything else,
    // and it spent 146 seconds inside `groupBy` because the heap was thrashing rather than working.
    // A guide re-sync then re-triggered it on every batch write, six loads deep and overlapping, and
    // the app died with an OutOfMemoryError inside [EpgDedupe.collapse].
    //
    // Nothing replaces it: [row] already answers the same question per channel through the
    // (epgChannelId, startMs) index, and the phone has always drawn its guide that way. Bounding the
    // batch would only have chosen which channels to silently lose.

    /**
     * One channel's programmes in the window, on the clock the user sees.
     *
     * **This is how a guide row is drawn**, on both the television and the phone. It reads one
     * channel through the `(epgChannelId, startMs)` index, so its cost is the size of that row rather
     * than the size of the database, and a shifted channel is simply a different slice of stored time
     * asked for by the same query. The caller decides what to keep; see the deleted-batch note above
     * for why there is no longer a bulk alternative.
     */
    suspend fun row(
        channel: ChannelEntity,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        from: Long,
        to: Long,
    ): List<EpgProgrammeEntity> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val epgKey = epgKeyOf(channel, cust)
        val shift = EpgShift.minutesFor(cust, channel, globalShiftMinutes)
        val stored = when {
            epgKey == null -> emptyList()
            shift == 0 -> epgDao.programmeSummariesForChannel(epgKey, from, to)
            else -> epgDao
                .programmeSummariesForChannel(epgKey, EpgShift.toStored(from, shift), EpgShift.toStored(to, shift))
                .map { EpgShift.apply(it, shift) }
        }
        if (stored.isNotEmpty()) {
            return@withContext EpgDedupe.collapse(stored).also { collapsed ->
                CorePerf.log {
                    "guide_row source=stored channelId=${channel.id} key=$epgKey shift=$shift " +
                        "rows=${stored.size} collapsed=${collapsed.size} " +
                        "totalMs=${SystemClock.elapsedRealtime() - startedAt}"
                }
            }
        }
        // Nothing stored for this channel in this window. That is not the same as nothing being on:
        // the bulk guide can simply stop — a feed that ends at midnight leaves today's daytime blank —
        // and the provider's own short-EPG endpoint answers for exactly that gap. It is what the
        // preview pane has always fallen back to, and the grid drawing a row of nothing beside a
        // preview listing programmes is the contradiction this removes.
        //
        // Only the part of it inside the asked-for window is returned, so a row never draws outside
        // the time it was asked about.
        liveEpgReader.providerProgrammes(channel, cust, globalShiftMinutes)
            .filter { it.stopMs > from && it.startMs < to }
            .also { rows ->
                CorePerf.log {
                    "guide_row source=provider channelId=${channel.id} key=$epgKey shift=$shift " +
                        "rows=${rows.size} totalMs=${SystemClock.elapsedRealtime() - startedAt}"
                }
            }
    }

    /**
     * A window of several channels at once, keyed by channel id and already on the user's clock —
     * what a Home rail or an "on now" list needs.
     *
     * One query per *shift group*, not per channel: channels with the same offset (almost always all
     * of them) read one moved window together, so a rail of twenty channels is one query. The order
     * of [channels] is kept, because the rails render straight off this map's iteration order.
     */
    suspend fun slice(
        channels: List<ChannelEntity>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        from: Long,
        to: Long,
    ): Map<Long, List<EpgProgrammeEntity>> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val keyed = channels.mapNotNull { ch ->
            epgKeyOf(ch, cust)?.let { key -> Triple(ch.id, key, EpgShift.minutesFor(cust, ch, globalShiftMinutes)) }
        }
        if (keyed.isEmpty()) return@withContext emptyMap()
        val startedAt = SystemClock.elapsedRealtime()
        var chunks = 0
        var rowsRead = 0
        val collected = HashMap<Long, List<EpgProgrammeEntity>>()
        for ((shift, group) in keyed.groupBy { it.third }) {
            val rowsByKey = group
                .map { it.second }.distinct()
                .chunked(KEY_CHUNK)
                .flatMap { keys ->
                    chunks++
                    epgDao.programmeSummariesForChannels(
                        keys,
                        EpgShift.toStored(from, shift),
                        EpgShift.toStored(to, shift),
                    ).also { rowsRead += it.size }
                }
                .groupBy { it.epgChannelId }
            for ((channelId, epgKey, _) in group) {
                rowsByKey[epgKey]?.takeIf { it.isNotEmpty() }
                    ?.let { collected[channelId] = EpgShift.apply(EpgDedupe.collapse(it), shift) }
            }
        }
        val ordered = LinkedHashMap<Long, List<EpgProgrammeEntity>>(collected.size)
        for (channel in channels) collected[channel.id]?.let { ordered[channel.id] = it }
        CorePerf.log {
            "guide_slice channels=${channels.size} keys=${keyed.map { it.second }.distinct().size} " +
                "shiftGroups=${keyed.groupBy { it.third }.size} chunks=$chunks rows=$rowsRead " +
                "answered=${ordered.size} spanH=${(to - from) / 3_600_000} " +
                "totalMs=${SystemClock.elapsedRealtime() - startedAt}"
        }
        ordered
    }

    /**
     * What is on each of [channels] at [atMs], and what follows it — the "on now" list.
     *
     * [LiveEpgReader.nowPlayingFor] answers the same question with only a title, which is all a
     * channel row under a name needs. A guide list is about the programme itself: it draws how far
     * through it is and what is next, so it needs the rows.
     */
    suspend fun onNow(
        channels: List<ChannelEntity>,
        cust: SectionCustomizations,
        globalShiftMinutes: Int,
        atMs: Long,
        lookAheadMs: Long,
    ): Map<Long, GuideSlot> {
        val rows = slice(channels, cust, globalShiftMinutes, atMs, atMs + lookAheadMs)
        val result = HashMap<Long, GuideSlot>(rows.size)
        for ((channelId, list) in rows) {
            val now = list.firstOrNull { atMs in it.startMs until it.stopMs }
            val next = list.firstOrNull { it.startMs > (now?.startMs ?: atMs) }
            if (now == null && next == null) continue
            result[channelId] = GuideSlot(now = now, next = next)
        }
        return result
    }

    /** The synopsis of one programme, fetched when it is opened — the list queries drop it. */
    suspend fun description(programmeId: Long): String? =
        withContext(Dispatchers.IO) { runCatching { epgDao.programmeDescription(programmeId) }.getOrNull() }

    /** The guide id this channel really reads from: a manual match wins over the channel's own. */
    private fun epgKeyOf(channel: ChannelEntity, cust: SectionCustomizations): String? =
        (cust.epgMatchResolver.epgIdFor(channel) ?: channel.epgChannelId)
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** What is on a channel now, and what follows it — both already on the user's clock. */
data class GuideSlot(val now: EpgProgrammeEntity?, val next: EpgProgrammeEntity?)
