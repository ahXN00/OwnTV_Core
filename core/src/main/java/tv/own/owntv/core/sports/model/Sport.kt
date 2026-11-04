package tv.own.owntv.core.sports.model

/**
 * A sport as ESPN files it. [path] is the segment in ESPN's URLs (`/sports/<path>/<league>/…`).
 * Declaration order is the hub's chip order: the eight shown first, then the rest behind "+N".
 */
enum class Sport(val path: String) {
    SOCCER("soccer"),
    BASKETBALL("basketball"),
    TENNIS("tennis"),
    CRICKET("cricket"),
    RACING("racing"),
    HOCKEY("hockey"),
    FOOTBALL("football"),
    MMA("mma"),
    BASEBALL("baseball"),
    GOLF("golf"),
    RUGBY("rugby"),
    RUGBY_LEAGUE("rugby-league"),
    AUSSIE_RULES("australian-football"),
    VOLLEYBALL("volleyball"),
    LACROSSE("lacrosse"),
    ;

    companion object {
        fun ofPath(path: String): Sport? = entries.firstOrNull { it.path == path }
    }
}

/**
 * One competition. [slug] is ESPN's league slug (`ger.1`, `nba`, `atp`); it is unique within a sport,
 * so [key] is unique everywhere. [country] is an ISO 3166 code where the competition belongs to one
 * country, else null (UCL, F1, ATP). [hasTable] is false for a knockout cup or a run of one-off games,
 * whose `standings` answer is empty every time.
 */
data class League(
    val sport: Sport,
    val slug: String,
    val name: String,
    val abbreviation: String = name,
    val country: String? = null,
    val hasTable: Boolean = true,
) {
    val key: String get() = keyOf(sport, slug)

    companion object {
        fun keyOf(sport: Sport, slug: String): String = "${sport.path}/$slug"
    }
}
