package tv.own.owntv.core.sports

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A card in the Leagues tab: who leads the table or ranking ([leaderPoints] its points), and the next match. */
data class LeagueCard(val league: League, val leader: Team?, val leaderPoints: Int?, val next: Match?)

object LeagueCards {

    /** Sports whose tables rank by wins and losses; ESPN's "points" there mean nothing, and its rows are not in order. */
    val RECORD_SPORTS = setOf(Sport.BASKETBALL, Sport.FOOTBALL, Sport.BASEBALL)

    /**
     * [league]'s card. The leader is the first row of the board's table (its first group, where a
     * league has several) or ranking — in [RECORD_SPORTS] the best record in any group; the next match
     * is the soonest still to come in [months] (the calendar's current month, and the next, so a card on
     * the 31st still has one) or on the board.
     */
    fun card(league: League, board: SportsBoard, months: List<CalendarMonth>, now: Long): LeagueCard {
        val groups = board.standings[league.key]?.groups
        val row = if (league.sport in RECORD_SPORTS) {
            groups?.flatMap { it.rows }?.maxWithOrNull(compareBy({ it.won.toDouble() / (it.won + it.lost).coerceAtLeast(1) }, { it.won }))
        } else groups?.firstOrNull()?.rows?.firstOrNull()
        val ranked = board.rankings[league.key]?.groups?.firstOrNull()?.rows?.firstOrNull()
        val next = (months.flatMap { it.days.values.flatten() } + board.matches)
            .filter { it.feed == league.key && it.state == MatchState.PRE && it.startMs > now }
            .minByOrNull { it.startMs }
        return LeagueCard(league, row?.team ?: ranked?.team, row?.points ?: ranked?.points, next)
    }
}

/**
 * A team's page: [fixtures] soonest first, [results] latest first (the first five are its form),
 * [position] its place in its league's table on the board (null: no table, or not in it). [failed]:
 * ESPN could not be reached, and what is shown is the last copy, if any.
 */
data class TeamSchedule(val fixtures: List<Match>, val results: List<Match>, val position: Int?, val failed: Boolean = false)

/**
 * Team pages' schedules, fetched when a page opens and kept for the rest of the device's day, in memory.
 * Football's schedule covers every competition the club plays; any other sport's covers its league only,
 * so the team's matches in other leagues' saved calendar months (a cup) are added to it.
 */
class SportsTeams(
    private val source: SportsSource,
    private val gate: SportsGate,
    private val calendar: SportsCalendar,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private data class Kept(val day: LocalDate, val fixtures: List<Match>, val results: List<Match>)

    private val lock = Mutex()
    private val kept = HashMap<String, Kept>()

    /** [teamId]'s schedule in [league], with its place in [board]'s table. */
    suspend fun schedule(league: League, teamId: String, board: SportsBoard): TeamSchedule {
        val key = "${league.key}#$teamId"
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        val position = board.standings[league.key]?.groups?.flatMap { it.rows }?.firstOrNull { it.team.id == teamId }?.rank
        val fresh = lock.withLock { kept[key]?.takeIf { it.day == today } } ?: try {
            coroutineScope {
                val fixtures = async { gate.call { source.teamSchedule(league, teamId, fixtures = true) } }
                val results = async { gate.call { source.teamSchedule(league, teamId, fixtures = false) } }
                Kept(today, fixtures.await(), results.await())
            }.also { lock.withLock { kept[key] = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "team schedule failed code=${(e as? SportsHttpException)?.code ?: -1} error=${e.javaClass.simpleName}")
            val last = lock.withLock { kept[key] }
            return TeamSchedule(last?.fixtures.orEmpty(), last?.results.orEmpty(), position, failed = true)
        }
        val cups = if (league.sport == Sport.SOCCER) emptyList() else calendar.savedMatchesOf(league.sport, teamId)
        val all = (fresh.fixtures + fresh.results + cups).distinctBy { it.key }
        return TeamSchedule(
            fixtures = all.filter { it.state != MatchState.POST }.sortedBy { it.startMs },
            results = all.filter { it.state == MatchState.POST }.sortedByDescending { it.startMs },
            position = position,
        )
    }

    private companion object {
        const val TAG = "OwnTV-Sports"
    }
}
