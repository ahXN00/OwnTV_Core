package tv.own.owntv.core.sports

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.MatchSummary
import tv.own.owntv.core.sports.model.Ranking
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsTable
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Everything the sports screens show, as one flow. Every screen reads [board]; what keeps it fresh is
 * the set of screens currently asking ([observe]), turned into fetches by [SportsRefreshPolicy].
 *
 * [today] and [tomorrow] are keyed by feed (the followed league's key, `cricket/all` for cricket);
 * [summaries] by match key; [standings] and [rankings] by league key. [failing] holds the feeds whose last try failed,
 * and [paused] is the 15-minute stop after ESPN said 403 or 429 ("Scores paused"). [rebuiltAt] is when
 * the last rebuild finished (null: never on this device), [builtFor] the league keys it fetched.
 */
data class SportsBoard(
    val today: Map<String, List<Match>> = emptyMap(),
    val tomorrow: Map<String, List<Match>> = emptyMap(),
    val summaries: Map<String, MatchSummary> = emptyMap(),
    val standings: Map<String, StandingsTable> = emptyMap(),
    val rankings: Map<String, Ranking> = emptyMap(),
    val failing: Set<Feed> = emptySet(),
    val paused: Boolean = false,
    val rebuiltAt: Long? = null,
    val builtFor: Set<String>? = null,
    /** When this device first saw each match (by key) finish, having seen it under way: the real end, extra time included. */
    val endedAt: Map<String, Long> = emptyMap(),
) {
    /** [fresh] copies replace older ones: a match seen under way and now finished gets its end time [now]. */
    fun withEnds(fresh: List<Match>, now: Long): SportsBoard {
        val ends = fresh.filter { m ->
            m.state == MatchState.POST && m.key !in endedAt && match(m.key)?.state == MatchState.IN
        }
        return if (ends.isEmpty()) this else copy(endedAt = endedAt + ends.associate { it.key to now })
    }
    /** Every match on the board, today's first, each once. */
    val matches: List<Match> get() = (today.values.flatten() + tomorrow.values.flatten()).distinctBy { it.key }

    fun match(key: String): Match? = summaries[key]?.match ?: matches.firstOrNull { it.key == key }
}

/** Where the board's rebuild stands, for the status pill: [Running] counts the fetches done of all planned. */
sealed interface RebuildState {
    data object Idle : RebuildState
    data class Running(val done: Int, val total: Int) : RebuildState
    data object Failed : RebuildState
}

/**
 * The rebuild's state for the app's bottom status pill. Its own tiny class, as the sync and Trending
 * trackers are, so the pill can watch it without creating the sports repository at app start.
 */
class SportsActivityTracker {
    val rebuild = MutableStateFlow<RebuildState>(RebuildState.Idle)

    /** When the stored board was last rebuilt (null: never, or not read yet); kept when the board leaves memory. */
    val rebuiltAt = MutableStateFlow<Long?>(null)
}

/** Whether Find channels may run for a match now. */
sealed interface WatchWindow {
    data object Open : WatchWindow
    data class TooEarly(val opensAt: Long) : WatchWindow
    data object Ended : WatchWindow

    companion object {
        const val BEFORE_MS = 30 * 60_000L
        const val AFTER_MS = 30 * 60_000L

        /** A finished match this device never saw end is taken to have ended this long after kick-off. */
        const val ASSUMED_LENGTH_MS = 2 * 60 * 60_000L

        /**
         * From 30 min before kick-off to 30 min after the end, judged on a freshly fetched [m]: live is
         * always open, however long it runs; postponed and cancelled never are.
         */
        fun of(m: Match, endedAt: Long?, now: Long): WatchWindow = when (m.state) {
            MatchState.IN -> Open
            MatchState.POSTPONED, MatchState.CANCELLED -> Ended
            MatchState.PRE -> if (now >= m.startMs - BEFORE_MS) Open else TooEarly(m.startMs - BEFORE_MS)
            MatchState.POST -> if (now < (endedAt ?: (m.startMs + ASSUMED_LENGTH_MS)) + AFTER_MS) Open else Ended
        }
    }
}

/**
 * Which matches the stored board keeps: one starting within [AHEAD_MS], a live one always, a
 * finished one until [KEEP_AFTER_END_MS] after its end; postponed and cancelled ones never.
 */
object SportsWindow {
    const val AHEAD_MS = 36 * 60 * 60_000L
    const val KEEP_AFTER_END_MS = 12 * 60 * 60_000L

    /** ESPN gives no end time, so a match is taken to end this long after its kick-off. */
    const val LENGTH_MS = 3 * 60 * 60_000L

    fun keeps(m: Match, now: Long): Boolean = when (m.state) {
        MatchState.IN -> true
        MatchState.POSTPONED, MatchState.CANCELLED -> false
        MatchState.PRE -> m.startMs <= now + AHEAD_MS && now < m.startMs + LENGTH_MS + KEEP_AFTER_END_MS
        MatchState.POST -> now < m.startMs + LENGTH_MS + KEEP_AFTER_END_MS
    }

    /** [board] without the matches the window no longer keeps, and without their details. */
    fun prune(board: SportsBoard, now: Long): SportsBoard {
        val today = board.today.mapValues { (_, l) -> l.filter { keeps(it, now) } }
        val tomorrow = board.tomorrow.mapValues { (_, l) -> l.filter { keeps(it, now) } }
        val kept = (today.values.flatten() + tomorrow.values.flatten()).mapTo(HashSet()) { it.key }
        return board.copy(today = today, tomorrow = tomorrow, summaries = board.summaries.filterKeys { it in kept })
    }

    /** A match of two teams has a summary worth storing (timeline, stats, line-ups, form); an athletes' one does not. */
    fun hasDetails(m: Match): Boolean = m.competitors.size == 2 && m.competitors.none { it.team.isAthlete }

    /** Sports whose leagues have a table, less the cups; cricket's one feed is every series, so it has none either. */
    fun hasTable(league: League): Boolean = league.hasTable && league.sport !in NO_TABLE

    private val NO_TABLE = setOf(Sport.TENNIS, Sport.RACING, Sport.GOLF, Sport.MMA, Sport.CRICKET)

    /** The leagues ranked instead of tabled: every racing series' drivers (F1's constructors too), the tennis tours' players. ESPN ranks no golfers. */
    fun hasRanking(league: League): Boolean = league.sport == Sport.RACING || league.key in RANKED_TOURS

    private val RANKED_TOURS = setOf("tennis/atp", "tennis/wta")
}

/**
 * The single owner of sports data. The board is built by [rebuild] and stored in
 * `filesDir/sports/` until the next one, so a cold start shows it at once. A rebuild runs by
 * itself as the Auto rebuild setting says ([appStarted], or while a screen is open); live matches are
 * refreshed only while a screen collects [observe]. When the last screen stops, polling stops, and five
 * minutes later the board is dropped from memory.
 *
 * A feed already being fetched is never fetched twice, and at most [SportsGate.MAX_PARALLEL] run at once. All
 * state is touched on one serial dispatcher, so none of it needs a lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsRepository(
    private val source: SportsSource,
    private val policy: SportsRefreshPolicy,
    private val followed: Flow<Set<String>>,
    private val liveRefreshSeconds: Flow<Int>,
    private val autoRebuild: Flow<AutoRebuild>,
    dataDir: File,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activity: SportsActivityTracker = SportsActivityTracker(),
    private val gate: SportsGate = SportsGate(policy, clock),
) {
    private val serial = dispatcher.limitedParallelism(1)
    private val cacheFile = File(File(dataDir, CACHE_DIR), CACHE_FILE)

    private val _board = MutableStateFlow(SportsBoard())
    val board: StateFlow<SportsBoard> = _board.asStateFlow()

    /** One entry per collecting screen, so two screens asking for the same thing are counted twice. */
    private val demands = MutableStateFlow<List<Demand>>(emptyList())

    // Confined to [serial].
    private val states = HashMap<Feed, FeedState>()
    private val inFlight = HashMap<Feed, Job>()
    private var cacheLoaded = false
    private var engine: Job? = null
    private var rebuildJob: Job? = null
    private var rebuildTriedAt: Long? = null
    private var rebuiltThisRun = false

    private val _rebuild get() = activity.rebuild
    val rebuild: StateFlow<RebuildState> = activity.rebuild.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The board, kept fresh for [demand] while it is collected. The demand is registered when
     * collection starts and withdrawn when it stops, so a screen needs no explicit start or stop.
     */
    fun observe(demand: Demand): Flow<SportsBoard> = board
        .onStart {
            demands.update { it + demand }
            scope.launch(serial) { if (engine == null) engine = scope.launch(serial) { run() } }
        }
        .onCompletion { demands.update { it - demand } }

    /**
     * The app has started and finished its own startup work: rebuild once if Auto rebuild says the
     * stored board is due ("on every app start", or older than the chosen hours). The app calls this
     * after its startup, so nothing here is on the startup path.
     */
    fun appStarted() {
        scope.launch(serial) {
            loadCache()
            if (rebuildDue(autoRebuild.first(), followed.first(), clock())) rebuild()
        }
    }

    private fun rebuildDue(mode: AutoRebuild, follow: Set<String>, now: Long): Boolean {
        val b = _board.value
        return policy.rebuildDue(mode, follow, b.rebuiltAt, b.builtFor, rebuildTriedAt, rebuiltThisRun, now)
    }

    /**
     * [match] fetched afresh now (its summary), for a page that must judge it on current data — Find
     * channels' window. Updates the board; null when the fetch fails (the caller keeps the copy it has).
     */
    suspend fun refetch(match: Match): Match? = try {
        val summary = gate.permits.withPermit { source.summary(match) }
        _board.update { it.withEnds(listOf(summary.match), clock()).copy(summaries = it.summaries + (match.key to summary)) }
        summary.match
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "refetch failed error=${e.javaClass.simpleName}")
        null
    }

    /**
     * Builds the board again: today's and tomorrow's scoreboard of every followed league,
     * each two-team match's details and each league's table or ranking, kept to [SportsWindow], then swapped in at
     * once. A league or a match whose fetch fails keeps what the board had; a finished match ESPN's new
     * day no longer lists stays its 12 hours. One at a time: a call while one runs is ignored.
     */
    fun rebuild() {
        scope.launch(serial) {
            if (rebuildJob?.isActive == true) return@launch
            rebuildTriedAt = clock()
            rebuildJob = scope.launch(serial) {
                try {
                    runRebuild()
                } finally {
                    wake.trySend(Unit)
                }
            }
        }
    }

    private suspend fun runRebuild() {
        loadCache()
        val now = clock()
        val follow = followed.first()
        val leagues = follow.mapNotNull(SportsCatalog::league)
        val nextDay = espnToday().plusDays(1)
        var done = 0
        var total = leagues.size * 2
        fun step() {
            _rebuild.value = RebuildState.Running(++done, total)
        }
        _rebuild.value = RebuildState.Running(0, total)

        val boards = coroutineScope {
            leagues.flatMap { l ->
                listOf(false, true).map { tomorrow ->
                    async { Triple(l, tomorrow, attempt { source.scoreboard(l, if (tomorrow) nextDay else null) }.also { step() }) }
                }
            }.awaitAll()
        }
        if (boards.isNotEmpty() && boards.all { it.third == null }) {
            _rebuild.value = RebuildState.Failed
            return
        }
        val old = _board.value
        fun day(tomorrow: Boolean): Map<String, List<Match>> = boards.filter { it.second == tomorrow }.associate { (l, _, fresh) ->
            l.key to (fresh ?: (if (tomorrow) old.tomorrow else old.today)[l.key].orEmpty()).filter { SportsWindow.keeps(it, now) }
        }
        val today = day(false).toMutableMap()
        val tomorrow = day(true)
        val listed = (today.values.flatten() + tomorrow.values.flatten()).mapTo(HashSet()) { it.key }
        old.matches.filter { it.state == MatchState.POST && it.feed in today && it.key !in listed && SportsWindow.keeps(it, now) }
            .forEach { today[it.feed] = today[it.feed].orEmpty() + it }

        val matches = (today.values.flatten() + tomorrow.values.flatten()).distinctBy { it.key }.filter(SportsWindow::hasDetails)
        // A finished match's details no longer change: the stored copy is reused.
        val (reused, wanted) = matches.partition { it.state == MatchState.POST && old.summaries[it.key]?.match?.state == MatchState.POST }
        val tabled = leagues.filter(SportsWindow::hasTable)
        val ranked = leagues.filter(SportsWindow::hasRanking)
        total += wanted.size + tabled.size + ranked.size
        _rebuild.value = RebuildState.Running(done, total)
        val (summaries, standings, rankings) = coroutineScope {
            val s = wanted.map { m -> async { m.key to (attempt { source.summary(m) }.also { step() } ?: old.summaries[m.key]) } }
            val t = tabled.map { l -> async { l.key to (attempt { source.standings(l) }.also { step() } ?: old.standings[l.key]) } }
            val r = ranked.map { l -> async { l.key to (attempt { source.rankings(l) }.also { step() } ?: old.rankings[l.key]) } }
            Triple(s.awaitAll(), t.awaitAll(), r.awaitAll())
        }

        _board.update {
            it.copy(
                today = today,
                tomorrow = tomorrow,
                summaries = reused.associate { m -> m.key to old.summaries.getValue(m.key) } +
                    summaries.mapNotNull { (k, v) -> v?.let { k to it } },
                standings = standings.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
                rankings = rankings.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
                failing = boards.filter { b -> b.third == null }.mapTo(HashSet()) { (l, next, _) -> Feed.Scoreboard(l.key, next) },
                rebuiltAt = now,
                builtFor = follow,
                endedAt = it.withEnds(today.values.flatten() + tomorrow.values.flatten(), now).endedAt,
            )
        }
        seed(_board.value, now, replace = true)
        activity.rebuiltAt.value = now
        rebuiltThisRun = true
        saveCache()
        _rebuild.value = if (gate.paused()) RebuildState.Failed else RebuildState.Idle
    }

    /** One rebuild fetch: its result, or null when it failed or ESPN asked us to pause (403/429). */
    private suspend fun <T> attempt(call: suspend () -> T): T? = gate.permits.withPermit {
        if (gate.paused()) return@withPermit null
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? SportsHttpException)?.code
            Log.w(TAG, "rebuild fetch failed code=${code ?: -1} error=${e.javaClass.simpleName}")
            gate.failed(code)
            null
        }
    }

    private suspend fun run() {
        demands.map { it.toSet() }.distinctUntilChanged().collectLatest { asked ->
            if (asked.isEmpty()) {
                delay(DROP_AFTER_MS)
                drop()
                return@collectLatest
            }
            loadCache()
            combine(followed, liveRefreshSeconds, autoRebuild) { f, s, m -> Triple(f, s, m) }.collectLatest { (follow, seconds, mode) ->
                while (true) {
                    val now = clock()
                    if (rebuildDue(mode, follow, now)) rebuild()
                    val plan = policy.plan(asked, follow, HashMap(states), now, seconds, gate.pausedUntil)
                    if (_board.value.paused != plan.paused) _board.update { it.copy(paused = plan.paused) }
                    plan.fetch.forEach { start(it, asked, now) }
                    val b = _board.value
                    val next = (listOfNotNull(plan.nextWakeAt) + policy.rebuildWakes(mode, b.rebuiltAt, rebuildTriedAt).filter { it > now }).minOrNull()
                    val wait = next?.let { (it - now).coerceAtLeast(MIN_WAIT_MS) }
                    if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        }
    }

    /** Starts [feed] unless it is already running. Marks it tried at once, so the next plan does not ask again. */
    private fun start(feed: Feed, asked: Set<Demand>, now: Long) {
        if (feed in inFlight) return
        states[feed] = (states[feed] ?: FeedState()).copy(attemptedAt = now)
        inFlight[feed] = scope.launch(serial) {
            try {
                gate.permits.withPermit { fetch(feed, asked) }
            } finally {
                inFlight.remove(feed)
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun fetch(feed: Feed, asked: Set<Demand>) {
        val tried = states[feed]?.attemptedAt ?: clock()
        try {
            val matches: List<Match> = when (feed) {
                is Feed.Scoreboard -> {
                    val league = SportsCatalog.league(feed.leagueKey) ?: throw IllegalArgumentException("not in the catalogue")
                    val day = if (feed.tomorrow) espnToday().plusDays(1) else null
                    source.scoreboard(league, day).also { m -> _board.update { b -> b.withScoreboard(feed, m, clock()) } }
                }
                is Feed.Summary -> {
                    val match = asked.firstNotNullOfOrNull { (it as? Demand.MatchPage)?.match?.takeIf { m -> m.key == feed.matchKey } }
                        ?: _board.value.match(feed.matchKey) ?: return
                    val summary = source.summary(match)
                    _board.update { it.withEnds(listOf(summary.match), clock()).copy(summaries = it.summaries + (feed.matchKey to summary), failing = it.failing - feed) }
                    listOf(summary.match)
                }
                is Feed.Standings -> {
                    val league = SportsCatalog.league(feed.leagueKey) ?: throw IllegalArgumentException("not in the catalogue")
                    val table = source.standings(league)
                    _board.update { it.copy(standings = it.standings + (feed.leagueKey to table), failing = it.failing - feed) }
                    emptyList()
                }
                is Feed.Rankings -> {
                    val league = SportsCatalog.league(feed.leagueKey) ?: throw IllegalArgumentException("not in the catalogue")
                    val ranking = source.rankings(league)
                    _board.update { it.copy(rankings = it.rankings + (feed.leagueKey to ranking), failing = it.failing - feed) }
                    emptyList()
                }
            }
            states[feed] = FeedState(fetchedAt = tried, attemptedAt = tried, matches = matches)
            if (feed is Feed.Scoreboard) saveCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? SportsHttpException)?.code
            Log.w(TAG, "fetch failed feed=$feed code=${code ?: -1} error=${e.javaClass.simpleName}")
            gate.failed(code)
            val previous = states[feed] ?: FeedState()
            states[feed] = previous.copy(attemptedAt = tried, failures = previous.failures + 1)
            _board.update { it.copy(failing = it.failing + feed) }
        }
    }

    /** A refreshed scoreboard updates the board's copies of its matches; what it no longer lists stays while the window keeps it. */
    private fun SportsBoard.withScoreboard(feed: Feed.Scoreboard, fresh: List<Match>, now: Long): SportsBoard {
        val had = (if (feed.tomorrow) tomorrow else today)[feed.leagueKey].orEmpty()
        val keys = fresh.mapTo(HashSet()) { it.key }
        val merged = (fresh + had.filter { it.key !in keys }).filter { SportsWindow.keeps(it, now) }
        val b = withEnds(fresh, now)
        return if (feed.tomorrow) b.copy(tomorrow = tomorrow + (feed.leagueKey to merged), failing = failing - feed)
        else b.copy(today = today + (feed.leagueKey to merged), failing = failing - feed)
    }

    /** ESPN's "today" is the US Eastern day; tomorrow's board is the day after that one. */
    private fun espnToday() = Instant.ofEpochMilli(clock()).atZone(ESPN_ZONE).toLocalDate()

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        val text = runCatching { cacheFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val stored = SportsCacheJson.read(text)?.let { SportsWindow.prune(it, clock()) } ?: return
        // Only fills what is still empty: a fetch that finished first is newer than the file.
        _board.update { b ->
            b.copy(
                today = stored.today + b.today,
                tomorrow = stored.tomorrow + b.tomorrow,
                summaries = stored.summaries + b.summaries,
                standings = stored.standings + b.standings,
                rankings = stored.rankings + b.rankings,
                rebuiltAt = b.rebuiltAt ?: stored.rebuiltAt,
                builtFor = b.builtFor ?: stored.builtFor,
                endedAt = stored.endedAt + b.endedAt,
            )
        }
        seed(stored, stored.rebuiltAt ?: clock(), replace = false)
        stored.rebuiltAt?.let { at -> activity.rebuiltAt.update { maxOf(it ?: 0L, at) } }
    }

    /**
     * Tells the refresh policy what [board] holds, as of [at]: which matches each scoreboard has (so it
     * knows what is live and when the next kick-off is) and which details and tables need no fetch.
     */
    private fun seed(board: SportsBoard, at: Long, replace: Boolean) {
        fun put(feed: Feed, state: FeedState) {
            if (replace || feed !in states) states[feed] = state
        }
        board.today.forEach { (k, l) -> put(Feed.Scoreboard(k), FeedState(at, at, 0, l)) }
        board.tomorrow.forEach { (k, l) -> put(Feed.Scoreboard(k, tomorrow = true), FeedState(at, at, 0, l)) }
        board.summaries.forEach { (k, sum) -> put(Feed.Summary(k), FeedState(at, at, 0, listOf(sum.match))) }
        board.standings.keys.forEach { put(Feed.Standings(it), FeedState(at, at)) }
        board.rankings.keys.forEach { put(Feed.Rankings(it), FeedState(at, at)) }
    }

    private fun saveCache() {
        val b = _board.value
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(SportsCacheJson.write(b))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        }.onFailure { Log.w(TAG, "cache write failed: ${it.javaClass.simpleName}") }
    }

    /** No screen for [DROP_AFTER_MS]: forget the board (the stored file stays for the next screen). */
    private fun drop() {
        states.clear()
        cacheLoaded = false
        _board.value = SportsBoard()
    }

    private companion object {
        const val TAG = "OwnTV-Sports"
        const val MIN_WAIT_MS = 1_000L
        const val DROP_AFTER_MS = 5 * 60_000L
        const val CACHE_DIR = "sports"
        const val CACHE_FILE = "board.json"
        val ESPN_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}
