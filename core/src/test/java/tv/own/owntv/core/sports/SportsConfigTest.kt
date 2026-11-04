package tv.own.owntv.core.sports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Sport

/** Settings → Sports: defaults, first-run choices and the import rules. */
class SportsConfigTest {

    private val full = SportsConfig(
        leagues = listOf("soccer/ger.1", "cricket/all"),
        allSports = true,
        sportOrder = listOf(Sport.SOCCER, Sport.CRICKET),
        autoRebuild = AutoRebuild.APP_START,
        teams = listOf(FollowedTeam(Sport.SOCCER, "125", "Eintracht Frankfurt", "SGE"), FollowedTeam(Sport.CRICKET, "6", "India")),
        languages = setOf("DE", "EN"),
        countries = setOf("DE", "AT"),
        minQuality = MinQuality.HD,
        onlyConfirmed = true,
        strictFilter = false,
        sourceIds = setOf(3L, 7L),
        rememberFilters = true,
        spoilerFree = true,
        scoreInInfoBar = false,
        liveRefreshSeconds = 90,
        showLikely = false,
        rememberedChannels = mapOf("soccer/ger.1" to RememberedChannel("Sky Sport Bundesliga 1", "DE")),
        alerts = GoalAlerts(true, true, setOf(AlertKind.GOALS, AlertKind.RED_CARDS), myTeams = false, followedLeagues = true, liveCard = false),
        reminderLeadMinutes = 30,
        calendarRefreshDays = 14,
    )

    @Test
    fun `calendar refresh - 7 days by default, kept through a round-trip, held to the stepper's values`() {
        assertEquals(7, SportsConfig().calendarRefreshDays)
        assertEquals(14, SportsConfig.fromJson(full.toJson().toString()).calendarRefreshDays)
        assertEquals("a blob from before the setting", 7, SportsConfig.fromJson(JSONObject().put("reminderLead", 15).toString()).calendarRefreshDays)
        listOf(0, 8, 31, -1, 999).forEach { v ->
            assertEquals("$v", 7, SportsConfig.fromJson(JSONObject().put("calendarRefreshDays", v).toString()).calendarRefreshDays)
        }
        SportsConfig.CALENDAR_REFRESH_CHOICES.forEach { v ->
            assertEquals(v, SportsConfig.fromJson(JSONObject().put("calendarRefreshDays", v).toString()).calendarRefreshDays)
        }
    }

    @Test
    fun `defaults - the documented values, alerts off`() {
        val d = SportsConfig.fromJson(null)
        assertEquals(SportsConfig(), d)
        assertEquals(emptyList<String>(), d.leagues)
        assertEquals(false, d.allSports)
        assertEquals(AutoRebuild.H4, d.autoRebuild)
        assertEquals(true, d.strictFilter)
        assertEquals(emptySet<Long>(), d.sourceIds)
        assertNull(d.languages)
        assertEquals(MinQuality.ANY, d.minQuality)
        assertEquals(30, d.liveRefreshSeconds)
        assertEquals(10, d.reminderLeadMinutes)
        assertEquals(true, d.scoreInInfoBar)
        assertEquals(true, d.showLikely)
        assertEquals(false, d.spoilerFree)
        assertEquals(false, d.alerts.enabled)
        assertEquals(setOf(AlertKind.GOALS, AlertKind.KICKOFF_FULL_TIME), d.alerts.kinds)
    }

    @Test
    fun `first run - nothing followed, languages the app language`() {
        val d = SportsConfig()
        assertEquals(emptySet<String>(), d.followedLeagues())
        assertEquals(setOf("DE"), d.preferredLanguages("de"))
        assertEquals(setOf("PT"), d.preferredLanguages("pt-BR"))
        assertEquals("follow-system tag with nothing usable: no preference", emptySet<String>(), d.preferredLanguages(""))
        // Once chosen, the choice wins — even an empty one.
        assertEquals(setOf("nba"), SportsConfig(leagues = listOf("nba")).followedLeagues())
        assertEquals(emptySet<String>(), SportsConfig(languages = emptySet()).preferredLanguages("de"))
    }

