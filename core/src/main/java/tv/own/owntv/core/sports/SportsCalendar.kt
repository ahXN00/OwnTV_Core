package tv.own.owntv.core.sports

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Where a calendar month's data stands, for its status line. */
sealed interface CalendarStatus {
    data object Ready : CalendarStatus

    /** A fetch is still running. */
    data object Loading : CalendarStatus

    /** ESPN could not be reached; what is shown was saved at [savedAt]. */
    data class Offline(val savedAt: Long) : CalendarStatus

    /** ESPN could not be reached and nothing was saved. */
    data object Failed : CalendarStatus
}

/**
 * One month of the calendar: [days] are the device's own days, each day's matches by kick-off.
 * [seasonStart] is the earliest season start among the leagues shown (null: not known yet, or cricket,
 * which has no season), so the screen can stop its arrows there.
 */
data class CalendarMonth(
    val month: YearMonth,
    val days: Map<LocalDate, List<Match>>,
    val seasonStart: LocalDate?,
    val status: CalendarStatus,
) {
    /** A league's Fixtures: what is still to be played (live, coming, postponed), soonest first. */
    fun fixtures(): List<Match> = days.values.flatten().filter { it.state != MatchState.POST }

    /** A league's Results: what was played, latest first. */
    fun results(): List<Match> = days.values.flatten().filter { it.state == MatchState.POST }.reversed()
}

/**
 * One league's saved month. [fetchedAt] is the last whole-month fetch; cricket fills [days] instead,
 * one fetch time per day, because its feed answers one day at a time. [seasonStart] is ESPN's.
 */
internal data class SavedMonth(
    val matches: List<Match>,
    val fetchedAt: Long? = null,
    val days: Map<LocalDate, Long> = emptyMap(),
    val seasonStart: Long? = null,
) {
    val savedAt: Long? get() = fetchedAt ?: days.values.maxOrNull()
}

/**
 * When a calendar month is fetched, as pure functions so every rule can be tested on its own:
 *
 * | Month                       | Fetched                                                          |
 * |-----------------------------|------------------------------------------------------------------|
 * | never saved                 | when it is first shown                                           |
 * | past                        | never again                                                      |
 * | current or future           | when shown and older than the Calendar refresh setting           |
 * | a past day still not played | its month once more, once per opening of the calendar            |
 * | cricket                     | the same rules day by day, the focused day first                 |
 *
 * Today and tomorrow come from the Scores board instead, which is live ([view]).
 */
internal object CalendarRules {

    /** What to fetch for [league]'s [month]: `null` is the whole month, a date one cricket day. */
    fun due(
        league: League,
        month: YearMonth,
        saved: SavedMonth?,
        today: LocalDate,
        zone: ZoneId,
        refreshMs: Long,
        now: Long,
        rechecked: Boolean,
        focus: LocalDate? = null,
    ): List<LocalDate?> {
        fun unplayedBefore(day: (LocalDate) -> Boolean) = !rechecked && saved != null &&
            saved.matches.any { it.state == MatchState.PRE && dayOf(it, zone).let { d -> d < today && day(d) } }

        if (league.sport == Sport.CRICKET) {
            val from = focus?.takeIf { YearMonth.from(it) == month } ?: today.takeIf { YearMonth.from(it) == month } ?: month.atDay(1)
            return (1..month.lengthOfMonth()).map(month::atDay)
                .sortedBy { kotlin.math.abs(it.toEpochDay() - from.toEpochDay()) }
                .filter { d ->
                    val at = saved?.days?.get(d)
                    when {
                        at == null -> true
                        d >= today -> now - at >= refreshMs
                        else -> unplayedBefore { it == d }
                    }
                }
        }
        val at = saved?.fetchedAt
        val wanted = when {
            at == null -> true
            month >= YearMonth.from(today) && now - at >= refreshMs -> true
            else -> unplayedBefore { true }
        }
        return if (wanted) listOf(null) else emptyList()
    }

    /**
     * [month]'s matches of [leagues] by the device's day: the saved month and its two neighbours (a
     * late kick-off in ESPN's day can fall on the next day here), with the [board]'s today and
     * tomorrow laid over them, since those are kept live.
     */
    fun view(month: YearMonth, leagues: List<League>, saved: Map<Slot, SavedMonth>, board: SportsBoard, zone: ZoneId): Map<LocalDate, List<Match>> {
        val keys = leagues.mapTo(HashSet()) { it.key }
        val byKey = LinkedHashMap<String, Match>()
        leagues.forEach { l ->
            listOf(month.minusMonths(1), month, month.plusMonths(1)).forEach { m ->
                saved[Slot(l.key, m)]?.matches?.forEach { byKey[it.key] = it }
            }
        }
        board.matches.filter { it.feed in keys }.forEach { byKey[it.key] = it }
        return byKey.values
            .filter { YearMonth.from(dayOf(it, zone)) == month }
            .sortedBy { it.startMs }
            .groupBy { dayOf(it, zone) }
            .toSortedMap()
    }

