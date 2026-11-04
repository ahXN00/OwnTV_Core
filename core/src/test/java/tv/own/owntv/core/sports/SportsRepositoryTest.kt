package tv.own.owntv.core.sports

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

@OptIn(ExperimentalCoroutinesApi::class)
class SportsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bl = "soccer/ger.1"
    private val pl = "soccer/eng.1"
    private val hour = 60 * 60_000L

    private fun match(id: String, feed: String = bl, state: MatchState = MatchState.IN, start: Long = 0L) =
        Match(id, Sport.SOCCER, feed, feed.substringAfter('/'), "League", start, state, "", "Home v Away")

    /** ESPN, faked: counts calls, can hold them open, can fail. */
    private class FakeSource : SportsSource {
        val calls = mutableListOf<String>()
        var running = 0
        var maxRunning = 0
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null
        var boards: (League, LocalDate?) -> List<Match> = { _, _ -> emptyList() }

        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> =
            call(league.key + if (day != null) "+1" else "") { boards(league, day) }

        override suspend fun summary(match: Match) = call("summary:${match.key}") { MatchSummary(match, emptyList(), emptyList(), emptyList()) }
        override suspend fun standings(league: League) = call("table:${league.key}") { StandingsTable(league.key, emptyList()) }
        override suspend fun teams(league: League): List<Team> = emptyList()
        override suspend fun month(league: League, month: YearMonth) = MonthBoard(emptyList(), null)
        override suspend fun rankings(league: League) = call("ranking:${league.key}") { Ranking(league.key, emptyList()) }
        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> = emptyList()

        private suspend fun <T> call(name: String, result: () -> T): T {
            calls += name
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                gate?.await()
                failWith?.let { throw it }
                return result()
            } finally {
                running--
            }
        }
    }

    /** A stored board, as a rebuild on this device would have left it. */
    private fun stored(today: Map<String, List<Match>>, followed: Set<String> = setOf(bl), at: Long = 0L): File {
        val dir = tmp.newFolder()
        File(dir, "sports").mkdirs()
        File(dir, "sports/board.json").writeText(SportsCacheJson.write(SportsBoard(today = today, rebuiltAt = at, builtFor = followed)))
        return dir
    }

    private fun TestScope.repo(
        source: SportsSource,
        followed: MutableStateFlow<Set<String>> = MutableStateFlow(setOf(bl)),
        dir: File = tmp.newFolder(),
        mode: AutoRebuild = AutoRebuild.MANUAL,
    ) = SportsRepository(
        source = source,
        policy = SportsRefreshPolicy(),
        followed = followed,
        liveRefreshSeconds = MutableStateFlow(30),
        autoRebuild = MutableStateFlow(mode),
        dataDir = dir,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.open(repo: SportsRepository, demand: Demand = Demand.Hub) =
        backgroundScope.launch { repo.observe(demand).collect {} }

    @Test
    fun `no demand means no network, whatever the rebuild mode`() = runTest {
        val source = FakeSource()
        repo(source, mode = AutoRebuild.H1)
        advanceTimeBy(10 * hour)
        runCurrent()
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `a cold start shows the stored board before the network answers, then keeps its live match fresh`() = runTest {
        advanceTimeBy(hour) // the stored board is an hour old
        val dir = stored(mapOf(bl to listOf(match("cached"))))
        val source = FakeSource().apply { gate = CompletableDeferred() }
        val repo = repo(source, dir = dir)

        open(repo)
        runCurrent()
        assertEquals(listOf("cached"), repo.board.value.today[bl]!!.map { it.id })
        assertTrue("its live league is being refreshed", bl in source.calls)

        source.boards = { _, _ -> listOf(match("cached", state = MatchState.POST)) }
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(MatchState.POST, repo.board.value.today[bl]!!.single().state)
        assertTrue("the update was written back", File(dir, "sports/board.json").readText().contains("POST"))
    }

    @Test
    fun `two screens asking for one live league make one request`() = runTest {
        advanceTimeBy(hour) // the stored board is an hour old
        val source = FakeSource().apply { gate = CompletableDeferred() }
        val repo = repo(source, dir = stored(mapOf(bl to listOf(match("1")))))
        open(repo, Demand.Hub)
        open(repo, Demand.HomeRow)
        open(repo, Demand.PlayerScore(match("1")))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, source.calls.count { it == bl })
    }

    @Test
    fun `at most four fetches run at once`() = runTest {
        val leagues = listOf("soccer/ger.1", "soccer/eng.1", "soccer/esp.1", "soccer/ita.1", "soccer/fra.1", "basketball/nba")
        val source = FakeSource().apply { gate = CompletableDeferred() }
        val repo = repo(source, followed = MutableStateFlow(leagues.toSet()))
        repo.rebuild()
        runCurrent()
        assertEquals(4, source.maxRunning)
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals("twelve scoreboards (today + tomorrow) and six tables", 18, source.calls.size)
        assertEquals(4, source.maxRunning)
    }

    @Test
    fun `a live league is fetched again at the live setting, and polling stops with the last screen`() = runTest {
        advanceTimeBy(hour) // the stored board is an hour old
        val source = FakeSource().apply { boards = { _, d -> if (d == null) listOf(match("1")) else emptyList() } }
        val repo = repo(source, dir = stored(mapOf(bl to listOf(match("1")))))
        val screen = open(repo)
        runCurrent()
        assertEquals(1, source.calls.count { it == bl })
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, source.calls.count { it == bl })

        screen.cancel()
        runCurrent()
        val before = source.calls.size
        advanceTimeBy(hour)
        runCurrent()
        assertEquals("no screen, no requests", before, source.calls.size)
    }

    @Test
    fun `the board is dropped five minutes after the last screen goes`() = runTest {
        val repo = repo(FakeSource(), dir = stored(mapOf(bl to listOf(match("1", state = MatchState.POST)))))
        val screen = open(repo)
        runCurrent()
        assertTrue(repo.board.value.today.isNotEmpty())
        screen.cancel()
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertTrue("still there at 4 min", repo.board.value.today.isNotEmpty())
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(SportsBoard(), repo.board.value)
    }

    @Test
    fun `a match page fetches a summary the board lacks, a league page its missing table`() = runTest {
        val source = FakeSource()
        val repo = repo(source)
        val m = match("7", state = MatchState.POST)
        open(repo, Demand.MatchPage(m))
        open(repo, Demand.LeaguePage(pl))
        runCurrent()
        assertTrue(m.key in repo.board.value.summaries)
        assertTrue(pl in repo.board.value.standings)
        assertEquals(setOf("summary:${m.key}", "table:$pl"), source.calls.toSet())
    }

    @Test
    fun `429 pauses everything for 15 minutes and says so`() = runTest {
        advanceTimeBy(hour) // the stored board is an hour old
        val source = FakeSource().apply { failWith = SportsHttpException(429) }
        val repo = repo(source, dir = stored(mapOf(bl to listOf(match("1")))))
        open(repo)
        runCurrent()
        val tried = source.calls.size
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repo.board.value.paused)
        assertTrue(Feed.Scoreboard(bl) in repo.board.value.failing)
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertEquals("nothing while paused", tried, source.calls.size)

        source.failWith = null
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(source.calls.size > tried)
        assertFalse(repo.board.value.paused)
        assertFalse(Feed.Scoreboard(bl) in repo.board.value.failing)
    }

    // --- auto rebuild ---

    @Test
    fun `app start - rebuilds once in app-start mode, never in manual, and an interval only when due`() = runTest {
        advanceTimeBy(10 * hour)
        val appStart = FakeSource()
        val a = repo(appStart, dir = stored(emptyMap(), at = 9 * hour), mode = AutoRebuild.APP_START)
        a.appStarted()
        runCurrent()
        a.appStarted()
        runCurrent()
        assertEquals("one rebuild: today, tomorrow, the table", setOf(bl, "$bl+1", "table:$bl"), appStart.calls.toSet())
        assertEquals(3, appStart.calls.size)

        val manual = FakeSource()
        repo(manual, dir = stored(emptyMap(), at = 0L), mode = AutoRebuild.MANUAL).appStarted()
        runCurrent()
        assertEquals(emptyList<String>(), manual.calls)

        val fresh = FakeSource()
        repo(fresh, dir = stored(emptyMap(), at = 9 * hour), mode = AutoRebuild.H4).appStarted()
        runCurrent()
        assertEquals("rebuilt an hour ago: not due", emptyList<String>(), fresh.calls)

        val stale = FakeSource()
        repo(stale, dir = stored(emptyMap(), at = 5 * hour), mode = AutoRebuild.H4).appStarted()
        runCurrent()
        assertEquals(3, stale.calls.size)
    }

    @Test
    fun `an open hub rebuilds when the interval comes round, and when the follow list changes`() = runTest {
        advanceTimeBy(10 * hour)
        val source = FakeSource()
        val followed = MutableStateFlow(setOf(bl))
        val repo = repo(source, followed, dir = stored(emptyMap(), at = 10 * hour), mode = AutoRebuild.H1)
        open(repo)
        runCurrent()
        assertEquals("just built: nothing", 0, source.calls.size)

        advanceTimeBy(hour)
        runCurrent()
        assertEquals("an hour on: rebuilt", 3, source.calls.size)

        followed.value = setOf(bl, pl)
        runCurrent()
        assertTrue("a new league: rebuilt for it", pl in source.calls)
        assertEquals(setOf(bl, pl), repo.board.value.builtFor)
    }

    @Test
    fun `a failing rebuild is tried again after 5 minutes, not sooner`() = runTest {
        val source = FakeSource().apply { failWith = SportsHttpException(500) }
        val repo = repo(source, mode = AutoRebuild.H4)
        open(repo)
        runCurrent()
        assertEquals(RebuildState.Failed, repo.rebuild.value)
        val tried = source.calls.size
        advanceTimeBy(SportsRefreshPolicy.REBUILD_RETRY_MS - 1_000)
        runCurrent()
        assertEquals(tried, source.calls.size)
        source.failWith = null
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(source.calls.size > tried)
        assertEquals(RebuildState.Idle, repo.rebuild.value)
    }
}
