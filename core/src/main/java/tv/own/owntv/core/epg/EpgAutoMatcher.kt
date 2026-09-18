package tv.own.owntv.core.epg

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.CorePerf
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity

/** A guide channel proposed for a channel the matcher was not confident enough to link on its own. */
data class AutoMatchSuggestion(
    val channel: ChannelEntity,
    val epgChannelId: String,
    val displayName: String?,
    val score: Double,
    /** False when the winning guide channel has no programmes stored — never auto-applied. */
    val hasProgrammes: Boolean,
)

/** What one auto-match run decided. Presentation is the app's; the decisions are not. */
data class AutoMatchOutcome(
    /** Confident hits, already checked to have programmes: item key → guide channel id. */
    val applied: List<Pair<String, String>>,
    /** Everything the user is asked about, best first. */
    val review: List<AutoMatchSuggestion>,
    /** False when there is no guide data at all — a different message from "nothing matched". */
    val hadCandidates: Boolean,
    /** A confident hit was held back because its guide channel carries no programmes. */
    val withheldForNoProgrammes: Int,
)

/**
 * Matching channels to guide channels by name, in one place.
 *
 * This existed three times — the television's bulk run, the television's single-channel run, and the
 * phone's bulk run — and the three had already drifted apart on which candidates they considered.
 * Presentation stays in each app; the decisions do not.
 *
 * Two of those decisions changed here, and both were previously dishonest:
 *
 * **"Needs a match" means no programmes, not an unknown id.** A channel whose `tvg-id` appears as an
 * empty `<channel>` entry in some feed was treated as already having a working guide, so it was
 * skipped on every run, for ever, while its guide row stayed blank.
 *
 * **A winner with no programmes is never applied silently.** A 0.95 name hit onto a guide channel
 * with nothing scheduled was counted as a success and reported as one, leaving the user with the same
 * empty row and a message saying it had been fixed. It now goes to review with its score, so the
 * choice is the user's.
 */
class EpgAutoMatcher(
    private val channelDao: ChannelDao,
    private val guideCandidates: GuideCandidates,
) {

    /**
     * Match every channel of [playlistIds] that has no working guide.
     *
     * Nothing is persisted here — the caller writes [AutoMatchOutcome.applied], because writing is
     * per-profile and the store is the app's to talk to.
     */
    suspend fun run(
        cust: SectionCustomizations,
        playlistIds: List<Long>,
        maxChannels: Int = MAX_CHANNELS,
    ): AutoMatchOutcome {
        val startedAt = SystemClock.elapsedRealtime()
        val candidates = guideCandidates.all()
        if (candidates.isEmpty()) {
            return AutoMatchOutcome(emptyList(), emptyList(), hadCandidates = false, withheldForNoProgrammes = 0)
        }
        // Only ids that actually have programmes count as "this channel already has a guide". An id
        // that exists but carries nothing is exactly the case that needs matching.
        val idsWithProgrammes = candidates.filter { it.hasProgrammes }
            .mapTo(HashSet()) { it.epgChannelId.trim().lowercase() }
        val hasProgrammesById = candidates.associate { it.epgChannelId to it.hasProgrammes }
        val channels = channelDao.allForSources(playlistIds, maxChannels)

        return withContext(Dispatchers.Default) {
            val prepared = EpgMatcher.prepare(
                candidates.map { EpgMatcher.Candidate(it.epgChannelId, it.displayName) },
            )
            // Narrow before scoring: the scan is channels × candidates, which is millions of
            // comparisons on a full lineup and minutes of spinner on TV silicon if it runs unfiltered.
            val unmatched = channels.filter { needsMatch(it, cust, idsWithProgrammes) }
            val best = EpgMatcher.bestEpgMatchBulk(unmatched.map { it.name }, prepared)

            val applied = mutableListOf<Pair<String, String>>()
            val review = mutableListOf<AutoMatchSuggestion>()
            var withheld = 0
            for ((channel, match) in unmatched.zip(best)) {
                if (match == null) continue
                val hasProgrammes = hasProgrammesById[match.epgChannelId] == true
                val confident = match.score >= EpgMatcher.AUTO_THRESHOLD
                when {
                    confident && hasProgrammes -> applied.add(CustomizeKeys.channel(channel) to match.epgChannelId)
                    // Confident, but the guide channel is empty. Applying it would report a success
                    // the user cannot see, so it is offered instead.
                    confident -> {
                        withheld++
                        review.add(match.toSuggestion(channel, hasProgrammes = false))
                    }
                    else -> review.add(match.toSuggestion(channel, hasProgrammes))
                }
            }
            CorePerf.log {
                "epg_automatch candidates=${candidates.size} withProgrammes=${idsWithProgrammes.size} " +
                    "channels=${channels.size} unmatched=${unmatched.size} " +
                    "applied=${applied.size} review=${review.size} withheld=$withheld " +
                    "totalMs=${SystemClock.elapsedRealtime() - startedAt}"
            }
            AutoMatchOutcome(
                applied = applied,
                review = review.sortedByDescending { it.score },
                hadCandidates = true,
                withheldForNoProgrammes = withheld,
            )
        }
    }

    /**
     * The best guide channel for one channel, for the long-press "Auto-match".
     *
     * Always a suggestion, never applied — a single deliberate action deserves to be confirmed, and
     * [AutoMatchSuggestion.hasProgrammes] tells the caller whether to warn that the guide is empty.
     */
    suspend fun one(channel: ChannelEntity): AutoMatchSuggestion? {
        val candidates = guideCandidates.all()
        if (candidates.isEmpty()) return null
        val hasProgrammesById = candidates.associate { it.epgChannelId to it.hasProgrammes }
        return withContext(Dispatchers.Default) {
            val best = EpgMatcher.bestEpgMatchPrepared(
                channel.name,
                EpgMatcher.prepare(candidates.map { EpgMatcher.Candidate(it.epgChannelId, it.displayName) }),
            )
            best?.toSuggestion(channel, hasProgrammesById[best.epgChannelId] == true)
        }
    }

    /** Whether there is a guide behind this channel today — matched, or via its own tvg-id. */
    private fun needsMatch(
        channel: ChannelEntity,
        cust: SectionCustomizations,
        idsWithProgrammes: Set<String>,
    ): Boolean {
        val key = CustomizeKeys.channel(channel)
        if (key in cust.hiddenItems) return false
        // Through the resolver, so a match that survived a re-added playlist is not matched again.
        if (cust.epgMatchResolver.epgIdFor(channel) != null) return false
        val tvg = channel.epgChannelId?.trim()?.lowercase()
        return tvg.isNullOrEmpty() || tvg !in idsWithProgrammes
    }

    private fun EpgMatcher.Result.toSuggestion(channel: ChannelEntity, hasProgrammes: Boolean) =
        AutoMatchSuggestion(
            channel = channel,
            epgChannelId = epgChannelId,
            displayName = displayName,
            score = score,
            hasProgrammes = hasProgrammes,
        )

    private companion object {
        /** Upper bound on channels scanned in one run. Matches what both apps already used. */
        const val MAX_CHANNELS = 20_000
    }
}
