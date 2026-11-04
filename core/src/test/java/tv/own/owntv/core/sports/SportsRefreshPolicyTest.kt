package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.SportsRefreshPolicy.Companion.BACKOFF_MS
import tv.own.owntv.core.sports.SportsRefreshPolicy.Companion.PAUSE_MS
import tv.own.owntv.core.sports.SportsRefreshPolicy.Companion.REBUILD_RETRY_MS
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport

/** One test per row of the policy's table, then the rebuild schedule. */
class SportsRefreshPolicyTest {

    private val policy = SportsRefreshPolicy()
    private val t0 = 1_000_000_000L
    private val hour = 60 * 60_000L
    private val bl = "soccer/ger.1"
    private val pl = "soccer/eng.1"

    private fun match(id: String, state: MatchState, start: Long = t0, feed: String = bl) =
        Match(id, Sport.SOCCER, feed, feed.substringAfter('/'), "League", start, state, "", "A v B")

    private fun feeds(vararg p: Pair<Feed, FeedState>): Map<Feed, FeedState> = mapOf(*p)

    private fun fetched(at: Long, vararg m: Match) = FeedState(fetchedAt = at, attemptedAt = at, matches = m.toList())

    // --- live refresh ---

    @Test
    fun `hub, a live match - only that league, at the live setting, the quiet one never`() {
        val feeds = feeds(
            Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN)),
            Feed.Scoreboard(pl) to fetched(t0, match("2", MatchState.POST, feed = pl)),
        )
        val at29 = policy.plan(setOf(Demand.Hub), setOf(bl, pl), feeds, t0 + 29_000)
        assertEquals(emptySet<Feed>(), at29.fetch)
        assertEquals(t0 + 30_000, at29.nextWakeAt)
        assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.Hub), setOf(bl, pl), feeds, t0 + 30_000).fetch)
        assertEquals("a finished league is never polled", setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.Hub), setOf(bl, pl), feeds, t0 + 10 * hour).fetch)
    }

    @Test
    fun `the live setting is 30, 60, 90 or 120 s, anything else means 30`() {
        val feeds = feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN)))
        fun wake(seconds: Int) = policy.plan(setOf(Demand.Hub), setOf(bl), feeds, t0 + 1, liveRefreshSeconds = seconds).nextWakeAt
        assertEquals(t0 + 30_000, wake(30))
        assertEquals(t0 + 60_000, wake(60))
        assertEquals(t0 + 90_000, wake(90))
        assertEquals(t0 + 120_000, wake(120))
        assertEquals(t0 + 30_000, wake(15))
        assertEquals(t0 + 30_000, wake(5))
    }

    @Test
    fun `hub, nothing live - no fetch, woken at the next kick-off, tomorrow's board included`() {
        val kickoff = t0 + 4 * 60_000
        val feeds = feeds(
            Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.POST, start = t0 - hour)),
            Feed.Scoreboard(bl, true) to fetched(t0, match("2", MatchState.PRE, start = kickoff)),
        )
        val before = policy.plan(setOf(Demand.Hub), setOf(bl), feeds, t0 + 60_000)
        assertEquals(emptySet<Feed>(), before.fetch)
        assertEquals(kickoff, before.nextWakeAt)
        // At kick-off the copy still says "not started": that counts as live, so today's board is fetched now.
        assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.Hub), setOf(bl), feeds, kickoff).fetch)
    }

    @Test
    fun `hub - a league the board does not hold is the rebuild's job, never polled`() {
        val plan = policy.plan(setOf(Demand.Hub), setOf(bl), emptyMap(), t0)
        assertEquals(emptySet<Feed>(), plan.fetch)
        assertNull(plan.nextWakeAt)
        assertTrue("tomorrow's board is never polled", policy.plan(setOf(Demand.Hub), setOf(bl), feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN))), t0 + hour)
            .fetch.none { it is Feed.Scoreboard && it.tomorrow })
    }

    @Test
    fun `match page - the summary at the live setting while live, once when the board has none, never when it has`() {
        val live = match("1", MatchState.IN)
        val summary = Feed.Summary(live.key)
        assertEquals(setOf(summary), policy.plan(setOf(Demand.MatchPage(live)), emptySet(), emptyMap(), t0).fetch)
        assertEquals(t0 + 30_000, policy.plan(setOf(Demand.MatchPage(live)), emptySet(), feeds(summary to fetched(t0, live)), t0 + 1).nextWakeAt)

        val later = match("1", MatchState.PRE, start = t0 + 5 * hour)
        assertEquals("not live, not stored: fetched once", setOf(summary), policy.plan(setOf(Demand.MatchPage(later)), emptySet(), emptyMap(), t0).fetch)
        val stored = policy.plan(setOf(Demand.MatchPage(later)), emptySet(), feeds(summary to fetched(t0 - hour, later)), t0)
        assertEquals("the rebuild stored it: nothing to fetch", emptySet<Feed>(), stored.fetch)
        assertNull(stored.nextWakeAt)

        val done = feeds(summary to fetched(t0, match("1", MatchState.POST)))
        assertEquals("the fetched copy says it is over: no more fetches", emptySet<Feed>(), policy.plan(setOf(Demand.MatchPage(live)), emptySet(), done, t0 + 10 * hour).fetch)
    }

    @Test
    fun `league page - its live matches as the hub, its table only when the board has none`() {
        val feeds = feeds(Feed.Scoreboard(pl) to fetched(t0, match("2", MatchState.IN, feed = pl)), Feed.Standings(pl) to fetched(t0))
        val plan = policy.plan(setOf(Demand.LeaguePage(pl)), emptySet(), feeds, t0 + 1)
        assertEquals(emptySet<Feed>(), plan.fetch)
        assertEquals(t0 + 30_000, plan.nextWakeAt)
        assertEquals(setOf(Feed.Standings(pl)), policy.plan(setOf(Demand.LeaguePage(pl)), emptySet(), emptyMap(), t0).fetch)
    }

    @Test
    fun `league page - a ranking once for racing and tennis, nothing for a cup's empty table`() {
        assertEquals(setOf(Feed.Rankings("racing/f1")), policy.plan(setOf(Demand.LeaguePage("racing/f1")), emptySet(), emptyMap(), t0).fetch)
        assertEquals(setOf(Feed.Rankings("racing/irl")), policy.plan(setOf(Demand.LeaguePage("racing/irl")), emptySet(), emptyMap(), t0).fetch)
        assertEquals(setOf(Feed.Rankings("tennis/atp")), policy.plan(setOf(Demand.LeaguePage("tennis/atp")), emptySet(), emptyMap(), t0).fetch)
        assertEquals(emptySet<Feed>(), policy.plan(setOf(Demand.LeaguePage("soccer/eng.fa")), emptySet(), emptyMap(), t0).fetch)
        val had = feeds(Feed.Rankings("racing/f1") to fetched(t0))
        assertEquals("not again once the board has it", emptySet<Feed>(), policy.plan(setOf(Demand.LeaguePage("racing/f1")), emptySet(), had, t0 + hour).fetch)
    }

    @Test
    fun `calendar and leagues tab - as the hub`() {
        val feeds = feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN)), Feed.Scoreboard(pl) to fetched(t0, match("2", MatchState.POST, feed = pl)))
        listOf(Demand.Calendar, Demand.Leagues).forEach { d ->
            assertEquals(policy.plan(setOf(Demand.Hub), setOf(bl, pl), feeds, t0 + 30_000), policy.plan(setOf(d), setOf(bl, pl), feeds, t0 + 30_000))
            assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(d), setOf(bl, pl), feeds, t0 + 30_000).fetch)
        }
    }

    @Test
    fun `team page - its league live, and the table once`() {
        assertEquals(setOf(Feed.Standings(pl)), policy.plan(setOf(Demand.TeamPage(pl)), emptySet(), feeds(Feed.Scoreboard(pl) to fetched(t0)), t0 + 1).fetch)
        val live = feeds(Feed.Scoreboard(pl) to fetched(t0, match("2", MatchState.IN, feed = pl)), Feed.Standings(pl) to fetched(t0))
        assertEquals(setOf(Feed.Scoreboard(pl)), policy.plan(setOf(Demand.TeamPage(pl)), emptySet(), live, t0 + 30_000).fetch)
    }

    @Test
    fun `player score - that one league while live, woken at kick-off, nothing after full time`() {
        val m = match("1", MatchState.IN)
        val live = feeds(Feed.Scoreboard(bl) to fetched(t0, m))
        assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.PlayerScore(m)), setOf(bl, pl), live, t0 + 30_000).fetch)

        val soon = match("1", MatchState.PRE, start = t0 + hour)
        assertEquals(t0 + hour, policy.plan(setOf(Demand.PlayerScore(soon)), setOf(bl), feeds(Feed.Scoreboard(bl) to fetched(t0, soon)), t0 + 1).nextWakeAt)

        val over = feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.POST)))
        val plan = policy.plan(setOf(Demand.PlayerScore(m)), setOf(bl, pl), over, t0 + hour)
        assertEquals(emptySet<Feed>(), plan.fetch)
        assertNull(plan.nextWakeAt)
    }

    @Test
    fun `player score follows the feed, not the series - cricket`() {
        val m = Match("9", Sport.CRICKET, "cricket/all", "22804", "T20", t0, MatchState.IN, "", "A v B")
        assertEquals(setOf(Feed.Scoreboard("cricket/all")), policy.plan(setOf(Demand.PlayerScore(m)), emptySet(), emptyMap(), t0).fetch)
    }

    @Test
    fun `home row - as the hub, at the live setting`() {
        val feeds = feeds(
            Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN)),
            Feed.Scoreboard(pl) to fetched(t0, match("2", MatchState.POST, feed = pl)),
        )
        assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.HomeRow), setOf(bl, pl), feeds, t0 + 30_000).fetch)
        assertEquals(emptySet<Feed>(), policy.plan(setOf(Demand.HomeRow), setOf(bl, pl), feeds, t0 + 29_000).fetch)
        assertEquals(t0 + 60_000, policy.plan(setOf(Demand.HomeRow), setOf(bl), feeds, t0 + 1, liveRefreshSeconds = 60).nextWakeAt)
    }

    @Test
    fun `no screen - nothing, never`() {
        val live = feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.IN)))
        val plan = policy.plan(emptySet(), setOf(bl, pl), live, t0 + hour)
        assertEquals(emptySet<Feed>(), plan.fetch)
        assertNull(plan.nextWakeAt)
        assertEquals("an unfollowed league is never requested", emptySet<Feed>(), policy.plan(setOf(Demand.Hub), emptySet(), live, t0 + hour).fetch)
    }

    @Test
    fun `errors back off 30 s, 1, 2, then 5 min`() {
        listOf(1 to BACKOFF_MS[0], 2 to BACKOFF_MS[1], 3 to BACKOFF_MS[2], 4 to BACKOFF_MS[3], 9 to BACKOFF_MS[3]).forEach { (n, wait) ->
            val feeds = feeds(Feed.Scoreboard(bl) to FeedState(fetchedAt = t0 - hour, attemptedAt = t0, failures = n, matches = listOf(match("1", MatchState.IN))))
            assertEquals("after $n failures", t0 + wait, policy.plan(setOf(Demand.Hub), setOf(bl), feeds, t0 + 1).nextWakeAt)
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L), BACKOFF_MS.toList())
    }

    @Test
    fun `403 and 429 pause everything for 15 min`() {
        assertEquals(t0 + PAUSE_MS, policy.pauseAfter(429, t0))
        assertEquals(t0 + PAUSE_MS, policy.pauseAfter(403, t0))
        assertNull(policy.pauseAfter(500, t0))
        val live = feeds(Feed.Scoreboard(bl) to fetched(t0 - hour, match("1", MatchState.IN)))
        val plan = policy.plan(setOf(Demand.Hub), setOf(bl), live, t0 + 1, pausedUntil = t0 + PAUSE_MS)
        assertTrue(plan.paused)
        assertEquals(emptySet<Feed>(), plan.fetch)
        assertEquals(t0 + PAUSE_MS, plan.nextWakeAt)
        assertEquals(setOf(Feed.Scoreboard(bl)), policy.plan(setOf(Demand.Hub), setOf(bl), live, t0 + PAUSE_MS, pausedUntil = t0 + PAUSE_MS).fetch)
    }

    @Test
    fun `a copy younger than 10 s is reused, whatever asks`() {
        // A kick-off one second after a fetch must still wait 10 s.
        val kick = feeds(Feed.Scoreboard(bl) to fetched(t0, match("1", MatchState.PRE, start = t0 + 1_000)))
        assertEquals(t0 + 10_000, policy.plan(setOf(Demand.Hub), setOf(bl), kick, t0 + 500).nextWakeAt)
        assertTrue(policy.plan(setOf(Demand.Hub), setOf(bl), kick, t0 + 5_000).fetch.isEmpty())
    }

    // --- the rebuild schedule ---

    private fun due(
        mode: AutoRebuild,
        rebuiltAt: Long? = t0,
        builtFor: Set<String>? = setOf(bl),
        triedAt: Long? = null,
        thisRun: Boolean = true,
        now: Long = t0 + 1,
        followed: Set<String> = setOf(bl),
    ) = policy.rebuildDue(mode, followed, rebuiltAt, builtFor, triedAt, thisRun, now)

    @Test
    fun `interval modes rebuild when the last rebuild is older than their hours`() {
        listOf(AutoRebuild.H1 to 1, AutoRebuild.H2 to 2, AutoRebuild.H4 to 4, AutoRebuild.H8 to 8).forEach { (mode, h) ->
            assertFalse("$mode before", due(mode, now = t0 + h * hour - 1))
            assertTrue("$mode at", due(mode, now = t0 + h * hour))
        }
    }

    @Test
    fun `app start rebuilds once per run, manual never by itself`() {
        assertTrue(due(AutoRebuild.APP_START, thisRun = false))
        assertFalse(due(AutoRebuild.APP_START, thisRun = true, now = t0 + 100 * hour))
        assertFalse(due(AutoRebuild.MANUAL, rebuiltAt = null, builtFor = null))
        assertFalse("not even for a changed follow list", due(AutoRebuild.MANUAL, followed = setOf(pl)))
    }

    @Test
    fun `never built, or built for another follow list, is due`() {
        assertTrue(due(AutoRebuild.H4, rebuiltAt = null, builtFor = null))
        assertTrue(due(AutoRebuild.H4, followed = setOf(bl, pl)))
        assertTrue(due(AutoRebuild.APP_START, followed = emptySet()))
    }

    @Test
    fun `a failed rebuild waits 5 minutes before the next one by itself`() {
        assertFalse(due(AutoRebuild.H1, rebuiltAt = null, triedAt = t0, now = t0 + REBUILD_RETRY_MS - 1))
        assertTrue(due(AutoRebuild.H1, rebuiltAt = null, triedAt = t0, now = t0 + REBUILD_RETRY_MS))
        assertEquals(listOf(t0 + 4 * hour, t0 + hour + REBUILD_RETRY_MS), policy.rebuildWakes(AutoRebuild.H4, t0, t0 + hour))
        assertEquals("a rebuild that succeeded has no wait", listOf(t0 + 4 * hour), policy.rebuildWakes(AutoRebuild.H4, t0, t0))
        assertTrue("so a new follow list right after it is due at once", due(AutoRebuild.H4, triedAt = t0, now = t0 + 1, followed = setOf(bl, pl)))
        assertEquals(emptyList<Long>(), policy.rebuildWakes(AutoRebuild.MANUAL, t0, t0))
        assertEquals("app start: only the retry", listOf(t0 + REBUILD_RETRY_MS), policy.rebuildWakes(AutoRebuild.APP_START, null, t0))
    }
}