    fun dayOf(m: Match, zone: ZoneId): LocalDate = Instant.ofEpochMilli(m.startMs).atZone(zone).toLocalDate()
}

/** One league's month: what is saved, and fetched, as one. */
internal data class Slot(val leagueKey: String, val month: YearMonth)

/**
 * The calendar's data, apart from the board: each followed league's months, saved in
 * `filesDir/sports/calendar/` and fetched only when shown ([CalendarRules]). Fetches go through the
 * [SportsGate] the board uses, so they share its four at a time and its pause; one already running is
 * never started twice. Nothing here is fetched by itself — only while a screen collects [month]; five
 * minutes after the last one stops, the months are dropped from memory (their files stay).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsCalendar(
    private val source: SportsSource,
    private val gate: SportsGate,
    private val board: Flow<SportsBoard>,
    private val followed: Flow<Set<String>>,
    private val refreshDays: Flow<Int>,
    dataDir: File,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private data class Fetch(val slot: Slot, val day: LocalDate?)

    private val serial = dispatcher.limitedParallelism(1)
    private val dir = File(File(dataDir, CACHE_DIR), CALENDAR_DIR)

    private val saved = MutableStateFlow<Map<Slot, SavedMonth>>(emptyMap())
    private val failed = MutableStateFlow<Set<Fetch>>(emptySet())
    private val running = MutableStateFlow<Set<Fetch>>(emptySet())

    // Confined to [serial].
    private val loaded = HashSet<Slot>()
    private val rechecked = HashSet<Slot>()
    private val inFlight = HashMap<Fetch, Job>()
    private var watchers = 0
    private var dropJob: Job? = null

    /**
     * [month] for the followed leagues among [leagueKeys] (the caller's sport and league choice), kept
     * up to date while collected. [focus] is the selected day, fetched first where the feed is per day.
     */
    fun month(leagueKeys: Set<String>, month: YearMonth, focus: LocalDate? = null): Flow<CalendarMonth> = flow {
        val follow = followed.first()
        val leagues = leagueKeys.filter { it in follow }.mapNotNull(SportsCatalog::league)
        val shown = leagues.mapTo(HashSet()) { Slot(it.key, month) }
        val refreshMs = refreshDays.first() * DAY_MS
        withContext(serial) {
            watchers++
            dropJob?.cancel()
            leagues.forEach { l -> listOf(month.minusMonths(1), month, month.plusMonths(1)).forEach { load(Slot(l.key, it)) } }
            val now = clock()
            val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
            leagues.forEach { l ->
                val slot = Slot(l.key, month)
                val due = CalendarRules.due(l, month, saved.value[slot], today, zone(), refreshMs, now, slot in rechecked, focus)
                if (due.isNotEmpty()) rechecked += slot
                due.forEach { start(Fetch(slot, it), l) }
            }
        }
        try {
            emitAll(
                combine(saved, failed, running, board) { s, f, r, b ->
                    val days = CalendarRules.view(month, leagues, s, b, zone())
                    CalendarMonth(month, days, seasonStart(leagues, month, s), status(shown, s, f, r, days.isNotEmpty()))
                }.distinctUntilChanged(),
            )
        } finally {
            withContext(NonCancellable + serial) { closed() }
        }
    }

    /**
     * "Refresh sports data now": every saved current and future month (for cricket, every day from
     * today on) counts as out of date, so it is fetched again when next shown. Past months stay.
     */
    fun markStale() {
        scope.launch(serial) {
            val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
            dir.listFiles().orEmpty().mapNotNull { slotOf(it.name) }.forEach(::load)
            saved.value.keys.filter { it.month >= YearMonth.from(today) }.forEach { slot ->
                put(slot) { m -> m!!.copy(fetchedAt = m.fetchedAt?.let { 0L }, days = m.days.filterKeys { it < today }) }
            }
        }
    }

    /**
     * Every saved match of [sport] that [teamId] plays in, from any league's saved months: how a team
     * page finds its cup games, which its league's schedule does not list.
     */
    internal suspend fun savedMatchesOf(sport: Sport, teamId: String): List<Match> = withContext(serial) {
        dir.listFiles().orEmpty().mapNotNull { slotOf(it.name) }.filter { it.leagueKey.startsWith("${sport.path}/") }.forEach(::load)
        saved.value.values.flatMap { it.matches }.filter { m -> m.sport == sport && m.competitors.any { it.team.id == teamId } }
    }

    private fun seasonStart(leagues: List<League>, month: YearMonth, s: Map<Slot, SavedMonth>): LocalDate? =
        leagues.mapNotNull { l ->
            listOf(month, month.plusMonths(1), month.minusMonths(1)).firstNotNullOfOrNull { s[Slot(l.key, it)]?.seasonStart }
        }.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }

    private fun status(shown: Set<Slot>, s: Map<Slot, SavedMonth>, f: Set<Fetch>, r: Set<Fetch>, anything: Boolean): CalendarStatus {
        val failing = f.mapNotNullTo(HashSet()) { it.slot.takeIf { slot -> slot in shown } }
        if (failing.isNotEmpty()) {
            val at = failing.mapNotNull { s[it]?.savedAt }.minOrNull() ?: shown.mapNotNull { s[it]?.savedAt }.minOrNull()
            return if (at == null || !anything) CalendarStatus.Failed else CalendarStatus.Offline(at)
        }
        return if (r.any { it.slot in shown }) CalendarStatus.Loading else CalendarStatus.Ready
    }

    private fun start(fetch: Fetch, league: League) {
        if (fetch in inFlight) return
        running.update { it + fetch }
        inFlight[fetch] = scope.launch(serial) {
            try {
                val at = clock()
                val day = fetch.day
                if (day == null) {
                    val month = gate.call { source.month(league, fetch.slot.month) }
                    put(fetch.slot) { old -> SavedMonth(month.matches, at, seasonStart = month.seasonStartMs ?: old?.seasonStart) }
                } else {
                    val fresh = gate.call { source.scoreboard(league, day) }
                    val keys = fresh.mapTo(HashSet()) { it.key }
                    put(fetch.slot) { old ->
                        SavedMonth(old?.matches.orEmpty().filter { it.key !in keys } + fresh, days = old?.days.orEmpty() + (day to at))
                    }
                }
                failed.update { it - fetch }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "calendar fetch failed code=${(e as? SportsHttpException)?.code ?: -1} error=${e.javaClass.simpleName}")
                failed.update { it + fetch }
            } finally {
                inFlight.remove(fetch)
                running.update { it - fetch }
            }
        }
    }

    private fun closed() {
        if (--watchers > 0) return
        dropJob = scope.launch(serial) {
            delay(DROP_AFTER_MS)
            saved.value = emptyMap()
            failed.value = emptySet()
            loaded.clear()
            rechecked.clear()
        }
    }

    private fun fileOf(slot: Slot) = File(dir, "${slot.leagueKey.replace('/', '_')}_${slot.month}.json")

    /** [fileOf] read back: `<sport>_<slug>_<yyyy-MM>.json`. A sport's path has no `_`; a slug may (`eng.league_cup`). */
    private fun slotOf(name: String): Slot? {
        if (!name.endsWith(JSON)) return null
        val base = name.removeSuffix(JSON)
        val month = runCatching { YearMonth.parse(base.takeLast(7)) }.getOrNull() ?: return null
        val league = base.dropLast(8).replaceFirst('_', '/')
        return Slot(league, month).takeIf { '/' in league }
    }

    private fun load(slot: Slot) {
        if (!loaded.add(slot) || slot in saved.value) return
        val stored = runCatching { fileOf(slot).takeIf { it.isFile }?.readText()?.let(::read) }.getOrNull() ?: return
        saved.update { if (slot in it) it else it + (slot to stored) }
    }

    private fun put(slot: Slot, change: (SavedMonth?) -> SavedMonth) {
        val next = change(saved.value[slot])
        saved.update { it + (slot to next) }
        runCatching {
            dir.mkdirs()
            val file = fileOf(slot)
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(write(next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "calendar write failed: ${it.javaClass.simpleName}") }
    }

    internal companion object {
        private const val TAG = "OwnTV-Sports"
        private const val CACHE_DIR = "sports"
        private const val CALENDAR_DIR = "calendar"
        private const val VERSION = 1
        private const val JSON = ".json"
        private const val DROP_AFTER_MS = 5 * 60_000L
        const val DAY_MS = 24 * 60 * 60_000L

        fun write(m: SavedMonth): String = JSONObject()
            .put("v", VERSION)
            .putOpt("at", m.fetchedAt)
            .putOpt("ss", m.seasonStart)
            .put("d", JSONObject().apply { m.days.forEach { (d, at) -> put(d.toString(), at) } })
            .put("m", JSONArray().apply { m.matches.forEach { put(SportsCacheJson.matchJson(it)) } })
            .toString()

        /** A saved month, or null when the file is unreadable or from another format. */
        fun read(text: String): SavedMonth? = runCatching {
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION) return null
            val days = o.optJSONObject("d")
            val arr = o.optJSONArray("m")
            SavedMonth(
                matches = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(SportsCacheJson::matchFromJson) },
                fetchedAt = if (o.has("at")) o.getLong("at") else null,
                days = days?.keys()?.asSequence()?.associate { LocalDate.parse(it) to days.getLong(it) }.orEmpty(),
                seasonStart = if (o.has("ss")) o.getLong("ss") else null,
            )
        }.getOrNull()
    }
}
