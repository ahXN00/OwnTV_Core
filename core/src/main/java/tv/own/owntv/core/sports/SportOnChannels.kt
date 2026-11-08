package tv.own.owntv.core.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.model.Match

/**
 * A sport as a *guide* files it — wider than ESPN's [tv.own.owntv.core.sports.model.Sport], because
 * this row exists for the sports ESPN does not cover (handball, darts, snooker, cycling). Declaration
 * order is the row's group order. [OTHER] is a fixture on a sports channel whose sport is not named.
 */
enum class GuideSport {
    FOOTBALL, HANDBALL, BASKETBALL, TENNIS, ICE_HOCKEY, MOTORSPORT, DARTS, SNOOKER, CYCLING, CRICKET,
    COMBAT, AMERICAN_FOOTBALL, BASEBALL, GOLF, RUGBY, VOLLEYBALL, ATHLETICS, WINTER, OTHER,
}

/** One card in "Sport on your channels now" (the hub's middle row): a programme and every channel showing it. */
data class GuideSportItem(
    val sport: GuideSport,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val channels: List<ChannelEntity>,
)

/**
 * What is on now, or starting within [SOON_MS], on the user's channels
 * that is sport — by the guide's category, by a sport word in the title, or as a fixture ("Kiel –
 * Magdeburg") on a sports channel. Local only: the guide and the playlist, no network, so it keeps
 * working when ESPN is unreachable. Programmes the guide already confirmed for one of [observe]'s
 * matches are left out; the hub shows those as matches.
 */
class SportOnChannels(private val data: WatchData, private val now: () -> Long = System::currentTimeMillis) {

    /** The row's content, again every [REFRESH_MS] while collected. */
    fun observe(matches: () -> List<Match> = { emptyList() }): Flow<Map<GuideSport, List<GuideSportItem>>> = flow {
        while (true) {
            emit(read(matches()))
            delay(REFRESH_MS)
        }
    }

    suspend fun read(matches: List<Match> = emptyList()): Map<GuideSport, List<GuideSportItem>> = withContext(Dispatchers.Default) { readNow(matches) }

    private suspend fun readNow(matches: List<Match>): Map<GuideSport, List<GuideSportItem>> {
        val t = now()
        val keys = matches.map { WatchMatcher.Keys(it) }
        val candidates = data.programmes(t - LOOKBACK_MS, t + SOON_MS, minStop = t).mapNotNull { p ->
            val tokens = TeamNames.tokens(p.title).toSet()
            val words = CompetitionNames.words(p.title)
            if (keys.any { WatchMatcher.hit(it, tokens, words) == WatchMatcher.Hit.CONFIRMED }) return@mapNotNull null
            val sport = sportOf(p.title, p.categories)
            when {
                sport != null -> Triple(p, sport, false)
                isFixture(p.title) -> Triple(p, GuideSport.OTHER, true)
                else -> null
            }
        }
        if (candidates.isEmpty()) return emptyMap()
        val channels = data.channelsForGuideKeys(candidates.map { it.first.epgChannelId }.toSet())
        return candidates
            .mapNotNull { (p, sport, needsSportsChannel) ->
                // A bare fixture counts only on a sports channel: "Liebe – Lügen" on a drama channel is not sport.
                val on = channels[p.epgChannelId].orEmpty()
                    .filter { !needsSportsChannel || isSportsChannel(it.name) || isSportsChannel(p.epgChannelId) }
                if (on.isEmpty()) null else Triple(p, sport, on)
            }
            // One feed carried by two playlists, or two guides for one channel: one card.
            .groupBy { (p, _, _) -> p.title.trim().lowercase() to p.startMs }
            .map { (_, same) ->
                val (p, sport, _) = same.first()
                GuideSportItem(sport, p.title.trim(), p.startMs, p.stopMs, same.flatMap { it.third }.distinctBy { it.id })
            }
            .sortedWith(compareBy({ it.sport.ordinal }, { it.startMs }))
            .groupBy { it.sport }
    }

