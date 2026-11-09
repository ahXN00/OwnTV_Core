package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Team

class TeamNamesTest {

    private fun found(team: Team, title: String) = TeamNames.keyOf(team).foundIn(TeamNames.tokens(title).toSet())

    private val bayern = Team("132", "Bayern Munich", "Bayern", "MUN", location = "Munich")
    private val koln = Team("122", "FC Cologne", "Cologne", "KOE", location = "Cologne")
    private val dortmund = Team("124", "Borussia Dortmund", "Dortmund", "DOR", location = "Dortmund")
    private val manUtd = Team("360", "Manchester United", "Man United", "MAN", location = "Manchester")
    private val manCity = Team("382", "Manchester City", "Man City", "MNC", location = "Manchester")
    private val psg = Team("160", "Paris Saint-Germain", "PSG", "PSG", location = "Paris")
    private val atletico = Team("1068", "Atlético Madrid", "Atlético", "ATM", location = "Madrid")
    private val sevilla = Team("243", "Sevilla", "Sevilla", "SEV", location = "Sevilla")
    private val besiktas = Team("1895", "Besiktas", "Besiktas", "BES", location = "Istanbul")
    private val fenerbahce = Team("436", "Fenerbahce", "Fenerbahce", "FEN", location = "Istanbul")
    private val alNassr = Team("817", "Al Nassr", "Al Nassr", "NSR", location = "Riyadh")
    private val alAhly = Team("6981", "Al Ahly", "Al Ahly", "AHL", location = "Cairo")
    private val germany = Team("481", "Germany", "Germany", "GER", location = "Germany")

    @Test fun `accents, club prefixes and numbers are dropped`() {
        assertEquals(listOf("bayern", "munich"), TeamNames.tokens("FC Bayern München"))
        assertEquals(listOf("cologne"), TeamNames.tokens("1. FC Köln"))
        assertEquals(listOf("besiktas"), TeamNames.tokens("Beşiktaş JK").filter { it != "jk" })
    }

    @Test fun `German guide titles`() {
        val title = "Fußball: 1. Bundesliga – FC Bayern München – 1. FC Köln, 7. Spieltag"
        assertTrue(found(bayern, title))
        assertTrue(found(koln, title))
        assertFalse(found(dortmund, title))
        assertTrue(found(bayern, "Live: FC Bayern Muenchen - Borussia Dortmund"))
        assertTrue(found(koln, "1. FC Koeln gegen Bayern"))
    }

    @Test fun `English guide titles keep the two Manchester clubs apart`() {
        val title = "Premier League: Man United v Arsenal (Live)"
        assertTrue(found(manUtd, title))
        assertFalse(found(manCity, title))
        assertFalse(found(manCity, "Live from Manchester: the weekly round-up"))
        assertTrue(found(manCity, "Manchester City vs. Liverpool"))
    }

    @Test fun `French and Spanish guide titles`() {
        assertTrue(found(psg, "Ligue 1 : Paris Saint-Germain / Olympique de Marseille"))
        assertTrue(found(psg, "Football. PSG - OM"))
        assertTrue(found(atletico, "LaLiga EA Sports: Atlético de Madrid - Sevilla FC"))
        assertTrue(found(sevilla, "LaLiga EA Sports: Atlético de Madrid - Sevilla FC"))
        assertTrue(found(sevilla, "Seville v Real Betis"))
    }

    @Test fun `Turkish guide titles`() {
        val title = "Süper Lig: Beşiktaş - Fenerbahçe (Canlı)"
        assertTrue(found(besiktas, title))
        assertTrue(found(fenerbahce, title))
    }

    @Test fun `Arabic transliterations`() {
        assertTrue(found(alNassr, "Saudi Pro League: Al-Nasr vs Al-Hilal"))
        assertTrue(found(alNassr, "AL NASSR X AL ITTIHAD"))
        assertTrue(found(alAhly, "El Ahly - Zamalek | الأهلي"))
    }

    @Test fun `a national team is not dropped as a country word`() {
        assertTrue(found(germany, "WM-Qualifikation: Deutschland - Frankreich"))
        assertTrue(found(germany, "Germany v France"))
    }

    @Test fun `a location alone never matches`() {
        // "Manchester" is the location of both clubs; it is no variant of its own.
        assertFalse(TeamNames.variantsOf(manUtd).contains(setOf("manchester")))
        assertFalse(found(besiktas, "Istanbul derby preview"))
    }
}
