package tv.own.owntv.core.sports

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.FormGame
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Lineup
import tv.own.owntv.core.sports.model.LineupPlayer
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchClock
import tv.own.owntv.core.sports.model.MatchEvent
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.PeriodScore
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.RankingGroup
import tv.own.owntv.core.sports.model.RankingKind
import tv.own.owntv.core.sports.model.RankingRow
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsGroup
import tv.own.owntv.core.sports.model.StandingsRow
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.StatLine
import tv.own.owntv.core.sports.model.Team
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * ESPN's JSON → our slim models. Pure: no network, no Android, so the unit tests run it on recorded
 * responses. Only the fields we show are read; anything else ESPN sends is ignored. One event that
 * does not parse is skipped rather than failing the whole board.
 */
internal object EspnParser {

    /** A scoreboard (`/sports/<sport>/<league>/scoreboard`). Tennis, racing and MMA nest differently. */
    fun scoreboard(league: League, body: String): List<Match> {
        val root = JSONObject(body)
        val leagueName = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotEmpty() } ?: league.name
        val events = root.optJSONArray("events") ?: return emptyList()
        return objects(events).flatMap { e ->
            runCatching {
                when (league.sport) {
                    Sport.TENNIS -> tennisMatches(league, e)
                    Sport.RACING -> racingSessions(league, leagueName, e)
                    Sport.MMA -> listOf(mmaCard(league, leagueName, e))
                    else -> listOf(teamMatch(league.sport, league.key, league.slug, leagueName, e, e.getJSONArray("competitions").getJSONObject(0)))
                }
            }.getOrDefault(emptyList())
        }
    }

    /** When the season a scoreboard belongs to started (`leagues[0].season.startDate`); null when ESPN gives none. */
    fun seasonStart(body: String): Long? =
        JSONObject(body).optJSONArray("leagues")?.optJSONObject(0)?.optJSONObject("season")?.optString("startDate")
            ?.takeIf { it.isNotEmpty() }?.let { runCatching { time(it) }.getOrNull() }

    /** Cricket's header feed (`/apis/v2/scoreboard/header?sport=cricket`): every series' matches of the day. */
    fun cricketHeader(league: League, body: String): List<Match> {
        val sport = JSONObject(body).optJSONArray("sports")?.optJSONObject(0) ?: return emptyList()
        val leagues = sport.optJSONArray("leagues") ?: return emptyList()
        return objects(leagues).flatMap { l ->
            val slug = l.optString("slug").ifEmpty { l.optString("id") }
            val name = l.optString("shortName").ifEmpty { l.optString("name") }
            objects(l.optJSONArray("events")).mapNotNull { e -> runCatching { cricketMatch(league.key, slug, name, e) }.getOrNull() }
        }
    }

    /** A match summary (`/summary?event=`): the timeline, the stats and the line-ups, plus the fresh score. */
    fun summary(match: Match, body: String): MatchSummary {
        val root = JSONObject(body)
        val competition = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        val fresh = competition?.let {
            runCatching { teamMatch(match.sport, match.feed, match.leagueSlug, match.leagueName, JSONObject().put("id", match.id).put("name", match.title), it) }.getOrNull()
        }?.let { m ->
            // The summary's cricket header is football-shaped: a zero-minute clock and the score as ESPN's
            // full text. Read it the way the cricket header feed is read, so both pages show 87/3 and 14 ov.
            if (m.sport != Sport.CRICKET) return@let m
            val raw = m.competitors.map { it.score?.text.orEmpty() }
            val status = competition?.optJSONObject("status")?.optString("summary").orEmpty()
            m.copy(
                competitors = m.competitors.zip(raw) { c, t -> c.copy(score = cricketScore(t)) },
                clock = MatchClock("", m.clock?.period ?: 0, cricketOvers(raw)),
                statusText = status.ifEmpty { m.statusText },
            )
        }
        val updated = fresh?.copy(title = match.title, subtitle = match.subtitle, venue = match.venue, broadcasts = match.broadcasts, events = match.events) ?: match
        return MatchSummary(
            match = updated,
            timeline = objects(root.optJSONArray("keyEvents")).mapNotNull(::timelineEvent),
            stats = stats(root.optJSONObject("boxscore")?.optJSONArray("teams")),
            lineups = objects(root.optJSONArray("rosters")).map(::lineup),
            form = objects(root.optJSONArray("lastFiveGames")).mapNotNull { t ->
                val id = t.optJSONObject("team")?.optString("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                id to objects(t.optJSONArray("events")).mapNotNull(::formGame)
            }.toMap(),
        )
    }

    /** One `lastFiveGames` event; one with no result or opponent is skipped. */
    private fun formGame(e: JSONObject): FormGame? {
        val result = e.optString("gameResult").takeIf { it.isNotEmpty() } ?: return null
        val opponent = e.optJSONObject("opponent")?.optString("displayName")?.takeIf { it.isNotEmpty() } ?: return null
        return FormGame(
            startMs = runCatching { java.time.Instant.parse(e.optString("gameDate").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrDefault(0L),
            result = result,
            opponent = opponent,
            home = e.optString("atVs") != "@",
            score = e.optString("score"),
        )
    }

    /** Standings (`/apis/v2/sports/<sport>/<league>/standings`): one group per conference or group. */
    fun standings(league: League, body: String): StandingsTable {
        val root = JSONObject(body)
        val children = root.optJSONArray("children")
        val groups = if (children != null && children.length() > 0) {
            objects(children).map { StandingsGroup(it.optString("name"), rows(it.optJSONObject("standings"))) }
        } else {
            listOf(StandingsGroup(root.optString("name", league.name), rows(root.optJSONObject("standings"))))
        }
        return StandingsTable(league.key, groups.filter { it.rows.isNotEmpty() })
    }

    /** A racing series' standings (`/apis/v2/sports/racing/<series>/standings`): drivers, and F1's constructors. */
    fun racingStandings(league: League, body: String): Ranking {
        val groups = objects(JSONObject(body).optJSONArray("children")).mapNotNull { c ->
            val entries = objects(c.optJSONObject("standings")?.optJSONArray("entries"))
            val rows = entries.mapNotNull { e ->
                runCatching {
                    val stats = objects(e.optJSONArray("stats")).associateBy { it.optString("name") }
                    val who = e.optJSONObject("athlete")?.let { athlete(it, "") } ?: team(e.getJSONObject("team"))
                    val points = (stats["championshipPts"] ?: stats["points"])?.optDouble("value", 0.0)?.toInt() ?: 0
                    RankingRow(stats["rank"]?.optDouble("value", 0.0)?.toInt() ?: 0, who, points)
                }.getOrNull()
            }.sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }
            val kind = if (entries.any { it.has("athlete") }) RankingKind.DRIVERS else RankingKind.CONSTRUCTORS
            rows.takeIf { it.isNotEmpty() }?.let { RankingGroup(kind, it) }
        }
        return Ranking(league.key, groups)
    }

    /** A tennis tour's rankings (`/tennis/<atp|wta>/rankings`): its singles ranking, as far as ESPN lists it. */
    fun tennisRankings(league: League, body: String): Ranking {
        val list = JSONObject(body).optJSONArray("rankings")?.optJSONObject(0)
        val rows = objects(list?.optJSONArray("ranks")).mapNotNull { r ->
            runCatching {
                val a = r.getJSONObject("athlete")
                val name = a.optString("displayName")
                val player = Team(
                    id = a.getString("id"),
                    name = name,
                    shortName = a.optString("shortname").ifEmpty { name },
                    isAthlete = true,
                    country = a.optString("flagAltText").takeIf { it.isNotEmpty() },
                )
                RankingRow(r.getInt("current"), player, r.optDouble("points", 0.0).toInt())
            }.getOrNull()
        }
        return Ranking(league.key, if (rows.isEmpty()) emptyList() else listOf(RankingGroup(RankingKind.PLAYERS, rows)))
    }

    /**
     * A team's schedule (`/teams/<id>/schedule`, `?fixture=true` for what is to come). Each event names
     * its own competition, so a schedule across all of a club's competitions keeps each match's league.
     * The score is an object here (`{value, displayValue}`) rather than the scoreboard's text.
     */
    fun teamSchedule(sport: Sport, body: String): List<Match> =
        objects(JSONObject(body).optJSONArray("events")).mapNotNull { e ->
            runCatching {
                val league = e.optJSONObject("league")
                val slug = league?.optString("slug")?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val name = league.optString("name").ifEmpty { slug }
                val c = e.getJSONArray("competitions").getJSONObject(0)
                objects(c.optJSONArray("competitors")).forEach { side ->
                    side.optJSONObject("score")?.let { side.put("score", it.optString("displayValue")) }
                }
                teamMatch(sport, League.keyOf(sport, slug), slug, name, e, c)
            }.getOrNull()
        }

    /** A league's teams (`/teams`), for the My teams picker. */
    fun teams(body: String): List<Team> {
        val league = JSONObject(body).optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)
            ?: return emptyList()
        return objects(league.optJSONArray("teams")).mapNotNull { it.optJSONObject("team")?.let(::team) }
    }

    // --- events ---

    private fun teamMatch(sport: Sport, feed: String, slug: String, leagueName: String, event: JSONObject, c: JSONObject): Match {
        val status = c.getJSONObject("status")
        return Match(
            id = event.getString("id"),
            sport = sport,
            feed = feed,
            leagueSlug = slug,
            leagueName = leagueName,
            startMs = time(c.optString("date").ifEmpty { event.getString("date") }),
            state = state(status),
            statusText = statusText(status),
            title = event.optString("name"),
            clock = MatchClock(
                status.optString("displayClock"),
                status.optInt("period"),
                halfTime = status.optJSONObject("type")?.optString("name") == "STATUS_HALFTIME",
            ),
            competitors = objects(c.optJSONArray("competitors")).map { competitor(it, sport) },
            venue = venueOf(c.optJSONObject("venue")),
            broadcasts = broadcasts(c),
            events = objects(c.optJSONArray("details")).mapNotNull(::detailEvent),
        )
    }

    /** A tennis tournament is one event; each of its matches is a competition inside a grouping (singles, doubles). */
    private fun tennisMatches(league: League, tour: JSONObject): List<Match> {
        val tournament = tour.optString("name").ifEmpty { league.name }
        return objects(tour.optJSONArray("groupings")).flatMap { g ->
            val grouping = g.optJSONObject("grouping")?.optString("displayName").orEmpty()
            objects(g.optJSONArray("competitions")).mapNotNull { c ->
                runCatching {
                    val players = objects(c.optJSONArray("competitors")).map { competitor(it, Sport.TENNIS) }
                    val status = c.getJSONObject("status")
                    Match(
                        id = c.getString("id"),
                        sport = Sport.TENNIS,
                        feed = league.key,
                        leagueSlug = league.slug,
                        leagueName = tournament,
                        startMs = time(c.getString("date")),
                        state = state(status),
                        statusText = statusText(status),
                        title = players.joinToString(" v ") { it.team.name },
                        subtitle = listOf(grouping, c.optJSONObject("round")?.optString("displayName").orEmpty())
                            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null },
                        clock = MatchClock("", status.optInt("period")),
                        competitors = players,
                        broadcasts = broadcasts(c),
                    )
                }.getOrNull()
            }
        }
    }

    /** A Grand Prix is one event; each session (practice, qualifying, race) becomes its own entry. */
    private fun racingSessions(league: League, leagueName: String, e: JSONObject): List<Match> {
        val id = e.getString("id")
        return objects(e.optJSONArray("competitions")).mapIndexedNotNull { i, c ->
            runCatching {
                val status = c.getJSONObject("status")
                Match(
                    id = "$id-$i",
                    sport = Sport.RACING,
                    feed = league.key,
                    leagueSlug = league.slug,
                    leagueName = leagueName,
                    startMs = time(c.getString("date")),
                    state = state(status),
                    statusText = statusText(status),
                    title = e.optString("name"),
                    subtitle = c.optJSONObject("type")?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
                    // The podium only; a session lists the whole grid.
                    competitors = objects(c.optJSONArray("competitors"))
                        .sortedBy { it.optInt("order", Int.MAX_VALUE) }.take(3).map { competitor(it, Sport.RACING) },
                    venue = venueOf(e.optJSONObject("circuit")),
                    broadcasts = broadcasts(c),
                )
            }.getOrNull()
        }
    }

    /** An MMA card is one entry, headed by its main event (which ESPN lists last). */
    private fun mmaCard(league: League, leagueName: String, e: JSONObject): Match {
        val fights = objects(e.getJSONArray("competitions"))
        val main = fights.last()
        val states = fights.map { state(it.getJSONObject("status")) }
        val state = when {
            MatchState.IN in states -> MatchState.IN
            states.all { it == MatchState.POST } -> MatchState.POST
            states.all { it == MatchState.CANCELLED } -> MatchState.CANCELLED
            else -> MatchState.PRE
        }
        val live = fights.firstOrNull { state(it.getJSONObject("status")) == MatchState.IN } ?: main
        return Match(
            id = e.getString("id"),
            sport = Sport.MMA,
            feed = league.key,
            leagueSlug = league.slug,
            leagueName = leagueName,
            startMs = time(fights.first().optString("date").ifEmpty { e.getString("date") }),
            state = state,
            statusText = statusText(live.getJSONObject("status")),
            title = e.optString("name"),
            subtitle = main.optJSONObject("type")?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
            competitors = objects(main.optJSONArray("competitors")).sortedBy { it.optInt("order") }.map { competitor(it, Sport.MMA) },
            venue = venueOf(main.optJSONObject("venue")),
            broadcasts = broadcasts(main),
        )
    }

    private fun cricketMatch(feed: String, slug: String, series: String, e: JSONObject): Match {
        val full = e.optJSONObject("fullStatus")
        val stateName = full?.optJSONObject("type")?.optString("state")?.ifEmpty { null } ?: e.optString("status")
        val competitors = objects(e.optJSONArray("competitors")).map { c ->
            val text = c.optString("score")
            Competitor(
                team = Team(
                    id = c.getString("id"),
                    name = c.optString("displayName").ifEmpty { c.optString("name") },
                    shortName = c.optString("name").ifEmpty { c.optString("displayName") },
                    abbreviation = c.optString("abbreviation"),
                    logoUrl = logo(c.optString("logo")),
                ),
                home = c.optString("homeAway") == "home",
                winner = if (c.has("winner")) c.optBoolean("winner") else null,
                score = cricketScore(text),
            )
        }
        val overs = cricketOvers(objects(e.optJSONArray("competitors")).map { it.optString("score") })
        val state = when (stateName) {
            "in" -> MatchState.IN
            "post" -> if (full?.optString("summary").orEmpty().contains("abandon", ignoreCase = true)) MatchState.CANCELLED else MatchState.POST
            else -> MatchState.PRE
        }
        return Match(
            id = e.getString("id"),
            sport = Sport.CRICKET,
            feed = feed,
            leagueSlug = slug,
            leagueName = series,
            startMs = time(e.getString("date")),
            state = state,
            statusText = full?.optString("summary")?.ifEmpty { null } ?: e.optString("summary"),
            title = e.optString("name"),
            clock = MatchClock("", full?.optInt("period") ?: 0, overs),
            competitors = competitors,
        )
    }

    /** "87/3 (14.1/50 ov, target 272)" → "87/3", valued by its runs; null for no score yet. */
    private fun cricketScore(text: String): Score? =
        text.takeIf { it.isNotEmpty() }?.let { Score(it.substringBefore(" (").trim(), RUNS.find(it)?.value?.toIntOrNull()) }

    /** The side that is batting, or batted last, carries the overs: "86/4 (14.1/20 ov, target 84)". */
    private fun cricketOvers(scores: List<String>): String? = scores.mapNotNull { OVERS.find(it)?.groupValues?.get(1) }.lastOrNull()

    private fun competitor(c: JSONObject, sport: Sport): Competitor {
        val teamJson = c.optJSONObject("team")
        val team = if (teamJson != null) team(teamJson) else athlete(c.getJSONObject("athlete"), c.optString("id"))
        val periods = objects(c.optJSONArray("linescores")).map {
            PeriodScore(it.optDouble("value", 0.0).toInt(), if (it.has("tiebreak")) it.optInt("tiebreak") else null)
        }
        val text = c.optString("score")
        val score = when {
            text.isNotEmpty() -> Score(text, text.toIntOrNull(), periods)
            periods.isNotEmpty() -> Score(periods.joinToString(" ") { it.value.toString() }, null, periods)
            else -> null
        }
        return Competitor(
            team = team,
            home = c.optString("homeAway") == "home",
            winner = if (c.has("winner")) c.optBoolean("winner") else null,
            score = score.takeUnless { sport == Sport.RACING },
        )
    }

    private fun team(t: JSONObject): Team = Team(
        id = t.getString("id"),
        name = t.optString("displayName").ifEmpty { t.optString("name") },
        shortName = t.optString("shortDisplayName").ifEmpty { t.optString("displayName") },
        abbreviation = t.optString("abbreviation"),
        location = t.optString("location").takeIf { it.isNotEmpty() },
        logoUrl = logo(t.optString("logo").ifEmpty { t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() }),
    )

    private fun athlete(a: JSONObject, fallbackId: String): Team {
        val name = a.optString("displayName")
        return Team(
            id = a.optString("id").ifEmpty { fallbackId },
            name = name,
            shortName = a.optString("shortName").ifEmpty { name },
            isAthlete = true,
            country = a.optJSONObject("flag")?.optString("alt")?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * A competition's broadcasters. `geoBroadcasts` names each one's region ("US"), kept in
     * [Broadcast.market]; the older `broadcasts` list (no region) is the fallback.
     */
    private fun broadcasts(c: JSONObject): List<Broadcast> {
        val geo = objects(c.optJSONArray("geoBroadcasts")).mapNotNull { b ->
            val name = b.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Broadcast(name, b.optString("region").uppercase().takeIf { it.isNotEmpty() })
        }
        if (geo.isNotEmpty()) return geo.distinctBy { it.name }
        return objects(c.optJSONArray("broadcasts")).flatMap { b ->
            val market = b.optString("market").takeIf { it.isNotEmpty() }
            val names = b.optJSONArray("names") ?: JSONArray()
            (0 until names.length()).mapNotNull { i -> names.optString(i).takeIf { it.isNotEmpty() }?.let { Broadcast(it, market) } }
        }.distinctBy { it.name }
    }

    /** "Deutsche Bank Park, Frankfurt, Germany": the venue (or circuit) with its city and country. */
    private fun venueOf(v: JSONObject?): String? {
        val name = v?.optString("fullName")?.takeIf { it.isNotEmpty() } ?: return null
        val a = v.optJSONObject("address")
        return listOfNotNull(name, a?.optString("city")?.takeIf { it.isNotEmpty() }, a?.optString("country")?.takeIf { it.isNotEmpty() })
            .distinct().joinToString(", ")
    }

    /** A scoreboard "detail" (soccer): goals and cards only. */
    private fun detailEvent(d: JSONObject): MatchEvent? {
        val kind = when {
            d.optBoolean("scoringPlay") && d.optBoolean("ownGoal") -> MatchEvent.Kind.OWN_GOAL
            d.optBoolean("scoringPlay") && d.optBoolean("penaltyKick") -> MatchEvent.Kind.PENALTY_GOAL
            d.optBoolean("scoringPlay") -> MatchEvent.Kind.GOAL
            d.optBoolean("redCard") -> MatchEvent.Kind.RED_CARD
            d.optBoolean("yellowCard") -> MatchEvent.Kind.YELLOW_CARD
            else -> return null
        }
        return MatchEvent(
            kind = kind,
            minute = d.optJSONObject("clock")?.optString("displayValue").orEmpty(),
            teamId = d.optJSONObject("team")?.optString("id"),
            players = objects(d.optJSONArray("athletesInvolved")).map { it.optString("displayName") },
        )
    }

    /** A summary "keyEvent": the timeline. Delays, VAR checks and the like are left out. */
    private fun timelineEvent(e: JSONObject): MatchEvent? {
        val type = e.optJSONObject("type")?.optString("type").orEmpty()
        val kind = when {
            type == "own-goal" -> MatchEvent.Kind.OWN_GOAL
            type.startsWith("penalty") && e.optBoolean("scoringPlay") -> MatchEvent.Kind.PENALTY_GOAL
            type.startsWith("goal") -> MatchEvent.Kind.GOAL
            type == "yellow-card" -> MatchEvent.Kind.YELLOW_CARD
            type == "red-card" -> MatchEvent.Kind.RED_CARD
            type == "substitution" -> MatchEvent.Kind.SUBSTITUTION
            type == "kickoff" -> MatchEvent.Kind.KICKOFF
            type == "halftime" -> MatchEvent.Kind.HALF_TIME
            type == "end-regular-time" -> MatchEvent.Kind.FULL_TIME
            else -> return null
        }
        return MatchEvent(
            kind = kind,
            minute = e.optJSONObject("clock")?.optString("displayValue").orEmpty(),
            teamId = e.optJSONObject("team")?.optString("id"),
            players = objects(e.optJSONArray("participants")).mapNotNull { it.optJSONObject("athlete")?.optString("displayName") },
            text = e.optString("text").takeIf { it.isNotEmpty() },
        )
    }

    private fun stats(teams: JSONArray?): List<StatLine> {
        val sides = objects(teams)
        if (sides.size != 2) return emptyList()
        val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: sides[0]
        val away = sides.first { it !== home }
        val awayByName = objects(away.optJSONArray("statistics")).associateBy { it.optString("name") }
        return objects(home.optJSONArray("statistics")).mapNotNull { s ->
            val name = s.optString("name")
            val other = awayByName[name] ?: return@mapNotNull null
            StatLine(name, s.optString("label"), s.optString("displayValue"), other.optString("displayValue"))
        }
    }

    private fun lineup(r: JSONObject): Lineup {
        val players = objects(r.optJSONArray("roster")).map { p ->
            p.optBoolean("starter") to LineupPlayer(
                name = p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                jersey = p.optString("jersey").takeIf { it.isNotEmpty() },
                position = p.optJSONObject("position")?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
                subbedIn = p.optBoolean("subbedIn"),
                subbedOut = p.optBoolean("subbedOut"),
            )
        }
        return Lineup(
            teamId = r.optJSONObject("team")?.optString("id").orEmpty(),
            formation = r.optString("formation").takeIf { it.isNotEmpty() },
            starters = players.filter { it.first }.map { it.second },
            bench = players.filterNot { it.first }.map { it.second },
        )
    }

    private fun rows(standings: JSONObject?): List<StandingsRow> =
        objects(standings?.optJSONArray("entries")).mapNotNull { e ->
            runCatching {
                val stats = objects(e.optJSONArray("stats")).associateBy { it.optString("name") }
                fun int(name: String) = stats[name]?.let { s -> s.optString("displayValue").toIntOrNull() ?: s.optDouble("value", 0.0).toInt() } ?: 0
                StandingsRow(
                    rank = int("rank"),
                    team = team(e.getJSONObject("team")),
                    played = int("gamesPlayed"),
                    won = int("wins"),
                    drawn = int("ties"),
                    lost = int("losses"),
                    scored = int("pointsFor"),
                    conceded = int("pointsAgainst"),
                    difference = stats["pointDifferential"]?.optString("displayValue").orEmpty(),
                    points = int("points"),
                    note = e.optJSONObject("note")?.optString("description")?.takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }.sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }

    // --- helpers ---

    private fun state(status: JSONObject): MatchState {
        val type = status.optJSONObject("type") ?: return MatchState.PRE
        val name = type.optString("name")
        return when {
            "POSTPONED" in name -> MatchState.POSTPONED
            "CANCEL" in name || "ABANDON" in name -> MatchState.CANCELLED
            type.optString("state") == "in" -> MatchState.IN
            type.optString("state") == "post" -> MatchState.POST
            else -> MatchState.PRE
        }
    }

    private fun statusText(status: JSONObject): String =
        status.optJSONObject("type")?.let { it.optString("shortDetail").ifEmpty { it.optString("description") } }.orEmpty()

    /** ESPN writes "2026-10-16T18:30Z" (no seconds) and "2026-10-16T03:30:00Z"; ISO accepts both. */
    private fun time(s: String): Long = OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()

    /**
     * Crests through ESPN's own resizer at 96 px: 7 KB instead of 80 KB at full size.
     * Anything not on ESPN's image host is passed through as it is.
     */
    internal fun logo(url: String): String? {
        if (url.isEmpty()) return null
        val path = url.substringAfter("://a.espncdn.com", missingDelimiterValue = "")
        if (path.isEmpty() || !path.startsWith("/i/")) return url
        return "https://a.espncdn.com/combiner/i?img=$path&w=$LOGO_PX&h=$LOGO_PX"
    }

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private const val LOGO_PX = 96
    private val RUNS = Regex("^\\d+")
    private val OVERS = Regex("\\((\\d+(?:\\.\\d)?)(?:/\\d+)? ov")
}
