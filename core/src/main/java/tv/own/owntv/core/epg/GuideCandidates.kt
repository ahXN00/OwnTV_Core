package tv.own.owntv.core.epg

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.CorePerf
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.EpgChannelName

/**
 * One guide channel the user can be offered: what the "Match EPG" picker lists, and what auto-match
 * scores against.
 *
 * [hasProgrammes] is carried on the row rather than asked for separately because both callers need
 * it and both would otherwise walk the same table a second time to get it. An entry with no
 * programmes is still a real candidate — a feed can name a channel today and schedule it tomorrow —
 * but it is not something to apply silently, which is what it is here for.
 */
data class GuideCandidate(
    val epgChannelId: String,
    val displayName: String?,
    val hasProgrammes: Boolean,
    /**
     * The matcher's normalized forms, read from storage where the sync wrote them (v41) and computed
     * on the fly where it did not. Carried on the row so that searching and ranking a few thousand
     * candidates does not repeat an NFKC pass and four regexes per candidate per keystroke.
     */
    val normName: String = "",
    val normId: String = "",
)

/**
 * The single way anything gets guide candidates.
 *
 * Two things about it are deliberate and are the whole reason it exists.
 *
 * **It does not filter by source.** Every guide *read* stopped doing that (see the "Guide reads:
 * keyed by epgChannelId ALONE" block in [EpgDao]), because which feed delivered a row is not part of
 * a channel's guide identity. The picker and auto-match kept their own filtered query, so the two
 * answered differently: the grid drew programmes for a channel the picker then refused to list, and
 * that reads to a user as "matching is broken". This is the other half of that fix.
 *
 * **It looks at `epg_programmes` as well as `epg_channels`.** A feed is not required to carry
 * `<channel>` entries at all; plenty carry only `<programme>`. Reading the channel table alone made
 * every one of those invisible to the picker while their programmes sat in the database being drawn
 * by the guide.
 *
 * Takes only an [EpgDao] — no source store, no profile — because after the two points above there is
 * nothing else to take. That is also what makes it constructible from either app without ceremony.
 */
class GuideCandidates(private val epgDao: EpgDao) {

