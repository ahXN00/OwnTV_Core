package tv.own.owntv.core.sports

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** The rebuild-based board — window rules, replace-not-merge, details and tables, storage. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsRebuildTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bl = "soccer/ger.1"
    private val pl = "soccer/eng.1"
    private val atp = "tennis/atp"
    private val hour = 60 * 60_000L

    /** Now, for every test: far enough from 0 that "16 hours ago" is a real time. */
    private val t0 = 100 * hour

    private fun team(id: String, athlete: Boolean = false) = Team(id, "Team $id", isAthlete = athlete)

    private fun match(id: String, feed: String = bl, state: MatchState = MatchState.PRE, start: Long = t0, athletes: Boolean = false) =
        Match(
            id, Sport.ofPath(feed.substringBefore('/'))!!, feed, feed.substringAfter('/'), "League", start, state, "", "Home v Away",
            competitors = listOf(Competitor(team("h$id", athletes), true, null, null), Competitor(team("a$id", athletes), false, null, null)),
        )

    private class Source : SportsSource {
        val calls = mutableListOf<String>()
        var today: (String) -> List<Match> = { emptyList() }
        var tomorrow: (String) -> List<Match> = { emptyList() }
        var failing: Set<String> = emptySet()

        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
            val name = league.key + if (day != null) "+1" else ""
            calls += name
            if (name in failing || league.key in failing) throw SportsHttpException(500)
            return if (day == null) today(league.key) else tomorrow(league.key)
        }

        override suspend fun summary(match: Match): MatchSummary {
            calls += "summary:${match.id}"
            if ("summary" in failing) throw SportsHttpException(500)
            return MatchSummary(match, emptyList(), emptyList(), emptyList())
        }

        override suspend fun standings(league: League): StandingsTable {
            calls += "table:${league.key}"
            return StandingsTable(league.key, emptyList())
        }

        override suspend fun teams(league: League): List<Team> = emptyList()
        override suspend fun month(league: League, month: YearMonth) = MonthBoard(emptyList(), null)
        override suspend fun rankings(league: League): Ranking {
            calls += "ranking:${league.key}"
            return Ranking(league.key, emptyList())
        }

        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> = emptyList()
    }

    private fun TestScope.repo(source: SportsSource, followed: MutableStateFlow<Set<String>>, dir: File) =
        SportsRepository(
            source = source,
            policy = SportsRefreshPolicy(),
            followed = followed,
            liveRefreshSeconds = MutableStateFlow(30),
            autoRebuild = MutableStateFlow(AutoRebuild.MANUAL),
            dataDir = dir,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )

    private fun TestScope.rebuilt(repo: SportsRepository): SportsBoard {
        repo.rebuild()
        runCurrent()
        return repo.board.value
    }

    private fun SportsBoard.ids() = matches.map { it.id }.toSet()

    @Test
    fun `the window - 36 h ahead, live always, finished for 12 h after its end, postponed and cancelled never`() = runTest {
        advanceTimeBy(t0)
        val source = Source().apply {
            today = {
                listOf(
                    match("live", state = MatchState.IN, start = t0 - 20 * hour), // live: kept however old
                    match("soon", start = t0 + 35 * hour),
                    match("far", start = t0 + 37 * hour),
                    match("off", state = MatchState.POSTPONED),
                    match("void", state = MatchState.CANCELLED),
                    match("ended14", state = MatchState.POST, start = t0 - 14 * hour), // ended 11 h ago
                    match("ended16", state = MatchState.POST, start = t0 - 16 * hour), // ended 13 h ago
                )
            }
        }
        val board = rebuilt(repo(source, MutableStateFlow(setOf(bl)), tmp.newFolder()))
        assertEquals(setOf("live", "soon", "ended14"), board.ids())
        assertEquals(t0, board.rebuiltAt)
    }

    @Test
    fun `a rebuild replaces - a match no longer listed and an unfollowed league are gone, a finished match stays`() = runTest {
        advanceTimeBy(t0)
        val followed = MutableStateFlow(setOf(bl, pl))
        val source = Source().apply {
            today = { k -> if (k == bl) listOf(match("a"), match("done", state = MatchState.POST, start = t0 - 2 * hour)) else listOf(match("p", feed = pl)) }
        }
        val repo = repo(source, followed, tmp.newFolder())
        assertEquals(setOf("a", "done", "p"), rebuilt(repo).ids())

        followed.value = setOf(bl)
        source.today = { listOf(match("b")) } // ESPN's next day: "a" moved away, "done" is yesterday's
        advanceTimeBy(hour)
        val board = rebuilt(repo)
        assertEquals(setOf("b", "done"), board.ids())
        assertFalse(pl in board.today)
    }

    @Test
    fun `details for two-team matches, tables for leagues that have one, a finished match's details reused`() = runTest {
        advanceTimeBy(t0)
        val source = Source().apply {
            today = { k ->
                if (k == atp) listOf(match("t1", feed = atp, athletes = true))
                else listOf(match("m1"), match("m2", state = MatchState.POST, start = t0 - hour))
            }
        }
        val repo = repo(source, MutableStateFlow(setOf(bl, atp)), tmp.newFolder())
        val board = rebuilt(repo)
        assertEquals(setOf(match("m1").key, match("m2").key), board.summaries.keys)
        assertEquals(setOf(bl), board.standings.keys)
        assertFalse("no summary for an athletes' match", "summary:t1" in source.calls)
        assertFalse("no table for tennis", "table:$atp" in source.calls)

        source.calls.clear()
        rebuilt(repo)
        assertTrue("a match still to come is fetched again", "summary:m1" in source.calls)
        assertFalse("a finished match's stored details are reused", "summary:m2" in source.calls)
    }

    @Test
    fun `rankings for racing and the tennis tours, none for a cup, all stored with the board`() = runTest {
        advanceTimeBy(t0)
        val dir = tmp.newFolder()
        val f1 = "racing/f1"
        val cup = "soccer/eng.fa"
        val source = Source()
        val board = rebuilt(repo(source, MutableStateFlow(setOf(bl, atp, f1, cup)), dir))
        assertEquals(setOf(atp, f1), board.rankings.keys)
        assertEquals(setOf("ranking:$atp", "ranking:$f1"), source.calls.filter { it.startsWith("ranking:") }.toSet())
        assertEquals("a table for the league only, never the cup", listOf("table:$bl"), source.calls.filter { it.startsWith("table:") })
        assertTrue("written with the board", File(dir, "sports/board.json").readText().contains("\"rk\""))
        assertEquals(setOf(atp, f1), SportsCacheJson.read(File(dir, "sports/board.json").readText())!!.rankings.keys)
    }

    @Test
    fun `a failed league keeps what it had, all failing leaves the board alone and says so`() = runTest {
        advanceTimeBy(t0)
        val followed = MutableStateFlow(setOf(bl, pl))
        val source = Source().apply { today = { k -> listOf(match(k.substringAfter('/'), feed = k)) } }
        val repo = repo(source, followed, tmp.newFolder())
        rebuilt(repo)

        source.failing = setOf(pl)
        source.today = { k -> listOf(match("new-" + k.substringAfter('/'), feed = k)) }
        val board = rebuilt(repo)
        assertEquals(setOf("new-ger.1", "eng.1"), board.ids())
        assertTrue(Feed.Scoreboard(pl) in board.failing)
        assertEquals(RebuildState.Idle, repo.rebuild.value)

        source.failing = setOf(bl, pl)
        advanceTimeBy(hour)
        val before = repo.board.value
        assertEquals(before, rebuilt(repo))
        assertEquals(RebuildState.Failed, repo.rebuild.value)
    }

    @Test
    fun `progress counts every fetch, and nothing followed builds an empty board`() = runTest {
        advanceTimeBy(t0)
        val source = Source().apply { today = { listOf(match("m")) } }
        val repo = repo(source, MutableStateFlow(setOf(bl)), tmp.newFolder())
        repo.rebuild()
        runCurrent()
        // 2 scoreboards + 1 summary + 1 table, all finished.
        assertEquals(RebuildState.Idle, repo.rebuild.value)
        assertEquals(4, source.calls.size)

        val empty = repo(Source(), MutableStateFlow(emptySet()), tmp.newFolder())
        val board = rebuilt(empty)
        assertTrue(board.matches.isEmpty())
        assertEquals(t0, board.rebuiltAt)
    }

    @Test
    fun `the rebuilt board is stored and read back, details and tables included, pruned to the window`() = runTest {
        advanceTimeBy(t0)
        val dir = tmp.newFolder()
        val source = Source().apply { today = { listOf(match("m"), match("ended", state = MatchState.POST, start = t0 - 2 * hour)) } }
        rebuilt(repo(source, MutableStateFlow(setOf(bl)), dir))
        assertTrue(File(dir, "sports/board.json").isFile)

        advanceTimeBy(13 * hour) // "ended" (over at t0 + 1 h) is now past its 12 hours
        val again = repo(Source(), MutableStateFlow(setOf(bl)), dir)
        backgroundScope.launch { again.observe(Demand.Hub).collect {} }
        runCurrent()
        val board = again.board.value
        assertEquals(setOf("m"), board.ids())
        assertNotNull(board.summaries[match("m").key])
        assertFalse(match("ended").key in board.summaries)
        assertTrue(bl in board.standings)
        assertEquals(t0, board.rebuiltAt)
    }
}
