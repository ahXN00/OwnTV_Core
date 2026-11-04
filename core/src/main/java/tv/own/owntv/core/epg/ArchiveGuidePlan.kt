package tv.own.owntv.core.epg

import tv.own.owntv.core.live.DEFAULT_CATCHUP_DAYS
import tv.own.owntv.core.parser.XtEpgEntry

/**
 * The rules for adding a catch-up channel's past from the provider's own schedule of it
 * ([tv.own.owntv.core.repository.EpgRepository.fillArchiveGuide]).
 *
 * Many Xtream providers put about a day of past programmes in `xmltv.php` while their archive keeps
 * up to a week: the recordings exist, the guide cannot reach them. The per-channel schedule lists the
 * whole archive. What it adds only ever extends the guide backwards — the feed's rows are never
 * replaced or overlapped. The rows live in a guide store of their own ([storeId]) and are replaced as
 * a whole at each fill ([changed]).
 */
internal object ArchiveGuidePlan {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A channel's schedule is asked for again after this long… */
    const val REFETCH_MS = 6L * 60 * 60 * 1000

    /** …or after this long when the last attempt failed. */
    const val RETRY_MS = 30L * 60 * 1000

    private const val STORE_BASE = 1_000_000_000L

    /**
     * The guide store a playlist's archive rows are written under, apart from the playlist's own id.
     * A guide sync deletes every row of the store it syncs that the feed no longer lists, and a
     * playlist's own guide is synced under the playlist's id (the guide screen's refresh), so rows kept
     * there were gone after the next refresh. EPG sources take small negative ids (-1, -2, …) and
     * playlists positive ones; this range is clear of both. Not an EPG source, it ranks below all of
     * them in [GuidePriority], as the playlist's own guide does.
     */
    fun storeId(playlistSourceId: Long): Long = -(STORE_BASE + playlistSourceId)

    fun due(lastTryMs: Long?, nowMs: Long): Boolean = lastTryMs == null || nowMs - lastTryMs >= REFETCH_MS

    /** The attempt time to record after a failure, so the next one is due [RETRY_MS] later. */
    fun failedAt(nowMs: Long): Long = nowMs - REFETCH_MS + RETRY_MS

    /**
     * The oldest instant worth storing: the channel's archive depth (the shared default when the
     * playlist does not say), at least one day and never beyond [maxBackMs], the guide's retention.
     */
    fun windowStart(nowMs: Long, catchupDays: Int, maxBackMs: Long): Long {
        val days = (catchupDays.takeIf { it > 0 } ?: DEFAULT_CATCHUP_DAYS).coerceAtLeast(1)
        return nowMs - (days * DAY_MS).coerceAtMost(maxBackMs)
    }

    /**
     * What to store: finished programmes inside the window that end by [guideStartMs], the oldest
     * programme the guide feeds hold for the channel. Nothing when the feeds hold nothing for the
     * channel: its row then comes from the provider's short EPG, which stored past rows would hide.
     * Duplicates are dropped; sorted by start.
     */
    fun toStore(
        listings: List<XtEpgEntry>,
        guideStartMs: Long?,
        windowStartMs: Long,
        nowMs: Long,
    ): List<XtEpgEntry> {
        if (guideStartMs == null) return emptyList()
        return listings.asSequence()
            .filter { it.startMs in windowStartMs until it.stopMs && it.stopMs <= nowMs && it.stopMs <= guideStartMs }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
            .toList()
    }

    /**
     * Whether the rows an earlier fill stored ([storedStarts]) are to be replaced by [keep]. They are
     * replaced as a whole rather than added to: the feed's oldest programme moves between two fills,
     * so rows stored last time may now overlap it or stop short of it, and the provider's schedule
     * itself moves on.
     */
    fun changed(keep: List<XtEpgEntry>, storedStarts: List<Long>): Boolean =
        keep.map { it.startMs } != storedStarts.sorted()
}
