package tv.own.owntv.core.sports

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import tv.own.owntv.core.sports.model.RankingGroup
import tv.own.owntv.core.sports.model.RankingKind
import tv.own.owntv.core.sports.model.RankingRow
import tv.own.owntv.core.sports.model.StandingsGroup
import tv.own.owntv.core.sports.model.StandingsRow
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLeaguesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private var now = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli()
    private val hour = 60 * 60_000L
    private val bl = SportsCatalog.league("soccer/ger.1")!!
    private val nhl = SportsCatalog.league("hockey/nhl")!!
    private val olympics = SportsCatalog.league("hockey/olympics-mens-ice-hockey")!!
    private val f1 = SportsCatalog.league("racing/f1")!!

    private fun team(id: String) = Team(id, "Team $id")

    private fun match(id: String, start: Long, state: MatchState = MatchState.PRE, league: League = bl, teams: List<String> = listOf("h", "a")) =
        Match(
            id, league.sport, league.key, league.slug, league.name, start, state, "", "Home v Away",
            competitors = teams.mapIndexed { i, t -> Competitor(team(t), i == 0, null, null) },
        )

    private fun row(rank: Int, id: String, points: Int) = StandingsRow(rank, team(id), 8, 5, 2, 1, 15, 6, "+9", points)

    // --- league cards ---

    @Test
    fun `a card shows the table's leader and the soonest match still to come`() {
        val board = SportsBoard(
            standings = mapOf(bl.key to StandingsTable(bl.key, listOf(StandingsGroup("Bundesliga", listOf(row(1, "fcb", 22), row(2, "b04", 17)))))),
            today = mapOf(bl.key to listOf(match("live", now - hour, MatchState.IN))),
        )
        val month = CalendarMonth(
            YearMonth.of(2026, 10),
            mapOf(LocalDate.of(2026, 10, 3) to listOf(match("old", now - 15 * 24 * hour, MatchState.POST)), LocalDate.of(2026, 10, 24) to listOf(match("next", now + 6 * 24 * hour))),
            null,
            CalendarStatus.Ready,
        )
        val later = month.copy(month = YearMonth.of(2026, 11), days = mapOf(LocalDate.of(2026, 11, 1) to listOf(match("later", now + 14 * 24 * hour))))
        val card = LeagueCards.card(bl, board, listOf(month, later), now)
        assertEquals("fcb", card.leader!!.id)
        assertEquals(22, card.leaderPoints)
        assertEquals("next", card.next!!.id)
    }

    @Test
    fun `where tables rank by wins and losses, the card's leader is the best record in any group`() {
        val nba = SportsCatalog.league("basketball/nba")!!
        fun rec(id: String, won: Int, lost: Int) = StandingsRow(0, team(id), won + lost, won, 0, lost, 0, 0, "", -2)
        // ESPN's basketball groups are not in standings order.
        val board = SportsBoard(
            standings = mapOf(
                nba.key to StandingsTable(
                    nba.key,
                    listOf(StandingsGroup("East", listOf(rec("chi", 1, 5), rec("bos", 4, 1))), StandingsGroup("West", listOf(rec("mem", 5, 0), rec("lal", 2, 3)))),
                ),
            ),
        )
        val card = LeagueCards.card(nba, board, emptyList(), now)
        assertEquals("mem", card.leader!!.id)
    }

    @Test
    fun `a ranked league's card leads with the ranking, an empty month leaves no next match`() {
        val board = SportsBoard(rankings = mapOf(f1.key to Ranking(f1.key, listOf(RankingGroup(RankingKind.DRIVERS, listOf(RankingRow(1, team("ant"), 340)))))))
        val card = LeagueCards.card(f1, board, emptyList(), now)
        assertEquals("ant", card.leader!!.id)
        assertEquals(340, card.leaderPoints)
        assertNull(card.next)
    }

    @Test
    fun `a month's fixtures come soonest first, its results latest first`() {
        val month = CalendarMonth(
            YearMonth.of(2026, 10),
            mapOf(
                LocalDate.of(2026, 10, 3) to listOf(match("r1", now - 15 * 24 * hour, MatchState.POST)),
                LocalDate.of(2026, 10, 10) to listOf(match("r2", now - 8 * 24 * hour, MatchState.POST), match("off", now - 8 * 24 * hour + hour, MatchState.POSTPONED)),
                LocalDate.of(2026, 10, 24) to listOf(match("f1", now + 6 * 24 * hour)),
            ),
            null,
            CalendarStatus.Ready,
        )
        assertEquals(listOf("off", "f1"), month.fixtures().map { it.id })
        assertEquals(listOf("r2", "r1"), month.results().map { it.id })
    }

    // --- team pages ---

    private class Source : SportsSource {
        val calls = mutableListOf<String>()
        var fail = false
        var fixtures: List<Match> = emptyList()
        var results: List<Match> = emptyList()

        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> {
            calls += "${league.key}/$teamId/" + if (fixtures) "fixtures" else "results"
            if (fail) throw SportsHttpException(500)
            return if (fixtures) this.fixtures else results
        }

        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> = emptyList()
        override suspend fun summary(match: Match) = MatchSummary(match, emptyList(), emptyList(), emptyList())
        override suspend fun standings(league: League) = StandingsTable(league.key, emptyList())
        override suspend fun teams(league: League): List<Team> = emptyList()
        override suspend fun month(league: League, month: YearMonth) = MonthBoard(emptyList(), null)
        override suspend fun rankings(league: League) = Ranking(league.key, emptyList())
    }

    private fun TestScope.teams(source: Source, dir: File = tmp.newFolder()): SportsTeams {
        val gate = SportsGate(SportsRefreshPolicy()) { now }
        val calendar = SportsCalendar(
            source, gate, MutableStateFlow(SportsBoard()), MutableStateFlow(emptySet()), MutableStateFlow(7), dir,
            backgroundScope, StandardTestDispatcher(testScheduler), { now }, { zone },
        )
        return SportsTeams(source, gate, calendar, { now }, { zone })
    }

    @Test
    fun `a team page fetches both lists once a day and finds the team's place in the table`() = runTest {
        val source = Source().apply {
            fixtures = listOf(match("f2", now + 48 * hour, teams = listOf("fcb", "x")), match("f1", now + 24 * hour, teams = listOf("y", "fcb")))
            results = listOf(match("r1", now - 48 * hour, MatchState.POST, teams = listOf("fcb", "z")), match("r2", now - 24 * hour, MatchState.POST, teams = listOf("w", "fcb")))
        }
        val board = SportsBoard(standings = mapOf(bl.key to StandingsTable(bl.key, listOf(StandingsGroup("Bundesliga", listOf(row(1, "b04", 20), row(2, "fcb", 19)))))))
        val teams = teams(source)
        val page = teams.schedule(bl, "fcb", board)
        assertEquals(listOf("f1", "f2"), page.fixtures.map { it.id })
        assertEquals(listOf("r2", "r1"), page.results.map { it.id })
        assertEquals(2, page.position)
        assertFalse(page.failed)

        teams.schedule(bl, "fcb", board)
        assertEquals("kept for the day", 2, source.calls.size)
        now += 24 * hour
        teams.schedule(bl, "fcb", board)
        assertEquals("a new day fetches again", 4, source.calls.size)
    }

    @Test
    fun `offline, a team page shows its last copy and says so`() = runTest {
        val source = Source().apply { fixtures = listOf(match("f1", now + 24 * hour)) }
        val teams = teams(source)
        teams.schedule(bl, "h", SportsBoard())
        source.fail = true
        now += 24 * hour
        val page = teams.schedule(bl, "h", SportsBoard())
        assertTrue(page.failed)
        assertEquals(listOf("f1"), page.fixtures.map { it.id })
        assertNull(page.position)
    }

    @Test
    fun `outside football, the team's games in other saved leagues are added`() = runTest {
        val dir = tmp.newFolder()
        File(dir, "sports/calendar").mkdirs()
        val cup = match("oly", now + 72 * hour, league = olympics, teams = listOf("can", "usa"))
        val other = match("else", now + 72 * hour, league = olympics, teams = listOf("swe", "fin"))
        File(dir, "sports/calendar/hockey_olympics-mens-ice-hockey_2026-10.json").writeText(SportsCalendar.write(SavedMonth(listOf(cup, other), fetchedAt = now)))
        val source = Source().apply { fixtures = listOf(match("nhl1", now + 24 * hour, league = nhl, teams = listOf("can", "tor"))) }
        val page = teams(source, dir).schedule(nhl, "can", SportsBoard())
        assertEquals(listOf("nhl1", "oly"), page.fixtures.map { it.id })
    }
}
