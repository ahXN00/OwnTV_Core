package tv.own.owntv.core.sports

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match

/** One line of "Suggested from what you watch": a team, or a competition ([team] null). */
data class SportsSuggestion(val key: String, val name: String, val abbreviation: String, val plays: Int, val team: FollowedTeam?)

/**
 * What the profile's sports settings remember from use: the channel chosen per competition
 * ("Always use for Bundesliga"), plays from the hub, and the suggestions made of them. Pure
 * functions on [SportsConfig]; the caller writes the result with `updateSportsConfig(pid) { … }`.
 * Nothing here ever follows anything — a suggestion is only offered.
 */
object SportsMemory {

    /** A suggestion needs this many plays from the hub. */
    const val SUGGEST_AFTER = 3

    /** The competition a match is remembered under: its own league (a cricket series), not its feed. */
    fun competitionKey(match: Match): String = League.keyOf(match.sport, match.leagueSlug)

    /**
     * "Always use for <competition>": stores [channel] by its portable name and country,
     * never its Room id, so it survives a re-sync, a restore on another device and a provider change.
     */
    fun remember(config: SportsConfig, match: Match, channel: ChannelEntity): SportsConfig {
        val facets = ChannelFacets.of(channel.matchName)
        return config.copy(rememberedChannels = config.rememberedChannels + (competitionKey(match) to RememberedChannel(facets.name, facets.country)))
    }

    fun forget(config: SportsConfig, competitionKey: String): SportsConfig =
        config.copy(rememberedChannels = config.rememberedChannels - competitionKey)

    /** The remembered channel for [match]'s competition, for [WatchReader.watchFor]. */
    fun rememberedFor(config: SportsConfig, match: Match): RememberedChannel? = config.rememberedChannels[competitionKey(match)]

    /** A match was played from the hub: count it for both sides and its competition. */
    fun recordPlay(config: SportsConfig, match: Match): SportsConfig {
        fun bump(map: Map<String, Played>, key: String, name: String, abbreviation: String) =
            map + (key to Played(name, abbreviation, (map[key]?.count ?: 0) + 1))
        var teams = config.playedTeams
        for (c in match.competitors) {
            teams = bump(teams, FollowedTeam(match.sport, c.team.id, c.team.name).key, c.team.name, c.team.abbreviation)
        }
        val league = competitionKey(match)
        val leagueName = SportsCatalog.league(league)?.name ?: match.leagueName
        return config.copy(
            playedTeams = teams.trimmed(),
            playedLeagues = bump(config.playedLeagues, league, leagueName, "").trimmed(),
        )
    }

    /**
     * Teams played [SUGGEST_AFTER]+ times and not followed, most played first; then competitions that
     * are played as often, or have a remembered channel, and are not followed.
     */
    fun suggestions(config: SportsConfig, followedLeagues: Set<String>): List<SportsSuggestion> {
        val followedTeams = config.teams.map { it.key }.toSet()
        val teams = config.playedTeams
            .filter { (k, p) -> p.count >= SUGGEST_AFTER && k !in followedTeams }
            .mapNotNull { (k, p) ->
                val sport = tv.own.owntv.core.sports.model.Sport.ofPath(k.substringBeforeLast('/')) ?: return@mapNotNull null
                SportsSuggestion(k, p.name, p.abbreviation, p.count, FollowedTeam(sport, k.substringAfterLast('/'), p.name, p.abbreviation))
            }
            .sortedByDescending { it.plays }
        val leagues = (config.playedLeagues.filterValues { it.count >= SUGGEST_AFTER }.keys + config.rememberedChannels.keys)
            .filter { it !in followedLeagues && it.followableUnder() !in followedLeagues }
            .mapNotNull { k ->
                val name = config.playedLeagues[k]?.name ?: SportsCatalog.league(k)?.name ?: return@mapNotNull null
                // The key is what following needs: a cricket series offers the cricket feed, under the series' name.
                SportsSuggestion(k.followableUnder(), name, "", config.playedLeagues[k]?.count ?: 0, null)
            }
            .sortedByDescending { it.plays }
            .distinctBy { it.key }
        return teams + leagues
    }

    /** A cricket series is followed through the one cricket feed. */
    private fun String.followableUnder(): String = if (startsWith("cricket/")) "cricket/all" else this

    private fun Map<String, Played>.trimmed(): Map<String, Played> =
        if (size <= SportsConfig.MAX_PLAYED) this
        else entries.sortedByDescending { it.value.count }.take(SportsConfig.MAX_PLAYED).associate { it.key to it.value }
}
