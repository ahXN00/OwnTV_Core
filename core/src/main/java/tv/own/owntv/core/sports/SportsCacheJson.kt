package tv.own.owntv.core.sports

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.FormGame
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

/**
 * Our own slim JSON for the stored board — not ESPN's, which is 10–50 times bigger. It holds what the
 * last rebuild built: the scoreboards, every match's details and every league's table or ranking, kept
 * until the next rebuild. A file that does not parse, or an entry in it that does not, is dropped.
 */
internal object SportsCacheJson {

    private const val VERSION = 3

    /** [board]'s matches, details, tables, rebuild time and follow list; the live flags (failing, paused) are not kept. */
    fun write(board: SportsBoard): String =
        JSONObject()
            .put("v", VERSION)
            .putOpt("at", board.rebuiltAt)
            .putOpt("for", board.builtFor?.let { JSONArray(it.toList()) })
            .put("end", JSONObject().apply { board.endedAt.forEach { (k, v) -> put(k, v) } })
            .put("today", feeds(board.today))
            .put("tomorrow", feeds(board.tomorrow))
            .put("sum", JSONObject().apply { board.summaries.forEach { (k, v) -> put(k, summary(v)) } })
            .put("tab", JSONObject().apply { board.standings.forEach { (k, v) -> put(k, table(v)) } })
            .put("rk", JSONObject().apply { board.rankings.forEach { (k, v) -> put(k, ranking(v)) } })
            .toString()

