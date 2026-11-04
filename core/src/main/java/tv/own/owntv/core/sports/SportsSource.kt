package tv.own.owntv.core.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Where scores come from. One implementation today ([EspnSportsSource]); the interface keeps ESPN replaceable. */
interface SportsSource {
    /** A league's matches on [day] (the source's own "today" when null). */
    suspend fun scoreboard(league: League, day: LocalDate? = null): List<Match>
    suspend fun summary(match: Match): MatchSummary
    suspend fun standings(league: League): StandingsTable
    suspend fun teams(league: League): List<Team>

    /** A league's whole [month] in one call. Not for cricket, whose feed answers one day at a time. */
    suspend fun month(league: League, month: YearMonth): MonthBoard

    /** The rankings of a league [SportsWindow.hasRanking] names: a racing series' standings, a tennis tour's players. */
    suspend fun rankings(league: League): Ranking

    /** [teamId]'s matches in [league]: [fixtures] those to come, else the results. Football reads every competition the club plays. */
    suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match>
}

/** A month's matches, and when the season they belong to started (null: ESPN did not say). */
data class MonthBoard(val matches: List<Match>, val seasonStartMs: Long?)

/** A non-2xx answer. [code] 403 and 429 mean "slow down". */
class SportsHttpException(val code: Int) : IOException("HTTP $code")

/**
 * ESPN's public endpoints, called straight from the device: no key, no Worker in between.
 * Rides the app's shared OkHttp client — `newBuilder()` keeps its connection pool, proxy, DNS and
 * gzip — with a 10 s ceiling per call. No retries here; [SportsRefreshPolicy] decides when to try again.
 */
class EspnSportsSource(client: OkHttpClient) : SportsSource {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        val date = day?.format(DateTimeFormatter.BASIC_ISO_DATE)
        return if (league.sport == Sport.CRICKET) {
            // Cricket series change every few weeks, so ESPN's per-league scoreboards are no use to a
            // follow list; the header feed carries every series' matches of the day in one call.
            EspnParser.cricketHeader(league, get("$WEB/apis/v2/scoreboard/header?sport=cricket" + date?.let { "&dates=$it" }.orEmpty()))
        } else {
            EspnParser.scoreboard(league, get("$SITE/apis/site/v2/sports/${league.sport.path}/${league.slug}/scoreboard" + date?.let { "?dates=$it" }.orEmpty()))
        }
    }

    override suspend fun month(league: League, month: YearMonth): MonthBoard {
        require(league.sport != Sport.CRICKET) { "cricket has no month feed" }
        val body = get("$SITE/apis/site/v2/sports/${league.sport.path}/${league.slug}/scoreboard?dates=${month.format(MONTH)}&limit=$MONTH_LIMIT")
        return MonthBoard(EspnParser.scoreboard(league, body), EspnParser.seasonStart(body))
    }

    override suspend fun rankings(league: League): Ranking = when (league.sport) {
        Sport.RACING -> EspnParser.racingStandings(league, get("$SITE/apis/v2/sports/racing/${league.slug}/standings"))
        Sport.TENNIS -> EspnParser.tennisRankings(league, get("$SITE/apis/site/v2/sports/tennis/${league.slug}/rankings"))
        else -> throw IllegalArgumentException("no ranking")
    }

    override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> {
        // Only football answers for every competition at once (`soccer/all`): league, cups, Europe.
        val slug = if (league.sport == Sport.SOCCER) "all" else league.slug
        val url = "$SITE/apis/site/v2/sports/${league.sport.path}/$slug/teams/$teamId/schedule" + if (fixtures) "?fixture=true" else ""
        return EspnParser.teamSchedule(league.sport, get(url))
    }

    override suspend fun summary(match: Match): MatchSummary =
        EspnParser.summary(match, get("$SITE/apis/site/v2/sports/${match.sport.path}/${match.leagueSlug}/summary?event=${match.id}"))

    override suspend fun standings(league: League): StandingsTable =
        EspnParser.standings(league, get("$SITE/apis/v2/sports/${league.sport.path}/${league.slug}/standings"))

    override suspend fun teams(league: League): List<Team> =
        EspnParser.teams(get("$SITE/apis/site/v2/sports/${league.sport.path}/${league.slug}/teams"))

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SportsHttpException(response.code)
                response.body.string()
            }
        } finally {
            hook?.dispose()
        }
    }

    private companion object {
        const val SITE = "https://site.api.espn.com"
        const val WEB = "https://site.web.api.espn.com"
        const val TIMEOUT_SECONDS = 10L

        /** ESPN pages a scoreboard at 100 events by default; an NBA month is over 200. */
        const val MONTH_LIMIT = 1000
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}
