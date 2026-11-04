package tv.own.owntv.core.sports

import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState

/**
 * What a screen is showing, and so what it needs kept fresh. A screen holds one while it is visible
 * ([SportsRepository.observe]); when the last one goes, nothing is fetched at all.
 */
sealed interface Demand {
    /** The Sports hub: every followed league's live matches. */
    data object Hub : Demand

    /** Home's "Live sports" row: as the hub. */
    data object HomeRow : Demand

    /** A match page: that match's summary (timeline, stats, line-ups). */
    data class MatchPage(val match: Match) : Demand

    /**
     * One league, on its page or in the Leagues tab: that league's live matches, and its table or
     * ranking if the board has none. [leagueKey] is a catalogue key.
     */
    data class LeaguePage(val leagueKey: String) : Demand

    /** The Calendar tab: as the hub, so today's and tomorrow's matches laid over the month stay live. */
    data object Calendar : Demand

    /** The Leagues tab's cards: as the hub, so a card's next match turns live. */
    data object Leagues : Demand

    /** A team's page: its league's live matches, and that league's table if the board has none. */
    data class TeamPage(val leagueKey: String) : Demand

    /** The score in the player's info bar: the scoreboard [match] is on. */
    data class PlayerScore(val match: Match) : Demand
}

/** One thing that can be fetched. */
sealed interface Feed {
    data class Scoreboard(val leagueKey: String, val tomorrow: Boolean = false) : Feed
    data class Summary(val matchKey: String) : Feed
    data class Standings(val leagueKey: String) : Feed
    data class Rankings(val leagueKey: String) : Feed
}

/**
 * What is known about a feed. [fetchedAt] is the last success, [attemptedAt] the last try (success or
 * not), [failures] how many tries in a row failed, and [matches] what the last success held, which is
 * what says whether it is live and when its next kick-off is.
 */
data class FeedState(
    val fetchedAt: Long? = null,
    val attemptedAt: Long? = null,
    val failures: Int = 0,
    val matches: List<Match> = emptyList(),
)

/** [fetch] is due now; [nextWakeAt] is when to ask again (null: never, until a demand changes). */
data class RefreshPlan(val fetch: Set<Feed>, val nextWakeAt: Long?, val paused: Boolean = false)

/**
 * The live refresh, as a pure function of what is on screen, what is followed and what
 * the board holds. The board itself is built by a rebuild; this only keeps what is live up to date.
 * No clock and no timer of its own: the repository asks it after every change and sleeps until
 * [RefreshPlan.nextWakeAt].
 *
 * | Situation                          | Fetched                          | When                                       |
 * |------------------------------------|----------------------------------|--------------------------------------------|
 * | Hub, Home row, Calendar, Leagues: a league is live | that league's scoreboard | the live setting (30/60/90/120 s) |
 * | Hub, Home row, Calendar, Leagues: nothing live     | nothing           | woken at its next kick-off, then as live   |
 * | Match page, live / not live        | the summary                      | the live setting / once if the board has none |
 * | League page                        | its scoreboard, as the hub; its table or ranking once if the board has none |   |
 * | Team page                          | its league's scoreboard, as the hub; the table once if the board has none |   |
 * | Player score                       | that match's league              | the live setting while it is live          |
 * | No demand                          | nothing                          | never                                      |
 * | Errors                             | back off 30 s → 1 → 2 → 5 min    | HTTP 403/429: everything pauses 15 min     |
 *
 * Nothing is fetched again within [MIN_AGE_MS] of the last try, whatever asks for it.
 */
class SportsRefreshPolicy {

    fun plan(
        demands: Set<Demand>,
        followed: Set<String>,
        feeds: Map<Feed, FeedState>,
        now: Long,
        liveRefreshSeconds: Int = DEFAULT_LIVE_SECONDS,
        pausedUntil: Long = 0L,
    ): RefreshPlan {
        val wakes = wakes(demands, followed, feeds, now, liveSeconds(liveRefreshSeconds) * 1_000L)
        if (wakes.isEmpty()) return RefreshPlan(emptySet(), null)
        if (pausedUntil > now) return RefreshPlan(emptySet(), pausedUntil, paused = true)
        val due = wakes.filterValues { it <= now }.keys
        return RefreshPlan(due, wakes.values.filter { it > now }.minOrNull())
    }

    /** When a failed fetch with [code] should stop every fetch until (null: it should not). */
    fun pauseAfter(code: Int, now: Long): Long? = if (code == 403 || code == 429) now + PAUSE_MS else null

    /**
     * Whether the board should be rebuilt by itself now. [rebuiltAt] and [builtFor] describe the
     * stored board; [triedAt] is when the last rebuild started, so one that failed (started after the
     * last success) waits [REBUILD_RETRY_MS]. A changed follow list counts in every mode but
     * [AutoRebuild.MANUAL].
     */
    fun rebuildDue(
        mode: AutoRebuild,
        followed: Set<String>,
        rebuiltAt: Long?,
        builtFor: Set<String>?,
        triedAt: Long?,
        startedThisRun: Boolean,
        now: Long,
    ): Boolean {
        if (mode == AutoRebuild.MANUAL) return false
        if (failedAt(rebuiltAt, triedAt)?.let { now - it < REBUILD_RETRY_MS } == true) return false
        if (rebuiltAt == null || builtFor != followed) return true
        return when (mode) {
            AutoRebuild.APP_START -> !startedThisRun
            else -> now - rebuiltAt >= hoursOf(mode) * HOUR_MS
        }
    }