    /** The stored board, or null when the file is unreadable or from another format. */
    fun read(text: String): SportsBoard? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v") != VERSION) return null
        SportsBoard(
            today = feeds(o.optJSONObject("today")),
            tomorrow = feeds(o.optJSONObject("tomorrow")),
            summaries = entries(o.optJSONObject("sum")) { runCatching { summary(it) }.getOrNull() },
            standings = entries(o.optJSONObject("tab")) { runCatching { table(it) }.getOrNull() },
            rankings = entries(o.optJSONObject("rk")) { runCatching { ranking(it) }.getOrNull() },
            rebuiltAt = if (o.has("at")) o.getLong("at") else null,
            builtFor = o.optJSONArray("for")?.let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } },
            endedAt = o.optJSONObject("end")?.let { e -> e.keys().asSequence().associateWith { e.getLong(it) } }.orEmpty(),
        )
    }.getOrNull()

    private fun <T> entries(o: JSONObject?, read: (JSONObject) -> T?): Map<String, T> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, T>()
        o.keys().forEach { k -> o.optJSONObject(k)?.let(read)?.let { out[k] = it } }
        return out
    }

    private fun summary(s: MatchSummary) = JSONObject()
        .put("m", match(s.match))
        .put("tl", JSONArray().apply { s.timeline.forEach { put(event(it)) } })
        .put("st", JSONArray().apply { s.stats.forEach { put(JSONObject().put("n", it.name).put("l", it.label).put("h", it.home).put("a", it.away)) } })
        .put("lu", JSONArray().apply {
            s.lineups.forEach { l ->
                put(JSONObject().put("tm", l.teamId).putOpt("f", l.formation)
                    .put("s", JSONArray().apply { l.starters.forEach { put(player(it)) } })
                    .put("b", JSONArray().apply { l.bench.forEach { put(player(it)) } }))
            }
        })
        .put("fo", JSONObject().apply {
            s.form.forEach { (team, games) ->
                put(team, JSONArray().apply {
                    games.forEach { put(JSONObject().put("t", it.startMs).put("r", it.result).put("o", it.opponent).put("h", it.home).put("s", it.score)) }
                })
            }
        })

    private fun summary(o: JSONObject) = MatchSummary(
        match = match(o.getJSONObject("m")),
        timeline = objects(o.optJSONArray("tl")).map(::event),
        stats = objects(o.optJSONArray("st")).map { StatLine(it.getString("n"), it.getString("l"), it.getString("h"), it.getString("a")) },
        lineups = objects(o.optJSONArray("lu")).map { l ->
            Lineup(l.getString("tm"), l.optStringOrNull("f"), objects(l.optJSONArray("s")).map(::player), objects(l.optJSONArray("b")).map(::player))
        },
        form = o.optJSONObject("fo")?.let { fo ->
            fo.keys().asSequence().associateWith { team ->
                objects(fo.optJSONArray(team)).map { FormGame(it.getLong("t"), it.getString("r"), it.getString("o"), it.getBoolean("h"), it.getString("s")) }
            }
        }.orEmpty(),
    )

    private fun player(p: LineupPlayer) = JSONObject()
        .put("n", p.name).putOpt("j", p.jersey).putOpt("p", p.position)
        .apply {
            if (p.subbedIn) put("in", true)
            if (p.subbedOut) put("out", true)
        }

    private fun player(o: JSONObject) =
        LineupPlayer(o.getString("n"), o.optStringOrNull("j"), o.optStringOrNull("p"), o.optBoolean("in"), o.optBoolean("out"))

    private fun table(t: StandingsTable) = JSONObject()
        .put("k", t.leagueKey)
        .put("g", JSONArray().apply {
            t.groups.forEach { g ->
                put(JSONObject().put("n", g.name).put("r", JSONArray().apply {
                    g.rows.forEach { r ->
                        put(JSONObject().put("rk", r.rank).put("tm", team(r.team)).put("p", r.played).put("w", r.won).put("d", r.drawn)
                            .put("l", r.lost).put("f", r.scored).put("a", r.conceded).put("gd", r.difference).put("pt", r.points).putOpt("no", r.note))
                    }
                }))
            }
        })

    private fun table(o: JSONObject) = StandingsTable(
        leagueKey = o.getString("k"),
        groups = objects(o.optJSONArray("g")).map { g ->
            StandingsGroup(g.getString("n"), objects(g.optJSONArray("r")).map { r ->
                StandingsRow(
                    rank = r.getInt("rk"), team = team(r.getJSONObject("tm")), played = r.getInt("p"), won = r.getInt("w"),
                    drawn = r.getInt("d"), lost = r.getInt("l"), scored = r.getInt("f"), conceded = r.getInt("a"),
                    difference = r.getString("gd"), points = r.getInt("pt"), note = r.optStringOrNull("no"),
                )
            })
        },
    )

    private fun ranking(r: Ranking) = JSONObject()
        .put("k", r.leagueKey)
        .put("g", JSONArray().apply {
            r.groups.forEach { g ->
                put(JSONObject().put("n", g.kind.name).put("r", JSONArray().apply {
                    g.rows.forEach { put(JSONObject().put("rk", it.rank).put("tm", team(it.team)).put("pt", it.points)) }
                }))
            }
        })

    private fun ranking(o: JSONObject) = Ranking(
        leagueKey = o.getString("k"),
        groups = objects(o.optJSONArray("g")).map { g ->
            RankingGroup(RankingKind.valueOf(g.getString("n")), objects(g.optJSONArray("r")).map { RankingRow(it.getInt("rk"), team(it.getJSONObject("tm")), it.getInt("pt")) })
        },
    )

    private fun feeds(map: Map<String, List<Match>>) = JSONObject().apply {
        map.forEach { (feed, matches) -> put(feed, JSONArray().apply { matches.forEach { put(match(it)) } }) }
    }

    private fun feeds(o: JSONObject?): Map<String, List<Match>> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, List<Match>>()
        o.keys().forEach { feed -> out[feed] = objects(o.optJSONArray(feed)).mapNotNull { runCatching { match(it) }.getOrNull() } }
        return out
    }

    internal fun matchJson(m: Match): JSONObject = match(m)

    internal fun matchFromJson(o: JSONObject): Match? = runCatching { match(o) }.getOrNull()

    private fun match(m: Match) = JSONObject()
        .put("id", m.id).put("sp", m.sport.name).put("f", m.feed).put("ls", m.leagueSlug).put("ln", m.leagueName)
        .put("t0", m.startMs).put("st", m.state.name).put("tx", m.statusText).put("ti", m.title)
        .putOpt("sub", m.subtitle).putOpt("ve", m.venue)
        .putOpt("ck", m.clock?.let { JSONObject().put("d", it.display).put("p", it.period).putOpt("ov", it.overs).apply { if (it.halfTime) put("ht", true) } })
        .put("c", JSONArray().apply { m.competitors.forEach { put(competitor(it)) } })
        .put("b", JSONArray().apply { m.broadcasts.forEach { put(JSONObject().put("n", it.name).putOpt("m", it.market)) } })
        .put("e", JSONArray().apply { m.events.forEach { put(event(it)) } })

    private fun match(o: JSONObject) = Match(
        id = o.getString("id"),
        sport = Sport.valueOf(o.getString("sp")),
        feed = o.getString("f"),
        leagueSlug = o.getString("ls"),
        leagueName = o.getString("ln"),
        startMs = o.getLong("t0"),
        state = MatchState.valueOf(o.getString("st")),
        statusText = o.optString("tx"),
        title = o.optString("ti"),
        subtitle = o.optStringOrNull("sub"),
        venue = o.optStringOrNull("ve"),
        clock = o.optJSONObject("ck")?.let { MatchClock(it.optString("d"), it.optInt("p"), it.optStringOrNull("ov"), it.optBoolean("ht")) },
        competitors = objects(o.optJSONArray("c")).map(::competitor),
        broadcasts = objects(o.optJSONArray("b")).map { Broadcast(it.getString("n"), it.optStringOrNull("m")) },
        events = objects(o.optJSONArray("e")).map(::event),
    )

    private fun competitor(c: Competitor) = JSONObject()
        .put("tm", team(c.team)).put("h", c.home).putOpt("w", c.winner)
        .putOpt("s", c.score?.let { s ->
            JSONObject().put("t", s.text).putOpt("v", s.value).put("p", JSONArray().apply {
                s.periods.forEach { put(JSONObject().put("v", it.value).putOpt("tb", it.tiebreak)) }
            })
        })

    private fun competitor(o: JSONObject) = Competitor(
        team = team(o.getJSONObject("tm")),
        home = o.optBoolean("h"),
        winner = if (o.has("w")) o.getBoolean("w") else null,
        score = o.optJSONObject("s")?.let { s ->
            Score(
                text = s.getString("t"),
                value = if (s.has("v")) s.getInt("v") else null,
                periods = objects(s.optJSONArray("p")).map { PeriodScore(it.getInt("v"), if (it.has("tb")) it.getInt("tb") else null) },
            )
        },
    )

    private fun team(t: Team) = JSONObject()
        .put("id", t.id).put("n", t.name).put("sn", t.shortName).put("ab", t.abbreviation)
        .putOpt("lo", t.location).putOpt("img", t.logoUrl).put("at", t.isAthlete).putOpt("co", t.country)

    private fun team(o: JSONObject) = Team(
        id = o.getString("id"),
        name = o.getString("n"),
        shortName = o.optString("sn", o.getString("n")),
        abbreviation = o.optString("ab"),
        location = o.optStringOrNull("lo"),
        logoUrl = o.optStringOrNull("img"),
        isAthlete = o.optBoolean("at"),
        country = o.optStringOrNull("co"),
    )

    private fun event(e: MatchEvent) = JSONObject()
        .put("k", e.kind.name).put("m", e.minute).putOpt("tm", e.teamId).put("p", JSONArray(e.players)).putOpt("tx", e.text)

    private fun event(o: JSONObject) = MatchEvent(
        kind = MatchEvent.Kind.valueOf(o.getString("k")),
        minute = o.optString("m"),
        teamId = o.optStringOrNull("tm"),
        players = o.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        text = o.optStringOrNull("tx"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
