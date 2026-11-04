package tv.own.owntv.core.sports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import tv.own.owntv.core.sports.model.Team
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SportsCalendarTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private val bl = SportsCatalog.league("soccer/ger.1")!!
    private val cricket = SportsCatalog.league("cricket/all")!!
    private val october = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 18)
    private val now = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli()
    private val day = SportsCalendar.DAY_MS
    private val week = 7 * day

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun match(id: String, start: Long, state: MatchState = MatchState.POST, league: League = bl) =
        Match(id, league.sport, league.key, league.slug, league.name, start, state, "", "Home v Away")

    // --- the rules ---

    private fun due(saved: SavedMonth?, month: YearMonth = october, rechecked: Boolean = false, league: League = bl, focus: LocalDate? = null) =
        CalendarRules.due(league, month, saved, today, zone, week, now, rechecked, focus)

    @Test
    fun `a month never saved is fetched`() {
        assertEquals(listOf<LocalDate?>(null), due(null))
        assertEquals(listOf<LocalDate?>(null), due(null, month = YearMonth.of(2026, 3)))
    }

    @Test
    fun `a past month is never fetched again`() {
        val played = SavedMonth(listOf(match("1", at("2026-09-12T13:30:00Z"))), fetchedAt = now - 30 * day)
        assertEquals(emptyList<LocalDate?>(), due(played, month = YearMonth.of(2026, 9)))
    }

    @Test
    fun `the current and a future month are fetched again once older than the setting`() {
        assertEquals(emptyList<LocalDate?>(), due(SavedMonth(emptyList(), fetchedAt = now - 6 * day)))
        assertEquals(listOf<LocalDate?>(null), due(SavedMonth(emptyList(), fetchedAt = now - 7 * day)))
        val november = YearMonth.of(2026, 11)
        assertEquals(emptyList<LocalDate?>(), due(SavedMonth(emptyList(), fetchedAt = now - day), month = november))
        assertEquals(listOf<LocalDate?>(null), due(SavedMonth(emptyList(), fetchedAt = now - 8 * day), month = november))
    }

    @Test
    fun `a past day that still shows a kick-off time fetches its month once per opening`() {
        val stale = SavedMonth(listOf(match("1", at("2026-09-26T13:30:00Z"), MatchState.PRE)), fetchedAt = now - 40 * day)
        assertEquals(listOf<LocalDate?>(null), due(stale, month = YearMonth.of(2026, 9)))
        assertEquals("not twice in one opening", emptyList<LocalDate?>(), due(stale, month = YearMonth.of(2026, 9), rechecked = true))
        val coming = SavedMonth(listOf(match("2", at("2026-10-24T13:30:00Z"), MatchState.PRE)), fetchedAt = now - day)
        assertEquals("a kick-off still to come is no reason", emptyList<LocalDate?>(), due(coming))
    }

    @Test
    fun `cricket goes day by day, the focused day first, past days never again`() {
        val all = due(null, league = cricket, focus = LocalDate.of(2026, 10, 5))
        assertEquals(31, all.size)
        assertEquals(LocalDate.of(2026, 10, 5), all.first())

        val days = (1..31).associate { october.atDay(it) to now - day }
        val saved = SavedMonth(emptyList(), days = days)
        assertEquals("fetched yesterday and within the setting: nothing", emptyList<LocalDate?>(), due(saved, league = cricket))
        val stale = SavedMonth(emptyList(), days = days.mapValues { now - week })
        assertEquals("only today and the days still to come", (18..31).map(october::atDay).toSet(), due(stale, league = cricket).toSet())

        val unplayed = SavedMonth(listOf(match("c", at("2026-10-10T09:00:00Z"), MatchState.PRE, cricket)), days = days)
        assertEquals(listOf<LocalDate?>(LocalDate.of(2026, 10, 10)), due(unplayed, league = cricket))
    }

    // --- the view ---

    @Test
    fun `the view lays the live board over the saved copy and uses the device's own days`() {
        val saved = mapOf(
            Slot(bl.key, october) to SavedMonth(listOf(match("a", at("2026-10-18T13:30:00Z"), MatchState.PRE), match("b", at("2026-10-03T13:30:00Z")))),
            // Saved with September (ESPN's day), but 00:30 on 1 October in Berlin: it is October's.
            Slot(bl.key, YearMonth.of(2026, 9)) to SavedMonth(listOf(match("c", at("2026-09-30T22:30:00Z")))),
        )
        val board = SportsBoard(today = mapOf(bl.key to listOf(match("a", at("2026-10-18T13:30:00Z"), MatchState.IN))))
        val view = CalendarRules.view(october, listOf(bl), saved, board, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), today), view.keys.toList())
        assertEquals("c", view.getValue(LocalDate.of(2026, 10, 1)).single().id)
        assertEquals(MatchState.IN, view.getValue(today).single().state)
    }

    @Test
    fun `a saved month reads back as written`() {
        val m = SavedMonth(listOf(match("a", now)), fetchedAt = now, days = mapOf(today to now), seasonStart = at("2026-07-01T04:00:00Z"))
        assertEquals(m, SportsCalendar.read(SportsCalendar.write(m)))
        assertNull(SportsCalendar.read("{\"v\":0}"))
    }

    // --- the store ---

    private class FakeSource : SportsSource {
        val calls = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null
        var months: (League, YearMonth) -> MonthBoard = { _, _ -> MonthBoard(emptyList(), null) }

        override suspend fun month(league: League, month: YearMonth): MonthBoard = call("${league.key}@$month") { months(league, month) }
        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> = call("${league.key}@$day") { emptyList() }
        override suspend fun summary(match: Match) = MatchSummary(match, emptyList(), emptyList(), emptyList())
        override suspend fun standings(league: League) = StandingsTable(league.key, emptyList())
        override suspend fun teams(league: League): List<Team> = emptyList()
        override suspend fun rankings(league: League) = Ranking(league.key, emptyList())
        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean): List<Match> = emptyList()

        private suspend fun <T> call(name: String, result: () -> T): T {
            calls += name
            hold?.await()
            failWith?.let { throw it }
            return result()
        }
    }

    private fun TestScope.calendar(
        source: SportsSource,
        dir: File = tmp.newFolder(),
        followed: Set<String> = setOf(bl.key, cricket.key),
        gate: SportsGate = SportsGate(SportsRefreshPolicy()) { now },
    ) = SportsCalendar(
        source = source,
        gate = gate,
        board = MutableStateFlow(SportsBoard()),
        followed = MutableStateFlow(followed),
        refreshDays = MutableStateFlow(7),
        dataDir = dir,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = { now },
        zone = { zone },
    )

    @Test
    fun `a month is fetched once, saved, and not fetched again when shown again`() = runTest {
        val source = FakeSource().apply {
            hold = CompletableDeferred()
            months = { _, _ -> MonthBoard(listOf(match("a", at("2026-10-24T13:30:00Z"), MatchState.PRE)), at("2026-07-01T04:00:00Z")) }
        }
        val dir = tmp.newFolder()
        val cal = calendar(source, dir)
        backgroundScope.launch { cal.month(setOf(bl.key), october).collect {} }
        backgroundScope.launch { cal.month(setOf(bl.key), october).collect {} }
        runCurrent()
        assertEquals("two screens, one request", listOf("soccer/ger.1@2026-10"), source.calls)
        assertEquals(CalendarStatus.Loading, cal.month(setOf(bl.key), october).first().status)

        source.hold!!.complete(Unit)
        runCurrent()
        val shown = cal.month(setOf(bl.key), october).first()
        assertEquals(CalendarStatus.Ready, shown.status)
        assertEquals(listOf("a"), shown.days.values.flatten().map { it.id })
        assertEquals(LocalDate.of(2026, 7, 1), shown.seasonStart)
        assertTrue(File(dir, "sports/calendar/soccer_ger.1_2026-10.json").isFile)

        val again = calendar(source, dir)
        assertEquals(listOf("a"), again.month(setOf(bl.key), october).first().days.values.flatten().map { it.id })
        runCurrent()
        assertEquals("the saved copy is fresh enough", 1, source.calls.size)
    }

    @Test
    fun `leagues not followed are left out`() = runTest {
        val source = FakeSource()
        val cal = calendar(source, followed = setOf(cricket.key))
        backgroundScope.launch { cal.month(setOf(bl.key), october).collect {} }
        runCurrent()
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `offline shows what was saved, with nothing saved it fails`() = runTest {
        val dir = tmp.newFolder()
        File(dir, "sports/calendar").mkdirs()
        File(dir, "sports/calendar/soccer_ger.1_2026-10.json")
            .writeText(SportsCalendar.write(SavedMonth(listOf(match("a", at("2026-10-24T13:30:00Z"), MatchState.PRE)), fetchedAt = now - week)))
        val source = FakeSource().apply { failWith = java.io.IOException("offline") }
        val cal = calendar(source, dir)
        backgroundScope.launch { cal.month(setOf(bl.key), october).collect {} }
        runCurrent()
        assertEquals(CalendarStatus.Offline(now - week), cal.month(setOf(bl.key), october).first().status)

        val empty = calendar(source)
        backgroundScope.launch { empty.month(setOf(bl.key), october).collect {} }
        runCurrent()
        assertEquals(CalendarStatus.Failed, empty.month(setOf(bl.key), october).first().status)
    }

    @Test
    fun `a pause the board earned stops the calendar too`() = runTest {
        val gate = SportsGate(SportsRefreshPolicy()) { now }.apply { failed(429) }
        val source = FakeSource()
        val cal = calendar(source, gate = gate)
        backgroundScope.launch { cal.month(setOf(bl.key), october).collect {} }
        runCurrent()
        assertEquals(emptyList<String>(), source.calls)
        assertEquals(CalendarStatus.Failed, cal.month(setOf(bl.key), october).first().status)
    }

    @Test
    fun `refresh now makes the current and later months due again, never a past one`() = runTest {
        val dir = tmp.newFolder()
        File(dir, "sports/calendar").mkdirs()
        listOf("2026-09", "2026-10", "2026-11").forEach { m ->
            File(dir, "sports/calendar/soccer_ger.1_$m.json").writeText(SportsCalendar.write(SavedMonth(emptyList(), fetchedAt = now - day)))
        }
        File(dir, "sports/calendar/cricket_all_2026-10.json")
            .writeText(SportsCalendar.write(SavedMonth(emptyList(), days = mapOf(LocalDate.of(2026, 10, 17) to now - day, today to now - day))))
        val cal = calendar(FakeSource(), dir)
        cal.markStale()
        runCurrent()
        fun saved(name: String) = SportsCalendar.read(File(dir, "sports/calendar/$name.json").readText())!!
        assertEquals(now - day, saved("soccer_ger.1_2026-09").fetchedAt)
        assertEquals(0L, saved("soccer_ger.1_2026-10").fetchedAt)
        assertEquals(0L, saved("soccer_ger.1_2026-11").fetchedAt)
        assertEquals("yesterday stays, today is due", setOf(LocalDate.of(2026, 10, 17)), saved("cricket_all_2026-10").days.keys)
        assertEquals(listOf<LocalDate?>(null), due(saved("soccer_ger.1_2026-10")))
    }

    @Test
    fun `cricket asks day by day, the focused day first`() = runTest {
        val source = FakeSource()
        val cal = calendar(source)
        backgroundScope.launch { cal.month(setOf(cricket.key), october, focus = LocalDate.of(2026, 10, 20)).collect {} }
        runCurrent()
        assertEquals(31, source.calls.size)
        assertEquals("cricket/all@2026-10-20", source.calls.first())
    }
}
