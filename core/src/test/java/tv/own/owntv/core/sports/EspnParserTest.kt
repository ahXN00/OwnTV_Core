package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.MatchEvent.Kind
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.PeriodScore
import tv.own.owntv.core.sports.model.RaceSession
import tv.own.owntv.core.sports.model.RankingKind
import tv.own.owntv.core.sports.model.Sport
import java.time.Instant

/** Every fixture under `test/resources/sports/` — trimmed real ESPN answers from 2026-10-16. */
class EspnParserTest {

    private val bundesliga = League(Sport.SOCCER, "ger.1", "Bundesliga", country = "DE")

    @Test
    fun `soccer scoreboard - finished, live, scheduled and postponed, malformed skipped`() {
        val matches = EspnParser.scoreboard(bundesliga, fixture("scoreboard_soccer_ger1.json"))
        assertEquals("the malformed event is skipped, the rest parse", 4, matches.size)

        val ft = matches.single { it.id == "401884774" }
        assertEquals(MatchState.POST, ft.state)
        assertEquals("German Bundesliga", ft.leagueName)
        assertEquals("FC Cologne", ft.home!!.team.name)
        assertEquals("Cologne", ft.home!!.team.shortName)
        assertEquals("KOE", ft.home!!.team.abbreviation)
        assertEquals(2, ft.home!!.score!!.value)
        assertEquals(1, ft.away!!.score!!.value)
        assertEquals(true, ft.home!!.winner)
        assertEquals(listOf(Kind.YELLOW_CARD, Kind.YELLOW_CARD, Kind.GOAL, Kind.GOAL, Kind.GOAL, Kind.YELLOW_CARD, Kind.YELLOW_CARD), ft.events.map { it.kind })
        assertEquals("26'", ft.events[2].minute)
        assertEquals(listOf("Ísak Jóhannesson"), ft.events[2].players)
        assertEquals("FT", ft.statusText)
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/122.png&w=96&h=96",
            ft.home!!.team.logoUrl,
        )

        val live = matches.single { it.id == "900000001" }
        assertEquals(MatchState.IN, live.state)
        assertTrue(live.isLive)
        assertEquals("67'", live.clock!!.display)
        assertEquals(2, live.clock.period)

        val pre = matches.single { it.id == "401884770" }
        assertEquals(MatchState.PRE, pre.state)
        assertEquals(Instant.parse("2026-10-16T18:30:00Z").toEpochMilli(), pre.startMs)
        assertEquals(listOf("Peacock"), pre.broadcasts.map { it.name }.take(1))