    /**
     * Every guide channel, at most [limit] of each underlying table.
     *
     * The merge runs in Kotlin rather than SQL so the display name can be chosen properly: the same
     * channel is routinely named by two feeds, and `GROUP BY` hands back an arbitrary one of them.
     * The longest non-blank name wins, which is stable and is almost always the informative one
     * ("BBC One HD" over "BBC1"). Phase 5 of the guide plan moves this into indexed columns; until
     * then the sets involved are a few thousand rows and this is measured, not assumed — see the
     * `guide_candidates` line.
     */
    suspend fun all(limit: Int = MAX_CANDIDATES): List<GuideCandidate> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val named = epgDao.guideChannelNames(limit)
        val withProgrammes = epgDao.guideProgrammeChannelIds(limit)
        val queriedAt = SystemClock.elapsedRealtime()
        val candidates = merge(named, withProgrammes)
        CorePerf.log {
            "guide_candidates named=${named.size} withProgrammes=${withProgrammes.size} " +
                "merged=${candidates.size} nameless=${candidates.count { it.displayName == null }} " +
                "emptyGuide=${candidates.count { !it.hasProgrammes }} " +
                "namedLimitHit=${named.size >= limit} programmeLimitHit=${withProgrammes.size >= limit} " +
                "queryMs=${queriedAt - startedAt} totalMs=${SystemClock.elapsedRealtime() - startedAt}"
        }
        // A truncated candidate set silently costs the user matches, so it is said out loud rather
        // than left to be inferred from a round number. The old query hid the same thing behind an
        // alphabetical `LIMIT`, which dropped the end of the alphabet and nothing else.
        if (named.size >= limit || withProgrammes.size >= limit) {
            android.util.Log.w(
                LOG_TAG,
                "Guide candidate limit $limit reached (named=${named.size} withProgrammes=${withProgrammes.size}) — list truncated",
            )
        }
        candidates
    }

    /**
     * The picker's list: [all], filtered by what the user typed and ranked so the channels resembling
     * [channelName] come first rather than appearing in whatever order the feeds arrived in.
     *
     * Ranking the whole set and then capping — rather than capping first — is what makes the right
     * answer reachable: the best name match is frequently nowhere near the top of an arbitrary order.
     */
    suspend fun forPicker(
        channelName: String,
        query: String,
        limit: Int = MAX_CANDIDATES,
        resultLimit: Int = PICKER_RESULTS,
    ): List<GuideCandidate> {
        val all = all(limit)
        // Filtered through the matcher's own normalizer rather than a raw substring — see
        // [EpgMatcher.matchesSearch] for the two things a raw substring could not do.
        val filtered = if (query.isBlank()) {
            all
        } else {
            all.filter { EpgMatcher.matchesNormalizedSearch(query, it.normName, it.normId) }
        }
        if (filtered.isEmpty()) return emptyList()
        val startedAt = SystemClock.elapsedRealtime()
        return EpgMatcher
            .rankForPickerParallel(channelName, filtered, { it.displayName }, { it.epgChannelId })
            .take(resultLimit)
            .also { ranked ->
                CorePerf.log {
                    "guide_picker candidates=${all.size} filtered=${filtered.size} ranked=${ranked.size} " +
                        "rankMs=${SystemClock.elapsedRealtime() - startedAt}"
                }
            }
    }

    companion object {
        /**
         * The two table reads combined into one candidate list — pure, so it is tested on the JVM
         * rather than only through a database.
         *
         * Three rules, each of which was a defect before it was one:
         * - an id known only to `epg_programmes` is still a candidate, with no name;
         * - an id known only to `epg_channels` is still a candidate, flagged as having no programmes;
         * - where several feeds name one channel, the longest non-blank name wins, so the answer does
         *   not depend on which feed was synced last.
         */
        internal fun merge(
            named: List<EpgChannelName>,
            withProgrammes: List<String>,
        ): List<GuideCandidate> {
            val programmeIds = withProgrammes.toHashSet()
            val bestName = HashMap<String, String>(named.size)
            for (row in named) {
                val name = row.displayName?.trim().orEmpty()
                if (name.isEmpty()) continue
                val current = bestName[row.epgChannelId]
                if (current == null || name.length > current.length) bestName[row.epgChannelId] = name
            }
            // The stored normalized name that belongs to the display name actually chosen. A row
            // written before v41 carries none, so it is computed here instead — never dropped.
            val storedNormName = HashMap<String, String>(named.size)
            val storedNormId = HashMap<String, String>(named.size)
            for (row in named) {
                row.normId?.takeIf { it.isNotEmpty() }?.let { storedNormId.putIfAbsent(row.epgChannelId, it) }
                val name = row.displayName?.trim().orEmpty()
                if (name.isNotEmpty() && name == bestName[row.epgChannelId]) {
                    row.normName?.let { storedNormName[row.epgChannelId] = it }
                }
            }

            val ids = LinkedHashSet<String>(named.size + withProgrammes.size)
            named.forEach { ids.add(it.epgChannelId) }
            ids.addAll(withProgrammes)
            return ids.map { id ->
                val displayName = bestName[id]
                GuideCandidate(
                    epgChannelId = id,
                    displayName = displayName,
                    hasProgrammes = id in programmeIds,
                    normName = storedNormName[id]
                        ?: displayName?.let(EpgMatcher::normalizeForEpg).orEmpty(),
                    normId = storedNormId[id] ?: EpgMatcher.normalizeForEpg(id),
                )
            }
        }

        /** Per table. Large enough for any real feed; a ceiling rather than a page size. */
        const val MAX_CANDIDATES = 20_000

        /** How many ranked entries the dialog shows. Beyond this nobody scrolls. */
        const val PICKER_RESULTS = 300

        private const val LOG_TAG = "GuideCandidates"
    }
}
