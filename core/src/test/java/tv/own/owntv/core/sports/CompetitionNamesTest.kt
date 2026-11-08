package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class CompetitionNamesTest {

    private fun match(sport: Sport, slug: String, espnName: String, sides: List<Team> = emptyList(), broadcasts: List<String> = emptyList()) = Match(
        id = "1", sport = sport, feed = League.keyOf(sport, slug), leagueSlug = slug, leagueName = espnName,
        startMs = 0, state = MatchState.PRE, statusText = "", title = "",
        competitors = sides.mapIndexed { i, t -> Competitor(t, home = i == 0, winner = null, score = null) },
        broadcasts = broadcasts.map { Broadcast(it) },
    )

    private val f1 = WatchMatcher.Keys(match(Sport.RACING, "f1", "Formula 1"))
    private val epl = WatchMatcher.Keys(match(Sport.SOCCER, "eng.1", "English Premier League"))
    private val laLiga = WatchMatcher.Keys(match(Sport.SOCCER, "esp.1", "Spanish LaLiga"))
    private val bundesliga = WatchMatcher.Keys(match(Sport.SOCCER, "ger.1", "German Bundesliga"))
    private val ucl = WatchMatcher.Keys(match(Sport.SOCCER, "uefa.champions", "UEFA Champions League"))

    private fun inTitle(keys: WatchMatcher.Keys, title: String) = keys.league.foundIn(CompetitionNames.words(title))
    private fun likely(keys: WatchMatcher.Keys, channel: String) = WatchMatcher.isLikely(keys, channel)

    @Test fun `a competition is found by every name it goes by`() {
        assertTrue(likely(f1, "UK| SKY SPORTS F1 UHD 3840P"))
        assertTrue(likely(f1, "DE| SKY SPORT F1"))
        assertTrue(likely(f1, "FORMULA 1 CHANNEL (F1TV)"))
        assertTrue(inTitle(f1, "Formel 1: Großer Preis von Brasilien – Rennen"))
        assertTrue(inTitle(f1, "Formula One: Las Vegas Grand Prix"))
        assertTrue(likely(epl, "UK| SKY SPORTS PREMIER LEAGUE FHD"))
        assertTrue(likely(epl, "US| EPL 3"))
        assertTrue(likely(laLiga, "ES| M+ LA LIGA HD"))
        assertTrue(likely(laLiga, "ES| DAZN LALIGA 2"))
        assertTrue(likely(laLiga, "ES| LaLiga TV Bar"))
        assertTrue(inTitle(ucl, "Ligue des Champions : PSG - Bayern"))
    }

    @Test fun `numbers tell competitions apart`() {
        assertFalse(likely(f1, "UK| FORMULA E"))
        assertFalse(inTitle(f1, "Formula 2: Feature Race"))
        assertFalse(inTitle(f1, "Formula E: Berlin E-Prix"))
        assertFalse(inTitle(bundesliga, "2. Bundesliga: Hertha – Schalke"))
        val ligue1 = WatchMatcher.Keys(match(Sport.SOCCER, "fra.1", "French Ligue 1"))
        assertFalse(inTitle(ligue1, "Ligue 2 : Bastia – Metz"))
        val top14 = WatchMatcher.Keys(match(Sport.RUGBY, "270559", "Top 14"))
        assertFalse(inTitle(top14, "Top Gear"))
        assertTrue(inTitle(top14, "Rugby Top 14 : Toulouse – Clermont"))
    }

    @Test fun `a name inside a longer competition does not count`() {
        assertFalse(inTitle(epl, "Indian Premier League: Mumbai v Chennai"))
        assertFalse(inTitle(epl, "Premier League Darts"))
        assertFalse(inTitle(ucl, "CAF Champions League: Al Ahly v Wydad"))
        assertFalse(inTitle(ucl, "EHF Champions League: Kiel – Veszprém"))
        assertFalse(inTitle(bundesliga, "Handball-Bundesliga: THW Kiel – Füchse"))
        assertFalse(likely(laLiga, "ES| LALIGA HYPERMOTION 1"))
        assertFalse(inTitle(f1, "F1 Academy: Race 1"))
        // A channel numbering its feeds is still the competition's channel.
        assertTrue(likely(bundesliga, "DE| SKY SPORT BUNDESLIGA 2"))
    }

    @Test fun `a broadcaster's channel counts, a channel that only shares its name does not`() {
        val withApple = WatchMatcher.Keys(match(Sport.RACING, "f1", "Formula 1", broadcasts = listOf("Apple TV")))
        assertTrue(likely(withApple, "US| APPLE TV+ HD"))
        assertTrue(likely(withApple, "US: APPLE TV F1 PPV 20"))
        assertFalse(likely(withApple, "UK| APPLE TV+ SERIES 1"))
        assertFalse(likely(withApple, "UK| APPLE TV+ SERIES INFO"))
    }

    @Test fun `a finished race is not matched by its podium`() {
        val norris = Team("1", "Lando Norris", "L. Norris", isAthlete = true)
        val max = Team("2", "Max Verstappen", "M. Verstappen", isAthlete = true)
        val race = WatchMatcher.Keys(match(Sport.RACING, "f1", "Formula 1", sides = listOf(norris, max)))
        assertEquals(null, race.home)
        assertEquals(emptyList<String>(), race.searchWords)
        assertEquals(WatchMatcher.Hit.CONFIRMED, WatchMatcher.hit(race, emptySet(), CompetitionNames.words("F1: Großer Preis von São Paulo")))
    }
}