    @Test
    fun `the user's order ranks sports, then the chosen leagues in the order kept`() {
        val c = SportsConfig(leagues = listOf("soccer/esp.1", "soccer/ger.1"), sportOrder = listOf(Sport.SOCCER, Sport.CRICKET))
        assertEquals(listOf(Sport.SOCCER, Sport.CRICKET, Sport.TENNIS), c.orderSports(listOf(Sport.TENNIS, Sport.CRICKET, Sport.SOCCER)))
        assertEquals(0 to 1, c.rankOf(Sport.SOCCER, "soccer/ger.1"))
        assertEquals(0 to 0, c.rankOf(Sport.SOCCER, "soccer/esp.1"))
        assertTrue("an unordered sport comes after the ordered ones", c.rankOf(Sport.TENNIS, "tennis/atp").first > c.rankOf(Sport.CRICKET, "cricket/all").first)
        assertEquals(listOf("soccer/esp.1", "soccer/ger.1"), SportsConfig.fromJson(c.toJson().toString()).leagues.toList())
    }

    @Test
    fun `all sports fetches every catalogue league, my sports only the chosen ones`() {
        val all = SportsConfig(leagues = listOf("soccer/ger.1"), allSports = true).followedLeagues()
        assertEquals(SportsCatalog.leagues.map { it.key }.toSet(), all)
        assertEquals(setOf("soccer/ger.1"), SportsConfig(leagues = listOf("soccer/ger.1")).followedLeagues())
    }

    @Test
    fun `a blob from before R1 reads with the new defaults`() {
        val old = JSONObject().put("minQuality", "HD").put("liveRefreshSeconds", 15).toString()
        val c = SportsConfig.fromJson(old)
        assertEquals(emptyList<String>(), c.leagues)
        assertEquals(AutoRebuild.H4, c.autoRebuild)
        assertEquals(true, c.strictFilter)
        assertEquals("15 s is no longer a choice", 30, c.liveRefreshSeconds)
        assertEquals(MinQuality.HD, c.minQuality)
    }

    @Test
    fun `every field survives the JSON round trip`() {
        assertEquals(full, SportsConfig.fromJson(full.toJson().toString()))
        assertEquals(SportsConfig(), SportsConfig.fromJson(SportsConfig().toJson().toString()))
    }

    @Test
    fun `out-of-range values fall back, unknown entries are dropped`() {
        val o = JSONObject(full.toJson().toString())
            .put("liveRefreshSeconds", 5)
            .put("reminderLead", 7)
            .put("leagues", org.json.JSONArray(listOf("soccer/ger.1", "soccer/xyz.9", 42)))
            .put("languages", org.json.JSONArray(listOf("de", "ENG", "", "e1")))
            .put("countries", org.json.JSONArray(listOf("at", "DEU")))
            .put("minQuality", "8K")
            .put("autoRebuild", "H3")
            .put("sources", org.json.JSONArray(listOf(4, -1, 0, "x")))
        o.getJSONObject("alerts").put("kinds", org.json.JSONArray(listOf("GOALS", "PENALTIES")))
        o.getJSONArray("teams").put(JSONObject().put("s", "quidditch").put("id", "1")).put(JSONObject().put("s", "soccer"))
        val c = SportsConfig.fromJson(o.toString())
        assertEquals(30, c.liveRefreshSeconds)
        assertEquals(10, c.reminderLeadMinutes)
        assertEquals(listOf("soccer/ger.1"), c.leagues)
        assertEquals(setOf("DE"), c.languages)
        assertEquals(setOf("AT"), c.countries)
        assertEquals(MinQuality.ANY, c.minQuality)
        assertEquals(AutoRebuild.H4, c.autoRebuild)
        assertEquals(setOf(4L), c.sourceIds)
        assertEquals(setOf(AlertKind.GOALS), c.alerts.kinds)
        assertEquals("a team with an unknown sport or no id is dropped", 2, c.teams.size)
        assertEquals(60, SportsConfig.fromJson(JSONObject().put("liveRefreshSeconds", 60).toString()).liveRefreshSeconds)
    }

    @Test
    fun `a broken blob reads as the defaults`() {
        assertEquals(SportsConfig(), SportsConfig.fromJson("{not json"))
        assertEquals(SportsConfig(), SportsConfig.fromJson(""))
    }

    @Test
    fun `a league reorder alone is a change, and survives the save`() {
        val a = SportsConfig(leagues = listOf("soccer/esp.1", "soccer/ger.1"))
        val b = SportsConfig(leagues = listOf("soccer/ger.1", "soccer/esp.1"))
        assertNotEquals(a, b)
        assertEquals(b.leagues, SportsConfig.fromJson(b.toJson().toString()).leagues)
    }
}
