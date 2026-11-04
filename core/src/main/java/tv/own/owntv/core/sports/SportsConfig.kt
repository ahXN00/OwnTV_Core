package tv.own.owntv.core.sports

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.settings.SettingsImportRules
import tv.own.owntv.core.sports.model.Sport

/** A team (or athlete, or national side) the profile follows. [id] is ESPN's, unique within [sport]. */
data class FollowedTeam(val sport: Sport, val id: String, val name: String, val abbreviation: String = "") {
    val key: String get() = "${sport.path}/$id"
}

/**
 * A channel remembered for a competition ("Always use for Bundesliga"), stored by its portable name —
 * the channel name without provider tags, plus its country — never by Room id, which every sync
 * recreates. It is resolved against the current playlists when a match is ranked.
 */
data class RememberedChannel(val name: String, val country: String? = null)

/**
 * How often the profile played a team's (or a competition's) matches from the hub — what "Suggested
 * from what you watch" is made of. [name] is kept so a suggestion can be shown without a
 * network call.
 */
data class Played(val name: String, val abbreviation: String = "", val count: Int = 0)

/** The lowest picture quality "Watch live" lists; ranks are ProviderCatalogMetadata's quality ranks. */
enum class MinQuality(val rank: Int) { ANY(0), HD(2), FHD(3), UHD(4) }

/** Settings → Sports → Auto rebuild: how often the stored board is built again by itself. */
enum class AutoRebuild { H1, H2, H4, H8, APP_START, MANUAL }

enum class AlertKind { GOALS, KICKOFF_FULL_TIME, HALF_TIME, RED_CARDS }

/** Settings → Sports → Goal alerts. Off until the user turns it on. */
data class GoalAlerts(
    val enabled: Boolean = false,
    val whenClosed: Boolean = false,
    val kinds: Set<AlertKind> = setOf(AlertKind.GOALS, AlertKind.KICKOFF_FULL_TIME),
    val myTeams: Boolean = true,
    val followedLeagues: Boolean = false,
    val liveCard: Boolean = true,
)

/**
 * One profile's sports settings (Settings → Sports). Stored as one JSON blob per
 * profile, `sports_config_<id>`, the way Home's config is, and backed up in the same way.
 *
 * A new profile follows nothing ([leagues] empty). [languages] is null until the user
 * first changes it: until then the app's own language is ranked first ([preferredLanguages]). Every read goes through [SettingsImportRules], so a value from another
 * build or a hand-edited backup can never be out of range.
 */
