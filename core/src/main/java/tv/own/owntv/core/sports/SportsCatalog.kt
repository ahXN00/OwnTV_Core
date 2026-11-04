package tv.own.owntv.core.sports

import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport

/**
 * The curated list behind Settings → Sports → Sports and leagues: what can be followed, in the order the
 * picker shows it (sports in [Sport] order, leagues as listed here). Every slug was checked against ESPN
 * on 2026-10-18. Competition names are the competitions' own brand names and are not translated.
 *
 * Cricket is one entry, `cricket/all`: ESPN's cricket header carries every series of the day, and a
 * series lasts weeks, so following series by slug would go stale.
 */
object SportsCatalog {

    val leagues: List<League> = listOf(
        // Football (soccer)
        soccer("uefa.champions", "Champions League"),
        soccer("uefa.europa", "Europa League"),
        soccer("uefa.europa.conf", "Conference League"),
        soccer("uefa.nations", "Nations League"),
        soccer("uefa.super_cup", "UEFA Super Cup", table = false),
        soccer("uefa.wchampions", "Women's Champions League"),
        soccer("fifa.world", "FIFA World Cup"),
        soccer("fifa.wwc", "FIFA Women's World Cup"),
        soccer("fifa.cwc", "FIFA Club World Cup"),
        soccer("fifa.worldq.uefa", "World Cup Qualifying – UEFA"),
        soccer("fifa.worldq.conmebol", "World Cup Qualifying – CONMEBOL"),
        soccer("fifa.worldq.concacaf", "World Cup Qualifying – Concacaf"),
        soccer("fifa.worldq.afc", "World Cup Qualifying – AFC"),
        soccer("fifa.worldq.caf", "World Cup Qualifying – CAF"),
        soccer("fifa.friendly", "International Friendlies", table = false),
        soccer("uefa.euro", "EURO"),
        soccer("uefa.weuro", "Women's EURO"),
        soccer("eng.1", "Premier League", "GB"),
        soccer("eng.2", "Championship", "GB"),
        soccer("eng.3", "League One", "GB"),
        soccer("eng.fa", "FA Cup", "GB", table = false),
        soccer("eng.league_cup", "Carabao Cup", "GB", table = false),
        soccer("eng.charity", "Community Shield", "GB", table = false),
        soccer("eng.w.1", "Women's Super League", "GB"),
        soccer("sco.1", "Scottish Premiership", "GB"),
        soccer("sco.tennents", "Scottish Cup", "GB", table = false),
        soccer("sco.cis", "Scottish League Cup", "GB"),
        soccer("ger.1", "Bundesliga", "DE"),
        soccer("ger.2", "2. Bundesliga", "DE"),
        soccer("ger.dfb_pokal", "DFB-Pokal", "DE", table = false),
        soccer("esp.1", "LaLiga", "ES"),
        soccer("esp.2", "LaLiga 2", "ES"),
        soccer("esp.copa_del_rey", "Copa del Rey", "ES", table = false),
        soccer("esp.super_cup", "Supercopa de España", "ES", table = false),
        soccer("ita.1", "Serie A", "IT"),
        soccer("ita.2", "Serie B", "IT"),
        soccer("ita.coppa_italia", "Coppa Italia", "IT", table = false),
        soccer("ita.super_cup", "Supercoppa Italiana", "IT", table = false),
        soccer("fra.1", "Ligue 1", "FR"),
        soccer("fra.2", "Ligue 2", "FR"),
        soccer("fra.coupe_de_france", "Coupe de France", "FR", table = false),
        soccer("ned.1", "Eredivisie", "NL"),
        soccer("ned.cup", "KNVB Beker", "NL", table = false),
        soccer("por.1", "Liga Portugal", "PT"),
        soccer("por.taca.portugal", "Taça de Portugal", "PT", table = false),
        soccer("bel.1", "Pro League", "BE"),
        soccer("aut.1", "Austrian Bundesliga", "AT"),
        soccer("sui.1", "Swiss Super League", "CH"),
        soccer("den.1", "Superliga", "DK"),
        soccer("nor.1", "Eliteserien", "NO"),
        soccer("swe.1", "Allsvenskan", "SE"),
        soccer("gre.1", "Greek Super League", "GR"),
        soccer("tur.1", "Süper Lig", "TR"),
        soccer("tur.2", "1. Lig", "TR"),
        soccer("ksa.1", "Saudi Pro League", "SA"),
        soccer("ksa.kings.cup", "King's Cup", "SA", table = false),
        soccer("afc.champions", "AFC Champions League Elite"),
        soccer("afc.cup", "AFC Champions League Two"),
        soccer("afc.asian.cup", "AFC Asian Cup"),
        soccer("ind.1", "Indian Super League", "IN"),
        soccer("caf.champions", "CAF Champions League"),
        soccer("caf.nations", "Africa Cup of Nations"),
        soccer("usa.1", "MLS", "US"),
        soccer("usa.nwsl", "NWSL", "US"),
        soccer("mex.1", "Liga MX", "MX"),
        soccer("concacaf.champions", "Concacaf Champions Cup", table = false),
        soccer("concacaf.leagues.cup", "Leagues Cup"),
        soccer("concacaf.gold", "Concacaf Gold Cup"),
        soccer("bra.1", "Brasileirão", "BR"),
        soccer("bra.copa_do_brazil", "Copa do Brasil", "BR", table = false),
        soccer("arg.1", "Liga Profesional", "AR"),
        soccer("conmebol.libertadores", "Copa Libertadores"),
        soccer("conmebol.sudamericana", "Copa Sudamericana"),
        soccer("conmebol.america", "Copa América"),
        // Basketball
        League(Sport.BASKETBALL, "nba", "NBA", country = "US"),
        League(Sport.BASKETBALL, "wnba", "WNBA", country = "US"),
        League(Sport.BASKETBALL, "mens-college-basketball", "NCAA Basketball", "NCAAM", "US"),
        League(Sport.BASKETBALL, "womens-college-basketball", "NCAA Women's Basketball", "NCAAW", "US"),
        League(Sport.BASKETBALL, "fiba", "FIBA World Cup"),
        // Tennis
        League(Sport.TENNIS, "atp", "ATP"),
        League(Sport.TENNIS, "wta", "WTA"),
        // Cricket
        League(Sport.CRICKET, CRICKET_ALL, "Cricket"),
        // Motorsport
        League(Sport.RACING, "f1", "Formula 1", "F1"),
        League(Sport.RACING, "irl", "IndyCar", country = "US"),
        League(Sport.RACING, "nascar-premier", "NASCAR Cup Series", "NASCAR", "US"),
        League(Sport.RACING, "nascar-secondary", "NASCAR O'Reilly Auto Parts Series", "NASCAR O'Reilly", "US"),
        League(Sport.RACING, "nascar-truck", "NASCAR Truck Series", "NASCAR Truck", "US"),
        // Ice hockey
        League(Sport.HOCKEY, "nhl", "NHL", country = "US"),
        League(Sport.HOCKEY, "olympics-mens-ice-hockey", "Olympic Men's Ice Hockey"),
        League(Sport.HOCKEY, "olympics-womens-ice-hockey", "Olympic Women's Ice Hockey"),
        // American football
        League(Sport.FOOTBALL, "nfl", "NFL", country = "US"),
        League(Sport.FOOTBALL, "college-football", "College Football", "NCAAF", "US"),
        // MMA
        League(Sport.MMA, "ufc", "UFC"),
        League(Sport.MMA, "pfl", "PFL"),
        League(Sport.MMA, "ofc", "ONE Championship", "ONE"),
        // Baseball
        League(Sport.BASEBALL, "mlb", "MLB", country = "US"),
        League(Sport.BASEBALL, "world-baseball-classic", "World Baseball Classic", hasTable = false),
        // Golf
        League(Sport.GOLF, "pga", "PGA Tour"),
        League(Sport.GOLF, "eur", "DP World Tour"),
        League(Sport.GOLF, "lpga", "LPGA Tour"),
        League(Sport.GOLF, "liv", "LIV Golf"),
        // Rugby union — ESPN's rugby slugs are numbers.
        League(Sport.RUGBY, "180659", "Six Nations"),
        League(Sport.RUGBY, "164205", "Rugby World Cup"),
        League(Sport.RUGBY, "244293", "The Rugby Championship"),
        League(Sport.RUGBY, "271937", "Champions Cup"),
        League(Sport.RUGBY, "267979", "Premiership Rugby", country = "GB"),
        League(Sport.RUGBY, "270559", "Top 14", country = "FR"),
        League(Sport.RUGBY, "270557", "United Rugby Championship"),
        League(Sport.RUGBY, "242041", "Super Rugby Pacific"),
        League(Sport.RUGBY, "17567", "Nations Championship"),
        League(Sport.RUGBY, "289234", "International Test Matches", hasTable = false),
        League(Sport.RUGBY, "272073", "European Challenge Cup"),
        League(Sport.RUGBY, "268565", "British & Irish Lions Tour", hasTable = false),
        League(Sport.RUGBY, "289237", "Women's Rugby World Cup"),
        // Rugby league, Aussie rules
        League(Sport.RUGBY_LEAGUE, "3", "NRL", country = "AU"),
        League(Sport.AUSSIE_RULES, "afl", "AFL", country = "AU"),
        // Volleyball, lacrosse
        League(Sport.VOLLEYBALL, "womens-college-volleyball", "NCAA Women's Volleyball", "NCAAW", "US"),
        League(Sport.LACROSSE, "pll", "Premier Lacrosse League", "PLL", "US"),
        League(Sport.LACROSSE, "nll", "National Lacrosse League", "NLL"),
    )

    private val byKey: Map<String, League> = leagues.associateBy { it.key }

    fun league(key: String): League? = byKey[key]

    /**
     * The names a guide or a channel may call [match]'s competition: the catalogue's brand name
     * ("Bundesliga") and ESPN's own ("German Bundesliga"). A cricket match keeps only its series name:
     * its feed, `cricket/all`, is every series at once.
     */
    fun competitionNames(match: Match): List<String> =
        listOfNotNull(league(League.keyOf(match.sport, match.leagueSlug))?.name, match.leagueName).distinct()

    /** The sports that have something in the catalogue, in chip order. */
    val sports: List<Sport> get() = Sport.entries.filter { s -> leagues.any { it.sport == s } }

    fun leaguesOf(sport: Sport): List<League> = leagues.filter { it.sport == sport }

    private fun soccer(slug: String, name: String, country: String? = null, table: Boolean = true) =
        League(Sport.SOCCER, slug, name, country = country, hasTable = table)

    private const val CRICKET_ALL = "all"
}
