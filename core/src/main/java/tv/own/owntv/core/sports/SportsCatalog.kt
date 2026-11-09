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
        soccer("uefa.champions", "Champions League", aliases = listOf("UEFA Champions League", "UCL", "Ligue des Champions", "Liga de Campeones", "Königsklasse")),
        soccer("uefa.europa", "Europa League", aliases = listOf("UEFA Europa League", "UEL", "Ligue Europa")),
        soccer("uefa.europa.conf", "Conference League", aliases = listOf("UEFA Conference League", "UECL", "Ligue Europa Conférence")),
        soccer("uefa.nations", "Nations League", aliases = listOf("UEFA Nations League", "Ligue des Nations")),
        soccer("uefa.super_cup", "UEFA Super Cup", table = false, aliases = listOf("UEFA Supercup", "Supercoupe de l'UEFA")),
        soccer("uefa.wchampions", "Women's Champions League", aliases = listOf("UEFA Women's Champions League", "UWCL", "Womens Champions League")),
        soccer("fifa.world", "FIFA World Cup", aliases = listOf("World Cup", "Coupe du Monde", "Copa Mundial", "Copa do Mundo", "Fußball-WM")),
        soccer("fifa.wwc", "FIFA Women's World Cup", aliases = listOf("Women's World Cup", "Womens World Cup", "Frauen-WM")),
        soccer("fifa.cwc", "FIFA Club World Cup", aliases = listOf("Club World Cup", "Klub-WM", "Mundial de Clubes")),
        soccer("fifa.worldq.uefa", "World Cup Qualifying – UEFA", aliases = listOf("World Cup Qualifiers", "WM-Qualifikation")),
        soccer("fifa.worldq.conmebol", "World Cup Qualifying – CONMEBOL", aliases = listOf("World Cup Qualifiers", "Eliminatorias")),
        soccer("fifa.worldq.concacaf", "World Cup Qualifying – Concacaf", aliases = listOf("World Cup Qualifiers")),
        soccer("fifa.worldq.afc", "World Cup Qualifying – AFC", aliases = listOf("World Cup Qualifiers")),
        soccer("fifa.worldq.caf", "World Cup Qualifying – CAF", aliases = listOf("World Cup Qualifiers")),
        soccer("fifa.friendly", "International Friendlies", table = false, aliases = listOf("International Friendly", "Länderspiel")),
        soccer("uefa.euro", "EURO", aliases = listOf("UEFA EURO", "European Championship", "Europameisterschaft", "Fußball-EM")),
        soccer("uefa.weuro", "Women's EURO", aliases = listOf("UEFA Women's EURO", "Womens EURO", "Frauen-EM")),
        soccer("eng.1", "Premier League", "GB", aliases = listOf("English Premier League", "EPL")),
        // "Championship" alone is half of sport ("World Championship"); only its English forms find it.
        soccer("eng.2", "Championship", "GB", nameMatches = false, aliases = listOf("EFL Championship", "Sky Bet Championship", "English Championship")),
        soccer("eng.3", "League One", "GB", aliases = listOf("EFL League One", "Sky Bet League One")),
        soccer("eng.fa", "FA Cup", "GB", table = false),
        soccer("eng.league_cup", "Carabao Cup", "GB", table = false, aliases = listOf("EFL Cup", "League Cup")),
        soccer("eng.charity", "Community Shield", "GB", table = false),
        soccer("eng.w.1", "Women's Super League", "GB", aliases = listOf("WSL", "Womens Super League")),
        soccer("sco.1", "Scottish Premiership", "GB", aliases = listOf("SPFL Premiership", "William Hill Premiership")),
        soccer("sco.tennents", "Scottish Cup", "GB", table = false),
        soccer("sco.cis", "Scottish League Cup", "GB"),
        soccer("ger.1", "Bundesliga", "DE", aliases = listOf("1. Bundesliga")),
        soccer("ger.2", "2. Bundesliga", "DE", aliases = listOf("Zweite Bundesliga")),
        soccer("ger.dfb_pokal", "DFB-Pokal", "DE", table = false, aliases = listOf("German Cup")),
        soccer("esp.1", "LaLiga", "ES", aliases = listOf("La Liga", "Primera División")),
        soccer("esp.2", "LaLiga 2", "ES", aliases = listOf("La Liga 2", "LaLiga Hypermotion", "Segunda División")),
        soccer("esp.copa_del_rey", "Copa del Rey", "ES", table = false),
        soccer("esp.super_cup", "Supercopa de España", "ES", table = false),
        soccer("ita.1", "Serie A", "IT"),
        soccer("ita.2", "Serie B", "IT", aliases = listOf("Serie BKT")),
        soccer("ita.coppa_italia", "Coppa Italia", "IT", table = false),
        soccer("ita.super_cup", "Supercoppa Italiana", "IT", table = false),
        soccer("fra.1", "Ligue 1", "FR"),
        soccer("fra.2", "Ligue 2", "FR"),
        soccer("fra.coupe_de_france", "Coupe de France", "FR", table = false),
        soccer("ned.1", "Eredivisie", "NL"),
        soccer("ned.cup", "KNVB Beker", "NL", table = false, aliases = listOf("KNVB Cup")),
        soccer("por.1", "Liga Portugal", "PT", aliases = listOf("Primeira Liga")),
        soccer("por.taca.portugal", "Taça de Portugal", "PT", table = false),
        soccer("bel.1", "Pro League", "BE", aliases = listOf("Jupiler Pro League", "Belgian Pro League")),
        soccer("aut.1", "Austrian Bundesliga", "AT", aliases = listOf("Admiral Bundesliga", "Österreichische Bundesliga")),
        soccer("sui.1", "Swiss Super League", "CH", aliases = listOf("Credit Suisse Super League")),
        // Argentina, Romania and Serbia each have a "Superliga" too.
        soccer("den.1", "Superliga", "DK", nameMatches = false, aliases = listOf("Danish Superliga", "3F Superliga", "Superligaen")),
        soccer("nor.1", "Eliteserien", "NO"),
        soccer("swe.1", "Allsvenskan", "SE"),
        soccer("gre.1", "Greek Super League", "GR", aliases = listOf("Super League Greece", "Stoiximan Super League")),
        soccer("tur.1", "Süper Lig", "TR", aliases = listOf("Turkish Super Lig")),
        soccer("tur.2", "1. Lig", "TR"),
        soccer("ksa.1", "Saudi Pro League", "SA", aliases = listOf("Roshn Saudi League")),
        soccer("ksa.kings.cup", "King's Cup", "SA", table = false, aliases = listOf("Kings Cup")),
        soccer("afc.champions", "AFC Champions League Elite", aliases = listOf("AFC Champions League", "ACL Elite")),
        soccer("afc.cup", "AFC Champions League Two", aliases = listOf("ACL Two")),
        soccer("afc.asian.cup", "AFC Asian Cup", aliases = listOf("Asian Cup")),
        soccer("ind.1", "Indian Super League", "IN", aliases = listOf("ISL")),
        soccer("caf.champions", "CAF Champions League"),
        soccer("caf.nations", "Africa Cup of Nations", aliases = listOf("AFCON", "Coupe d'Afrique des Nations")),
        soccer("usa.1", "MLS", "US", aliases = listOf("Major League Soccer")),
        soccer("usa.nwsl", "NWSL", "US"),
        soccer("mex.1", "Liga MX", "MX"),
        soccer("concacaf.champions", "Concacaf Champions Cup", table = false),
        soccer("concacaf.leagues.cup", "Leagues Cup"),
        soccer("concacaf.gold", "Concacaf Gold Cup", aliases = listOf("Gold Cup")),
        soccer("bra.1", "Brasileirão", "BR", aliases = listOf("Brasileirão Série A", "Campeonato Brasileiro")),
        soccer("bra.copa_do_brazil", "Copa do Brasil", "BR", table = false),
        soccer("arg.1", "Liga Profesional", "AR", aliases = listOf("Liga Profesional Argentina")),
        soccer("conmebol.libertadores", "Copa Libertadores", aliases = listOf("Libertadores")),
        soccer("conmebol.sudamericana", "Copa Sudamericana", aliases = listOf("Sudamericana")),
        soccer("conmebol.america", "Copa América"),
        // Basketball
        League(Sport.BASKETBALL, "nba", "NBA", country = "US"),
        League(Sport.BASKETBALL, "wnba", "WNBA", country = "US"),
        League(Sport.BASKETBALL, "mens-college-basketball", "NCAA Basketball", "NCAAM", "US", aliases = listOf("College Basketball", "March Madness")),
        League(Sport.BASKETBALL, "womens-college-basketball", "NCAA Women's Basketball", "NCAAW", "US", aliases = listOf("Women's College Basketball")),
        League(Sport.BASKETBALL, "fiba", "FIBA World Cup", aliases = listOf("Basketball World Cup", "Basketball-WM")),
        // Tennis
        League(Sport.TENNIS, "atp", "ATP"),
        League(Sport.TENNIS, "wta", "WTA"),
        // Cricket
        League(Sport.CRICKET, CRICKET_ALL, "Cricket"),
        // Motorsport
        League(Sport.RACING, "f1", "Formula 1", "F1", aliases = listOf("F1", "Formula One", "Formel 1", "Formule 1", "Fórmula 1", "Formula Uno")),
        League(Sport.RACING, "irl", "IndyCar", country = "US", aliases = listOf("Indy Car", "Indy 500")),
        League(Sport.RACING, "nascar-premier", "NASCAR Cup Series", "NASCAR", "US", aliases = listOf("NASCAR")),
        League(Sport.RACING, "nascar-secondary", "NASCAR O'Reilly Auto Parts Series", "NASCAR O'Reilly", "US", aliases = listOf("NASCAR O'Reilly", "NASCAR Xfinity")),
        League(Sport.RACING, "nascar-truck", "NASCAR Truck Series", "NASCAR Truck", "US", aliases = listOf("NASCAR Craftsman Truck Series", "NASCAR Trucks")),
        // Ice hockey
        League(Sport.HOCKEY, "nhl", "NHL", country = "US"),
        League(Sport.HOCKEY, "olympics-mens-ice-hockey", "Olympic Men's Ice Hockey", aliases = listOf("Olympic Ice Hockey")),
        League(Sport.HOCKEY, "olympics-womens-ice-hockey", "Olympic Women's Ice Hockey"),
        // American football
        League(Sport.FOOTBALL, "nfl", "NFL", country = "US"),
        League(Sport.FOOTBALL, "college-football", "College Football", "NCAAF", "US", aliases = listOf("NCAAF", "NCAA Football")),
        // MMA
        League(Sport.MMA, "ufc", "UFC"),
        League(Sport.MMA, "pfl", "PFL", aliases = listOf("Professional Fighters League")),
        League(Sport.MMA, "ofc", "ONE Championship", "ONE", aliases = listOf("ONE Fight Night")),
        // Baseball
        League(Sport.BASEBALL, "mlb", "MLB", country = "US", aliases = listOf("Major League Baseball")),
        League(Sport.BASEBALL, "world-baseball-classic", "World Baseball Classic", hasTable = false),
        // Golf
        League(Sport.GOLF, "pga", "PGA Tour"),
        League(Sport.GOLF, "eur", "DP World Tour", aliases = listOf("European Tour")),
        League(Sport.GOLF, "lpga", "LPGA Tour", aliases = listOf("LPGA")),
        League(Sport.GOLF, "liv", "LIV Golf"),
        // Rugby union — ESPN's rugby slugs are numbers.
        League(Sport.RUGBY, "180659", "Six Nations", aliases = listOf("6 Nations", "Tournoi des Six Nations", "Sei Nazioni")),
        League(Sport.RUGBY, "164205", "Rugby World Cup", aliases = listOf("Coupe du Monde de Rugby", "Rugby-WM")),
        League(Sport.RUGBY, "244293", "The Rugby Championship", aliases = listOf("Rugby Championship")),
        League(Sport.RUGBY, "271937", "Champions Cup", aliases = listOf("European Rugby Champions Cup")),
        League(Sport.RUGBY, "267979", "Premiership Rugby", country = "GB", aliases = listOf("Gallagher Premiership", "Gallagher PREM")),
        League(Sport.RUGBY, "270559", "Top 14", country = "FR"),
        League(Sport.RUGBY, "270557", "United Rugby Championship", aliases = listOf("URC")),
        League(Sport.RUGBY, "242041", "Super Rugby Pacific", aliases = listOf("Super Rugby")),
        League(Sport.RUGBY, "17567", "Nations Championship"),
        League(Sport.RUGBY, "289234", "International Test Matches", hasTable = false, aliases = listOf("Autumn Nations Series", "Summer Nations Series")),
        League(Sport.RUGBY, "272073", "European Challenge Cup", aliases = listOf("EPCR Challenge Cup")),
        League(Sport.RUGBY, "268565", "British & Irish Lions Tour", hasTable = false, aliases = listOf("British and Irish Lions", "Lions Tour")),
        League(Sport.RUGBY, "289237", "Women's Rugby World Cup", aliases = listOf("Womens Rugby World Cup")),
        // Rugby league, Aussie rules
        League(Sport.RUGBY_LEAGUE, "3", "NRL", country = "AU", aliases = listOf("National Rugby League")),
        League(Sport.AUSSIE_RULES, "afl", "AFL", country = "AU", aliases = listOf("Australian Football League")),
        // Volleyball, lacrosse
        League(Sport.VOLLEYBALL, "womens-college-volleyball", "NCAA Women's Volleyball", "NCAAW", "US", aliases = listOf("College Volleyball")),
        League(Sport.LACROSSE, "pll", "Premier Lacrosse League", "PLL", "US", aliases = listOf("PLL")),
        League(Sport.LACROSSE, "nll", "National Lacrosse League", "NLL", aliases = listOf("NLL")),
    )

    /**
     * Competitions that cannot be followed but whose names contain one that can: the "Indian Premier
     * League" is not the Premier League, the "NBA G League" not the NBA. A name found inside one of these
     * does not count ([CompetitionNames]); the catalogue's own names do the same for each other.
     */
    val otherCompetitions: List<String> = listOf(
        "Indian Premier League", "Caribbean Premier League", "Bangladesh Premier League", "Lanka Premier League",
        "Premier League Darts", "Premier League 2", "Premier League Cup", "Premier League International Cup",
        "Basketball Champions League", "EHF Champions League", "CEV Champions League", "Volleyball Champions League",
        "World Cup of Darts", "Cricket World Cup", "T20 World Cup", "ICC World Cup", "Rugby League World Cup",
        "Ski World Cup", "FIS World Cup", "Biathlon World Cup",
        "Frauen-Bundesliga", "Handball-Bundesliga", "Basketball Bundesliga", "Volleyball Bundesliga", "Bundesliga Handball",
        "Serie A Femminile", "Serie A Women", "Brasileirão Série B", "Serie B Femminile",
        "Vrouwen Eredivisie", "Eredivisie Vrouwen", "Liga Portugal 2",
        "NBA G League", "NBA Summer League", "MLS Next Pro", "F1 Academy", "PGA Tour Champions",
        "Women's Six Nations", "U20 Six Nations", "Six Nations U20", "Copa del Rey de Baloncesto",
    )

    private val byKey: Map<String, League> = leagues.associateBy { it.key }

    fun league(key: String): League? = byKey[key]

    /**
     * The names a guide or a channel may call [match]'s competition: the catalogue's search names
     * ("Bundesliga", "1. Bundesliga") and ESPN's own ("German Bundesliga").
     */
    fun competitionNames(match: Match): List<String> =
        (league(League.keyOf(match.sport, match.leagueSlug))?.searchNames.orEmpty() + match.leagueName)
            .filter { it.isNotBlank() }.distinct()

    /** Every competition name there is, for telling a competition apart from a longer one around it. */
    val allCompetitionNames: List<String> by lazy { leagues.flatMap { it.searchNames } + otherCompetitions }

    /** The sports that have something in the catalogue, in chip order. */
    val sports: List<Sport> get() = Sport.entries.filter { s -> leagues.any { it.sport == s } }

    fun leaguesOf(sport: Sport): List<League> = leagues.filter { it.sport == sport }

    private fun soccer(
        slug: String,
        name: String,
        country: String? = null,
        table: Boolean = true,
        nameMatches: Boolean = true,
        aliases: List<String> = emptyList(),
    ) = League(Sport.SOCCER, slug, name, country = country, hasTable = table, aliases = aliases, nameMatches = nameMatches)

    private const val CRICKET_ALL = "all"
}