data class SportsConfig(
    /** A List, not a Set: a Set equals itself in any order, so a reorder alone never reached anyone. */
    val leagues: List<String> = emptyList(),
    /** Sport buttons: true = every catalogue league is fetched, false = only [leagues]. */
    val allSports: Boolean = false,
    /** The user's sport order (Settings → Sports and leagues, Hold OK to move); sports not in it follow, in catalogue order. */
    val sportOrder: List<Sport> = emptyList(),
    val autoRebuild: AutoRebuild = AutoRebuild.H4,
    val teams: List<FollowedTeam> = emptyList(),
    val languages: Set<String>? = null,
    val countries: Set<String> = emptySet(),
    val minQuality: MinQuality = MinQuality.ANY,
    val onlyConfirmed: Boolean = false,
    /** On: only channels passing the filters are listed. Off: every channel, the passing ones first. */
    val strictFilter: Boolean = true,
    /** The playlists (source ids) Watch live searches; empty = every playlist. */
    val sourceIds: Set<Long> = emptySet(),
    val rememberFilters: Boolean = false,
    val spoilerFree: Boolean = false,
    val scoreInInfoBar: Boolean = true,
    val liveRefreshSeconds: Int = SportsRefreshPolicy.DEFAULT_LIVE_SECONDS,
    val showLikely: Boolean = true,
    val rememberedChannels: Map<String, RememberedChannel> = emptyMap(),
    val alerts: GoalAlerts = GoalAlerts(),
    val reminderLeadMinutes: Int = DEFAULT_REMINDER_LEAD_MINUTES,
    /** Settings → Sports → Calendar refresh: a saved current or future month is fetched again once this many days old. */
    val calendarRefreshDays: Int = DEFAULT_CALENDAR_REFRESH_DAYS,
    /** Plays from the hub per team ([FollowedTeam.key] form, `soccer/132`) and per league key. */
    val playedTeams: Map<String, Played> = emptyMap(),
    val playedLeagues: Map<String, Played> = emptyMap(),
) {
    /** [sports] in the user's order: [sportOrder] first, the rest as given. */
    fun orderSports(sports: List<Sport>): List<Sport> = sports.sortedBy { s -> sportOrder.indexOf(s).let { if (it < 0) Int.MAX_VALUE else it } }

    /** A match's place in the user's order: its sport first, then its league among the chosen ones ([leagues] keeps their order). */
    fun rankOf(sport: Sport, leagueKey: String): Pair<Int, Int> {
        val s = sportOrder.indexOf(sport).let { if (it < 0) sportOrder.size + sport.ordinal else it }
        val l = leagues.indexOf(leagueKey).let { if (it < 0) Int.MAX_VALUE else it }
        return s to l
    }

    /** The league keys to fetch: every catalogue league with [allSports], else the user's choice. */
    fun followedLeagues(): Set<String> = if (allSports) SportsCatalog.leagues.mapTo(LinkedHashSet()) { it.key } else leagues.toCollection(LinkedHashSet())

    /** The commentary languages ranked first (upper-case ISO 639-1): the user's choice, or the app's language. */
    fun preferredLanguages(appLanguage: String): Set<String> =
        languages ?: setOfNotNull(appLanguage.substringBefore('-').uppercase().takeIf { it.length == 2 })

    fun toJson(): JSONObject = JSONObject().apply {
        put("leagues", JSONArray(leagues.toList()))
        put("allSports", allSports)
        put("sportOrder", JSONArray(sportOrder.map { it.name }))
        put("autoRebuild", autoRebuild.name)
        put("teams", JSONArray().apply {
            teams.forEach { put(JSONObject().put("s", it.sport.path).put("id", it.id).put("n", it.name).put("ab", it.abbreviation)) }
        })
        languages?.let { put("languages", JSONArray(it.toList())) }
        put("countries", JSONArray(countries.toList()))
        put("minQuality", minQuality.name)
        put("onlyConfirmed", onlyConfirmed)
        put("strict", strictFilter)
        put("sources", JSONArray(sourceIds.toList()))
        put("rememberFilters", rememberFilters)
        put("spoilerFree", spoilerFree)
        put("scoreInInfoBar", scoreInInfoBar)
        put("liveRefreshSeconds", liveRefreshSeconds)
        put("showLikely", showLikely)
        put("channels", JSONObject().apply {
            rememberedChannels.forEach { (league, ch) -> put(league, JSONObject().put("n", ch.name).putOpt("c", ch.country)) }
        })
        put("alerts", JSONObject()
            .put("on", alerts.enabled).put("closed", alerts.whenClosed)
            .put("kinds", JSONArray(alerts.kinds.map { it.name }))
            .put("myTeams", alerts.myTeams).put("leagues", alerts.followedLeagues).put("card", alerts.liveCard))
        put("reminderLead", reminderLeadMinutes)
        put("calendarRefreshDays", calendarRefreshDays)
        put("played", JSONObject().put("t", playedJson(playedTeams)).put("l", playedJson(playedLeagues)))
    }

    private fun playedJson(map: Map<String, Played>) = JSONObject().apply {
        map.forEach { (k, p) -> put(k, JSONObject().put("n", p.name).put("ab", p.abbreviation).put("c", p.count)) }
    }

    companion object {
        const val DEFAULT_REMINDER_LEAD_MINUTES = 10

        /** Most-played teams and leagues kept per profile. */
        const val MAX_PLAYED = 60

        /** The Match reminders lead times. */
        val REMINDER_LEAD_CHOICES = listOf(5, 10, 15, 30)

        const val DEFAULT_CALENDAR_REFRESH_DAYS = 7

        /** The Calendar refresh stepper's values, in days: both apps step through these. */
        val CALENDAR_REFRESH_CHOICES = listOf(1, 2, 3, 4, 5, 6, 7, 10, 14, 21, 30)

        fun fromJson(raw: String?): SportsConfig {
            if (raw.isNullOrBlank()) return SportsConfig()
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return SportsConfig()
            val d = SportsConfig()
            val a = o.optJSONObject("alerts")
            return SportsConfig(
                leagues = SettingsImportRules.sportsLeagues(strings(o.optJSONArray("leagues"))),
                allSports = o.optBoolean("allSports", d.allSports),
                sportOrder = strings(o.optJSONArray("sportOrder")).mapNotNull { n -> Sport.entries.firstOrNull { it.name == n } }.distinct(),
                autoRebuild = runCatching { AutoRebuild.valueOf(o.optString("autoRebuild")) }.getOrDefault(d.autoRebuild),
                teams = objects(o.optJSONArray("teams")).mapNotNull { t ->
                    val sport = Sport.ofPath(t.optString("s")) ?: return@mapNotNull null
                    val id = t.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    FollowedTeam(sport, id, t.optString("n"), t.optString("ab"))
                }.distinctBy { it.key },
                languages = o.optJSONArray("languages")?.let { SettingsImportRules.sportsCodes(strings(it), length = 2) },
                countries = SettingsImportRules.sportsCodes(strings(o.optJSONArray("countries")), length = 2),
                minQuality = runCatching { MinQuality.valueOf(o.optString("minQuality")) }.getOrDefault(d.minQuality),
                onlyConfirmed = o.optBoolean("onlyConfirmed", d.onlyConfirmed),
                strictFilter = o.optBoolean("strict", d.strictFilter),
                sourceIds = SettingsImportRules.sportsSources(longs(o.optJSONArray("sources"))),
                rememberFilters = o.optBoolean("rememberFilters", d.rememberFilters),
                spoilerFree = o.optBoolean("spoilerFree", d.spoilerFree),
                scoreInInfoBar = o.optBoolean("scoreInInfoBar", d.scoreInInfoBar),
                liveRefreshSeconds = SettingsImportRules.sportsLiveRefresh(o.optInt("liveRefreshSeconds", d.liveRefreshSeconds)),
                showLikely = o.optBoolean("showLikely", d.showLikely),
                rememberedChannels = o.optJSONObject("channels")?.let { ch ->
                    ch.keys().asSequence().mapNotNull { league ->
                        val c = ch.optJSONObject(league) ?: return@mapNotNull null
                        val name = c.optString("n").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        league to RememberedChannel(name, c.optString("c").takeIf { it.isNotEmpty() })
                    }.toMap()
                }.orEmpty(),
                alerts = if (a == null) d.alerts else GoalAlerts(
                    enabled = a.optBoolean("on", d.alerts.enabled),
                    whenClosed = a.optBoolean("closed", d.alerts.whenClosed),
                    kinds = a.optJSONArray("kinds")?.let { k -> strings(k).mapNotNull { runCatching { AlertKind.valueOf(it) }.getOrNull() }.toSet() }
                        ?: d.alerts.kinds,
                    myTeams = a.optBoolean("myTeams", d.alerts.myTeams),
                    followedLeagues = a.optBoolean("leagues", d.alerts.followedLeagues),
                    liveCard = a.optBoolean("card", d.alerts.liveCard),
                ),
                reminderLeadMinutes = SettingsImportRules.sportsReminderLead(o.optInt("reminderLead", d.reminderLeadMinutes)),
                calendarRefreshDays = SettingsImportRules.sportsCalendarRefresh(o.optInt("calendarRefreshDays", d.calendarRefreshDays)),
                playedTeams = played(o.optJSONObject("played")?.optJSONObject("t")),
                playedLeagues = played(o.optJSONObject("played")?.optJSONObject("l")),
            )
        }

        private fun played(o: JSONObject?): Map<String, Played> =
            SettingsImportRules.sportsPlayed(
                o?.keys()?.asSequence()?.mapNotNull { k ->
                    val p = o.optJSONObject(k) ?: return@mapNotNull null
                    k to Played(p.optString("n"), p.optString("ab"), p.optInt("c"))
                }?.toMap().orEmpty(),
            )

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.opt(it) as? String }

        private fun longs(arr: JSONArray?): List<Long> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { (arr.opt(it) as? Number)?.toLong() }

        private fun objects(arr: JSONArray?): List<JSONObject> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
}