    /**
     * The times [rebuildDue] may turn true by itself: an interval mode's next rebuild, and the end of a
     * failed one's wait. The caller keeps those still to come.
     */
    fun rebuildWakes(mode: AutoRebuild, rebuiltAt: Long?, triedAt: Long?): List<Long> {
        if (mode == AutoRebuild.MANUAL) return emptyList()
        val interval = hoursOf(mode).takeIf { it > 0 }?.let { h -> rebuiltAt?.plus(h * HOUR_MS) }
        return listOfNotNull(interval, failedAt(rebuiltAt, triedAt)?.plus(REBUILD_RETRY_MS))
    }

    /** When the last rebuild started, if it has not succeeded since (null: the last one succeeded, or none ran). */
    private fun failedAt(rebuiltAt: Long?, triedAt: Long?): Long? = triedAt?.takeIf { rebuiltAt == null || it > rebuiltAt }

    /** Each feed something on screen needs, with the earliest time any of them wants it. */
    private fun wakes(demands: Set<Demand>, followed: Set<String>, feeds: Map<Feed, FeedState>, now: Long, liveMs: Long): Map<Feed, Long> {
        val out = HashMap<Feed, Long>()
        fun want(feed: Feed, want: Want) {
            val wake = wakeAt(feeds[feed], want, now) ?: return
            out[feed] = minOf(out[feed] ?: wake, wake)
        }
        /** A league's scoreboard: at the live setting while live, else at its next kick-off. */
        fun league(key: String) {
            val known = feeds[Feed.Scoreboard(key)]?.matches.orEmpty() + feeds[Feed.Scoreboard(key, tomorrow = true)]?.matches.orEmpty()
            when {
                isLive(known, now) -> want(Feed.Scoreboard(key), Want.Every(liveMs))
                else -> nextKickoff(known, now)?.let { want(Feed.Scoreboard(key), Want.At(it)) }
            }
        }

        /** A league's table, once, where it has one: a cup's is empty every time. */
        fun table(key: String) {
            if (SportsCatalog.league(key)?.let(SportsWindow::hasTable) == true) want(Feed.Standings(key), Want.Once)
        }

        demands.forEach { d ->
            when (d) {
                Demand.Hub, Demand.HomeRow, Demand.Calendar, Demand.Leagues -> followed.forEach(::league)
                is Demand.LeaguePage -> {
                    league(d.leagueKey)
                    table(d.leagueKey)
                    SportsCatalog.league(d.leagueKey)?.takeIf(SportsWindow::hasRanking)?.let { want(Feed.Rankings(d.leagueKey), Want.Once) }
                }
                is Demand.TeamPage -> {
                    league(d.leagueKey)
                    table(d.leagueKey)
                }
                is Demand.MatchPage -> {
                    val summary = Feed.Summary(d.match.key)
                    val known = feeds[summary]?.matches?.firstOrNull() ?: d.match
                    want(summary, if (isLive(listOf(known), now)) Want.Every(liveMs) else Want.Once)
                }
                is Demand.PlayerScore -> {
                    val board = Feed.Scoreboard(d.match.feed)
                    val known = feeds[board]?.matches?.firstOrNull { it.key == d.match.key } ?: d.match
                    when {
                        isLive(listOf(known), now) -> want(board, Want.Every(liveMs))
                        known.state == MatchState.PRE && known.startMs > now -> want(board, Want.At(known.startMs))
                        else -> want(board, Want.Once)
                    }
                }
            }
        }
        return out
    }

    /** How often, or when, a feed is wanted. */
    private sealed interface Want {
        data class Every(val ms: Long) : Want
        data class At(val time: Long) : Want
        data object Once : Want
    }

    /** When [state]'s feed is next due for [want] (null: not again). */
    private fun wakeAt(state: FeedState?, want: Want, now: Long): Long? {
        val tried = state?.attemptedAt
        val wake = when {
            tried != null && state.failures > 0 -> tried + BACKOFF_MS[minOf(state.failures, BACKOFF_MS.size) - 1]
            want is Want.At -> want.time
            tried == null -> now
            want is Want.Every -> (state.fetchedAt ?: tried) + want.ms
            else -> return null
        }
        return if (tried == null) wake else maxOf(wake, tried + MIN_AGE_MS)
    }

    companion object {
        const val DEFAULT_LIVE_SECONDS = 30
        val LIVE_SECONDS_CHOICES = listOf(30, 60, 90, 120)

        const val MIN_AGE_MS = 10_000L
        const val PAUSE_MS = 15 * 60_000L
        val BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

        /** A failed rebuild is not tried again by itself sooner than this. */
        const val REBUILD_RETRY_MS = 5 * 60_000L
        private const val HOUR_MS = 60 * 60_000L

        /** A kick-off this long ago with the match still listed as not started is treated as live: ESPN is late. */
        const val LATE_START_MS = 3 * 60 * 60_000L

        /** The live setting, held to its choices. */
        fun liveSeconds(setting: Int): Int = setting.takeIf { it in LIVE_SECONDS_CHOICES } ?: DEFAULT_LIVE_SECONDS

        /** An interval mode's hours; 0 for the two that are not on a clock. */
        fun hoursOf(mode: AutoRebuild): Int = when (mode) {
            AutoRebuild.H1 -> 1
            AutoRebuild.H2 -> 2
            AutoRebuild.H4 -> 4
            AutoRebuild.H8 -> 8
            AutoRebuild.APP_START, AutoRebuild.MANUAL -> 0
        }

        /** Live, or past its kick-off and not yet updated by ESPN. */
        fun isLive(matches: List<Match>, now: Long): Boolean = matches.any {
            it.state == MatchState.IN || (it.state == MatchState.PRE && it.startMs <= now && now - it.startMs < LATE_START_MS)
        }

        /** The next kick-off still to come, if any. */
        fun nextKickoff(matches: List<Match>, now: Long): Long? =
            matches.filter { it.state == MatchState.PRE && it.startMs > now }.minOfOrNull { it.startMs }
    }
}
