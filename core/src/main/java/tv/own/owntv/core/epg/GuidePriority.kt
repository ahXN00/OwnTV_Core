package tv.own.owntv.core.epg

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Which guide wins when two feeds carry the same channel: the one higher in Settings → EPG Sources.
 *
 * Guide reads are keyed by `epgChannelId` alone (see the note in
 * [tv.own.owntv.core.database.dao.EpgDao]), so a channel two feeds carry comes back with both feeds'
 * programmes. [EpgDedupe] could only hide the ones that are plainly the same broadcast; where the
 * feeds disagree about what is on, both blocks were drawn on top of each other and Now picked
 * whichever started last. This makes the answer the user's: the highest-ranked feed with programmes
 * for the channel is drawn in full, and a lower one only fills the time it leaves empty.
 *
 * Works on rows the caller has already read, so it adds no query to the guide's hot path. Rows from
 * one rank (the usual case: one feed per channel) go straight to [EpgDedupe], exactly as before.
 */
class GuidePriority private constructor(private val rankBySource: Map<Long, Int>) {

    /** [rows] for one channel, overlaps settled by rank, in start order. */
    fun resolve(rows: List<EpgProgrammeEntity>): List<EpgProgrammeEntity> {
        if (rows.size < 2) return rows
        val firstRank = rankOf(rows[0].sourceId)
        if (rows.all { rankOf(it.sourceId) == firstRank }) return EpgDedupe.collapse(rows)
        val kept = ArrayList<EpgProgrammeEntity>(rows.size)
        for ((_, group) in rows.groupBy { rankOf(it.sourceId) }.toSortedMap()) {
            // A whole rank is taken at once, so a feed's own rows never push each other out; only a
            // higher feed's rows can.
            val higher = kept.toList()
            group.filterTo(kept) { row -> higher.none { it.overlaps(row) } }
        }
        return EpgDedupe.collapse(kept)
    }

    /** The source's place in the list; any source not on it (an old playlist guide) ranks last. */
    private fun rankOf(sourceId: Long): Int = rankBySource[sourceId] ?: Int.MAX_VALUE

    private fun EpgProgrammeEntity.overlaps(other: EpgProgrammeEntity): Boolean =
        startMs < other.stopMs && other.startMs < stopMs

    companion object {
        /** [sourceIds] best first, as the EPG Sources list shows them. */
        fun of(sourceIds: List<Long>): GuidePriority =
            GuidePriority(sourceIds.withIndex().associate { (index, id) -> id to index })
    }
}