        assertEquals(MatchState.POSTPONED, matches.single { it.id == "900000002" }.state)
        assertEquals("soccer/ger.1#401884774", ft.key)
    }

    @Test
    fun `month scoreboard - every match of the month, and the season's start`() {
        val body = fixture("scoreboard_month_soccer_ger1.json")
        val matches = EspnParser.scoreboard(bundesliga, body)
        assertEquals(listOf("401884781", "401884754"), matches.map { it.id })
        assertEquals(MatchState.POST, matches[0].state)
        assertEquals(MatchState.PRE, matches[1].state)
        assertEquals(Instant.parse("2026-10-31T17:30:00Z").toEpochMilli(), matches[1].startMs)
        assertEquals(Instant.parse("2026-07-01T04:00:00Z").toEpochMilli(), EspnParser.seasonStart(body))
        assertNull(EspnParser.seasonStart(fixture("header_cricket.json")))
    }

    @Test
    fun `f1 standings - drivers with their country, then constructors`() {
        val r = EspnParser.racingStandings(League(Sport.RACING, "f1", "Formula 1"), fixture("standings_racing_f1.json"))
        assertEquals(listOf(RankingKind.DRIVERS, RankingKind.CONSTRUCTORS), r.groups.map { it.kind })
        val leader = r.groups[0].rows[0]
        assertEquals(1, leader.rank)
        assertEquals("Kimi Antonelli", leader.team.name)
        assertEquals("Italy", leader.team.country)
        assertTrue(leader.team.isAthlete)
        assertEquals(340, leader.points)
        assertEquals(listOf("Mercedes", "Ferrari", "McLaren"), r.groups[1].rows.map { it.team.name })
        assertEquals(578, r.groups[1].rows[0].points)
    }

    @Test
    fun `atp rankings - rank, player, country and points`() {
        val r = EspnParser.tennisRankings(League(Sport.TENNIS, "atp", "ATP"), fixture("rankings_tennis_atp.json"))
        val rows = r.groups.single().rows
        assertEquals(RankingKind.PLAYERS, r.groups.single().kind)
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
        assertEquals("Jannik Sinner", rows[0].team.name)
        assertEquals("Italy", rows[0].team.country)
        assertEquals(11000, rows[0].points)
    }

    @Test
    fun `team schedule - fixtures and results, each match in its own competition`() {
        val fixtures = EspnParser.teamSchedule(Sport.SOCCER, fixture("schedule_soccer_132_fixtures.json"))
        assertEquals(listOf("401884759", "401884754"), fixtures.map { it.id })
        assertTrue(fixtures.all { it.state == MatchState.PRE && it.feed == "soccer/ger.1" })

        val results = EspnParser.teamSchedule(Sport.SOCCER, fixture("schedule_soccer_132_results.json"))
        val leipzig = results.single { it.id == "401884772" }
        assertEquals(MatchState.POST, leipzig.state)
        assertEquals("6", leipzig.home!!.score!!.text)
        assertEquals(6, leipzig.home!!.score!!.value)
        assertEquals(1, leipzig.away!!.score!!.value)
        val europe = results.single { it.id == "401915411" }
        assertEquals("uefa.champions", europe.leagueSlug)
        assertEquals("soccer/uefa.champions#401915411", europe.key)
    }

    @Test
    fun `racing sessions name their kind`() {
        assertEquals(RaceSession.PRACTICE_2, RaceSession.of("FP2"))
        assertEquals(RaceSession.SPRINT_QUALIFYING, RaceSession.of("SS"))
        assertEquals(RaceSession.SPRINT, RaceSession.of("SR"))
        assertEquals(RaceSession.QUALIFYING, RaceSession.of("Qual"))
        assertEquals(RaceSession.RACE, RaceSession.of("Race"))
        assertNull(RaceSession.of("XYZ"))
    }

    @Test
    fun `nba - quarters and points`() {
        val matches = EspnParser.scoreboard(League(Sport.BASKETBALL, "nba", "NBA"), fixture("scoreboard_basketball_nba.json"))
        assertEquals(2, matches.size)
        val knicks = matches.first()
        assertEquals("New York Knicks", knicks.home!!.team.name)
        assertEquals(122, knicks.home!!.score!!.value)
        assertEquals(120, knicks.away!!.score!!.value)
        assertEquals(listOf(35, 28, 24, 35), knicks.home!!.score!!.periods.map { it.value })
        assertTrue(matches.all { it.state == MatchState.POST })
    }

    @Test
    fun `nfl - national broadcasters`() {
        val matches = EspnParser.scoreboard(League(Sport.FOOTBALL, "nfl", "NFL"), fixture("scoreboard_football_nfl.json"))
        assertEquals(3, matches.size)
        assertEquals(listOf("Prime Video"), matches[0].broadcasts.map { it.name })
        assertEquals("national", matches[0].broadcasts[0].market)
        assertEquals(MatchState.POST, matches[0].state)
        assertEquals(MatchState.PRE, matches[1].state)
    }

    @Test
    fun `f1 - every session is an entry, with no competitors yet`() {
        val sessions = EspnParser.scoreboard(League(Sport.RACING, "f1", "Formula 1"), fixture("scoreboard_racing_f1.json"))
        assertEquals(listOf("FP1", "FP2", "FP3", "Qual", "Race"), sessions.map { it.subtitle })
        assertEquals(5, sessions.map { it.id }.toSet().size)
        assertTrue(sessions.all { it.competitors.isEmpty() && it.state == MatchState.PRE })
        assertEquals("MSC Cruises United States Grand Prix", sessions.last().title)
        assertNull(sessions.last().home)
    }

    @Test
    fun `tennis - sets with tie-breaks, a live match, a retirement`() {
        val matches = EspnParser.scoreboard(League(Sport.TENNIS, "atp", "ATP"), fixture("scoreboard_tennis_atp.json"))
        assertEquals(4, matches.size)
        assertTrue(matches.all { it.leagueName == "Rolex Shanghai Masters" })

        val tb = matches[0]
        assertEquals(MatchState.POST, tb.state)
        assertEquals("Nikoloz Basilashvili v Andre Ilagan", tb.title)
        assertEquals("Men's Singles · Qualifying 1st Round", tb.subtitle)
        val p1 = tb.competitors[0]
        assertTrue(p1.team.isAthlete)
        assertEquals("Georgia", p1.team.country)
        assertEquals(true, p1.winner)
        assertEquals(listOf(PeriodScore(6), PeriodScore(6, 1), PeriodScore(7)), p1.score!!.periods)
        assertEquals("6 6 7", p1.score.text)

        val live = matches.single { it.state == MatchState.IN }
        assertEquals(2, live.clock!!.period)
        assertNull("no winner while it is being played", live.competitors[0].winner)

        assertEquals(3, matches.count { it.state == MatchState.POST || it.state == MatchState.IN })
        assertEquals(MatchState.PRE, matches.last().state)
    }

    @Test
    fun `mma - one card, headed by its main event`() {
        val cards = EspnParser.scoreboard(League(Sport.MMA, "ufc", "UFC"), fixture("scoreboard_mma_ufc.json"))
        val card = cards.single()
        assertEquals("UFC Fight Night: Buckley vs. Malott", card.title)
        assertEquals(listOf("Joaquin Buckley", "Mike Malott"), card.competitors.map { it.team.name })
        assertEquals("Welterweight", card.subtitle)
        assertEquals(MatchState.PRE, card.state)
    }

    @Test
    fun `cricket header - overs, runs, live and finished`() {
        val matches = EspnParser.cricketHeader(League(Sport.CRICKET, "all", "Cricket"), fixture("header_cricket.json"))
        assertEquals(9, matches.size)
        assertTrue(matches.all { it.sport == Sport.CRICKET && it.feed == "cricket/all" })
        assertEquals("the series is the league, the feed stays cricket/all", "22804", matches[0].leagueSlug)

        val done = matches[0]
        assertEquals("T20 Spring Challenge", done.leagueName)
        assertEquals(MatchState.POST, done.state)
        assertEquals("14.1", done.clock!!.overs)
        val heat = done.competitors.single { it.team.name == "Brisbane Heat Women" }
        assertEquals("86/4", heat.score!!.text)
        assertEquals(86, heat.score.value)
        assertEquals(true, heat.winner)
        assertEquals("Heat won by 6 wkts (35b rem)", done.statusText)

        assertEquals(4, matches.count { it.state == MatchState.IN })
        assertEquals(4, matches.count { it.state == MatchState.PRE })
        val notStarted = matches.last()
        assertNull("no score before the toss", notStarted.home!!.score)
    }

    @Test
    fun `cricket summary - score and overs read as on the header feed`() {
        val board = EspnParser.cricketHeader(League(Sport.CRICKET, "all", "Cricket"), fixture("header_cricket.json")).first()
        val m = EspnParser.summary(board.copy(id = "1555117", leagueSlug = "10931"), fixture("summary_cricket.json")).match
        assertEquals("87/3", m.competitors.single { it.team.name == "Bangladesh" }.score!!.text)
        assertEquals(87, m.competitors.single { it.team.name == "Bangladesh" }.score!!.value)
        assertEquals("271/8", m.competitors.single { it.team.name == "Zimbabwe" }.score!!.text)
        assertEquals("14", m.clock!!.overs)
        assertEquals(MatchState.IN, m.state)
        assertEquals("Bangladesh require 185 runs", m.statusText)
    }

    @Test
    fun `soccer summary - timeline, stats and line-ups`() {
        val board = EspnParser.scoreboard(bundesliga, fixture("scoreboard_soccer_ger1.json")).single { it.id == "401884774" }
        val summary = EspnParser.summary(board, fixture("summary_soccer_ger1.json"))

        assertEquals(board.title, summary.match.title)
        assertEquals(MatchState.POST, summary.match.state)
        assertEquals(2, summary.match.home!!.score!!.value)

        val kinds = summary.timeline.map { it.kind }
        assertEquals(3, kinds.count { it == Kind.GOAL })
        assertEquals(10, kinds.count { it == Kind.SUBSTITUTION })
        assertEquals(Kind.KICKOFF, kinds.first())
        assertEquals(Kind.FULL_TIME, kinds.last())
        assertTrue(Kind.HALF_TIME in kinds)
        val third = summary.timeline.filter { it.kind == Kind.GOAL }[2]
        assertEquals("67'", third.minute)
        assertEquals(listOf("Said El Mala", "Linton Maina"), third.players)

        val possession = summary.stats.single { it.name == "possessionPct" }
        assertEquals("47.6", possession.home)
        assertEquals(11, summary.stats.size)

        assertEquals(listOf("4-1-2-1-2", "3-4-2-1"), summary.lineups.map { it.formation })
        assertTrue(summary.lineups.all { it.starters.size == 11 && it.bench.size == 9 })
        assertEquals("122", summary.lineups[0].teamId)

        // Each side's last five (ESPN gives no head-to-head): Köln beat Hoffenheim 3-2 at home on 29 Aug.
        assertEquals(setOf("122"), summary.form.keys.filter { it == "122" }.toSet())
        assertEquals(2, summary.form.size)
        val first = summary.form.getValue("122").first()
        assertEquals("W", first.result)
        assertEquals("TSG Hoffenheim", first.opponent)
        assertTrue(first.home)
        assertEquals("3-2", first.score)
        assertEquals(java.time.Instant.parse("2026-08-29T13:30:00Z").toEpochMilli(), first.startMs)
        assertTrue(summary.form.values.all { it.size == 5 })
    }

    @Test
    fun `standings - every row in rank order, with the qualification note`() {
        val table = EspnParser.standings(bundesliga, fixture("standings_soccer_ger1.json"))
        assertEquals("soccer/ger.1", table.leagueKey)
        val rows = table.groups.single().rows
        assertEquals(18, rows.size)
        assertEquals((1..18).toList(), rows.map { it.rank })
        val top = rows.first()
        assertEquals("SC Freiburg", top.team.name)
        assertEquals(13, top.points)
        assertEquals(5, top.played)
        assertEquals(4, top.won)
        assertEquals(1, top.drawn)
        assertEquals(0, top.lost)
        assertEquals("+10", top.difference)
        assertEquals("Champions League", top.note)
    }

    @Test
    fun `teams - every club with its crest`() {
        val teams = EspnParser.teams(fixture("teams_soccer_ger1.json"))
        assertEquals(18, teams.size)
        val union = teams.single { it.id == "598" }
        assertEquals("1. FC Union Berlin", union.name)
        assertEquals("Union Berlin", union.shortName)
        assertEquals("FCU", union.abbreviation)
        assertTrue(union.logoUrl!!.contains("w=96"))
    }

    @Test
    fun `a logo off ESPN's host is passed through, an empty one is null`() {
        assertEquals("https://example.org/x.png", EspnParser.logo("https://example.org/x.png"))
        assertNull(EspnParser.logo(""))
    }
}

internal fun fixture(name: String): String =
    requireNotNull(EspnParserTest::class.java.classLoader!!.getResource("sports/$name")) { "missing fixture $name" }.readText()