    companion object {
        const val REFRESH_MS = 5 * 60_000L
        const val SOON_MS = 30 * 60_000L

        /** How long before now a programme may have started and still be on (a Test match day, a GP). */
        private const val LOOKBACK_MS = 8 * 60 * 60_000L

        // Guide words per sport, after TeamNames folding (lower case, no accents). Several languages,
        // because the guide speaks the user's: German, English, French, Spanish, Italian, Turkish.
        private val WORDS: List<Pair<GuideSport, Set<String>>> = listOf(
            GuideSport.HANDBALL to setOf("handball", "balonmano", "pallamano", "hentbol", "hbl", "ehf"),
            GuideSport.DARTS to setOf("darts", "dart", "pdc"),
            GuideSport.SNOOKER to setOf("snooker"),
            GuideSport.CYCLING to setOf("radsport", "cycling", "cyclisme", "ciclismo", "bisiklet", "radrennen"),
            GuideSport.TENNIS to setOf("tennis", "tenis", "atp", "wta"),
            GuideSport.BASKETBALL to setOf("basketball", "basket", "baloncesto", "basketbol", "nba", "euroleague", "bbl"),
            GuideSport.ICE_HOCKEY to setOf("eishockey", "hockey", "nhl", "del", "hokey"),
            GuideSport.MOTORSPORT to setOf("motorsport", "formel", "formula", "motogp", "nascar", "indycar", "rallye", "rally", "dtm", "superbike", "grand", "prix"),
            GuideSport.CRICKET to setOf("cricket", "ipl", "odi"),
            GuideSport.COMBAT to setOf("boxen", "boxing", "boxe", "boks", "ufc", "mma", "kickboxing", "wrestling", "wwe"),
            GuideSport.AMERICAN_FOOTBALL to setOf("nfl", "superbowl", "ncaaf"),
            GuideSport.BASEBALL to setOf("baseball", "mlb", "beisbol"),
            GuideSport.GOLF to setOf("golf", "pga"),
            GuideSport.RUGBY to setOf("rugby"),
            GuideSport.VOLLEYBALL to setOf("volleyball", "volley", "voleibol", "voleybol", "pallavolo"),
            GuideSport.ATHLETICS to setOf("leichtathletik", "athletics", "athletisme", "atletismo", "marathon"),
            GuideSport.WINTER to setOf("biathlon", "skispringen", "ski", "langlauf", "bobsleigh", "rodeln"),
            GuideSport.FOOTBALL to setOf(
                "fussball", "fusball", "football", "soccer", "futbol", "calcio", "futebol", "voetbal",
                "bundesliga", "premier", "league", "laliga", "liga", "serie", "ligue", "champions", "europa", "pokal", "dfb", "uefa", "fifa", "super", "lig",
            ),
        )

        // "American football" is not football; "Formel 1" is not "1"; these phrases decide first.
        private val PHRASES: List<Pair<GuideSport, List<String>>> = listOf(
            GuideSport.AMERICAN_FOOTBALL to listOf("american football", "football americain", "futbol americano"),
            GuideSport.WINTER to listOf("ski alpin", "alpine skiing", "ski jumping"),
            GuideSport.ICE_HOCKEY to listOf("ice hockey"),
            GuideSport.MOTORSPORT to listOf("formula 1", "formula one", "formel 1", "grand prix", "grosser preis"),
        )

        // XMLTV categories that say "this is sport" without saying which.
        private val SPORT_CATEGORY = setOf("sport", "sports", "deporte", "deportes", "spor", "esporte", "live sport")

        // A sport-word that alone is too common in other programmes' titles ("Grand Designs", "Super Size").
        private val WEAK = setOf("grand", "prix", "super", "lig", "liga", "serie", "premier", "league", "europa", "champions", "formula", "basket", "del", "odi", "ski")

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val FIXTURE =Regex("\\S\\s+(?:[-–—:]|v|vs\\.?|gegen|contre|x)\\s+\\S", RegexOption.IGNORE_CASE)
        private val SPORTS_CHANNEL = Regex(
            "sport|dazn|eurosport|bein|espn|tsn|arena|eleven|setanta|tnt ?sport|premier ?sports|canal\\+ ?(?:sport|foot)|movistar ?(?:deportes|liga)|dyn|magenta ?sport|sky ?sport|laliga ?tv|nba ?tv|nfl|mlb|nhl",
            RegexOption.IGNORE_CASE,
        )

        /** The sport a programme is, from its categories and title; null when neither names one. */
        fun sportOf(title: String, categories: String?): GuideSport? {
            val text = listOfNotNull(categories, title).joinToString(" ")
            val raw = text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
            // Both spellings: folded ("americain") and raw with its digits ("formula 1").
            val phrases = " ${TeamNames.tokens(text).joinToString(" ")} | ${raw.joinToString(" ")} "
            PHRASES.firstOrNull { (_, ps) -> ps.any { " $it " in phrases } }?.let { return it.first }
            val tokens = TeamNames.tokens(text).toSet() + raw
            val catTokens = categories?.let { TeamNames.tokens(it).toSet() }.orEmpty()
            for ((sport, words) in WORDS) {
                val strong = words - WEAK
                if (tokens.any { it in strong }) return sport
                // A weak word counts when a sports category backs it ("Super Lig" under "Sport").
                if (catTokens.any { it in SPORT_CATEGORY } && tokens.any { it in words }) return sport
                if (tokens.any { it in words } && words.count { it in tokens } >= 2) return sport
            }
            return null
        }

        fun isFixture(title: String): Boolean = FIXTURE.containsMatchIn(title)

        /** A channel (by name or guide id) that carries sport. */
        fun isSportsChannel(nameOrId: String): Boolean = SPORTS_CHANNEL.containsMatchIn(nameOrId)
    }
}
