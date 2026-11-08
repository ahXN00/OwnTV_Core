package tv.own.owntv.core.sports

import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport

/** Why a channel is listed under "Watch live", best first. */
enum class WatchTier { GUIDE, EVENT, REMEMBERED, LIKELY }

/**
 * What the filters read off a channel's name: its portable name (provider tags removed, [portableKey]
 * for comparing), country prefix ("DE|"), language and picture quality (ProviderCatalogMetadata ranks:
 * 0 unknown, 1 SD, 2 HD, 3 FHD, 4 UHD, 5 8K). Unknown is null / 0, never a guess.
 */
data class ChannelFacets(val name: String, val country: String?, val language: String?, val qualityRank: Int) {
    val portableKey: String get() = EpgMatcher.normalizeForEpg(name)

    companion object {
        // A provider's country prefix says which language the channel speaks when its name does not.
        // "AR|" is Arabic in IPTV playlists, not Argentina; "UK|" is the United Kingdom, not Ukrainian.
        private val COUNTRY_LANGUAGE = mapOf(
            "DE" to "DE", "AT" to "DE", "CH" to "DE",
            "UK" to "EN", "GB" to "EN", "US" to "EN", "USA" to "EN", "IE" to "EN", "CA" to "EN", "AU" to "EN", "NZ" to "EN", "IN" to "EN",
            "FR" to "FR", "BE" to "FR", "ES" to "ES", "MX" to "ES", "LAT" to "ES", "IT" to "IT", "TR" to "TR",
            "AR" to "AR", "ARA" to "AR", "SA" to "AR", "AE" to "AR", "QA" to "AR", "EG" to "AR", "MA" to "AR",
            "PT" to "PT", "BR" to "PT", "NL" to "NL", "PL" to "PL", "RU" to "RU", "GR" to "EL", "RO" to "RO",
            "SE" to "SV", "NO" to "NO", "DK" to "DA", "FI" to "FI", "HU" to "HU", "CZ" to "CS", "PK" to "UR",
        )

        fun of(channelName: String): ChannelFacets {
            val parsed = ProviderTags.parse(channelName)
            val meta = ProviderCatalogMetadataParser.parse(parsed.name, null)
            val tagRank = if (parsed.tags.isEmpty()) 0
            else ProviderCatalogMetadataParser.parse(parsed.tags.joinToString(" "), null).qualityRank
            val country = parsed.country
            return ChannelFacets(
                name = parsed.name,
                country = country,
                language = country?.let { COUNTRY_LANGUAGE[it] } ?: meta.language?.takeIf { it != "UK" },
                qualityRank = maxOf(meta.qualityRank, tagRank),
            )
        }
    }
}

/** One channel under "Watch live": the [tier] it earned, and for a guide hit the programme's title. */
data class WatchOption(
    val channel: ChannelEntity,
    val tier: WatchTier,
    val guideTitle: String? = null,
    val facets: ChannelFacets = ChannelFacets.of(channel.matchName),
)

/**
 * The pure half of "where is it live": which guide titles and channel names name a
 * match, and how the hits rank. [WatchReader] does the reads; everything here is unit-tested.
 */
object WatchMatcher {

    /** How a programme's text relates to a match. */
    enum class Hit { NONE, PARTIAL, CONFIRMED }

    /**
     * A match prepared once: its sides' and competition's name keys. A race has no sides to look for:
     * once it is over ESPN lists its first two drivers as if they were, and no guide titles a race by them.
     */
    class Keys(val match: Match) {
        private val sides = if (match.sport == Sport.RACING) emptyList() else listOfNotNull(match.home?.team, match.away?.team)
        val home: TeamNames.Key? = sides.getOrNull(0)?.let(TeamNames::keyOf)
        val away: TeamNames.Key? = sides.getOrNull(1)?.let(TeamNames::keyOf)
        val competitionNames: List<String> = SportsCatalog.competitionNames(match)
        val league: CompetitionNames.Key = CompetitionNames.keyOf(competitionNames)
        val broadcasters: List<CompetitionNames.Key> = match.broadcasts.map { CompetitionNames.broadcasterKey(it.name) }

        /** Words for a channel full-text search: one team's word is enough to find "Frankfurt v Köln". */
        val searchWords: List<String> = sides.flatMap { t -> asciiWords(t.shortName) + asciiWords(t.name) }.distinct()
    }

    /**
     * Both teams, or one team and the competition → confirmed. One team alone → partial, worth reading
     * the synopsis. A session with no sides (racing) is confirmed by its competition alone. [tokens] are
     * the text's [TeamNames.tokens], [words] its [CompetitionNames.words].
     */
    fun hit(keys: Keys, tokens: Set<String>, words: List<String>): Hit {
        val home = keys.home?.foundIn(tokens) == true
        val away = keys.away?.foundIn(tokens) == true
        val league = keys.league.foundIn(words)
        return when {
            keys.home == null && keys.away == null -> if (league) Hit.CONFIRMED else Hit.NONE
            home && away -> Hit.CONFIRMED
            (home || away) && league -> Hit.CONFIRMED
            home || away -> Hit.PARTIAL
            else -> Hit.NONE
        }
    }

    /** A provider's event channel ("BL 18:30 Frankfurt v Köln"): its name carries both teams. */
    fun isEventChannel(keys: Keys, channelName: String): Boolean {
        val home = keys.home ?: return false
        val away = keys.away ?: return false
        val tokens = TeamNames.tokens(channelName).toSet()
        return home.foundIn(tokens) && away.foundIn(tokens)
    }

    /**
     * A channel that usually carries it: its name carries the competition, or it is a channel of a
     * broadcaster ESPN names and nothing else ("PEACOCK EVENT 3", not "APPLE TV+ SERIES 1").
     * Not a channel named for one fixture ("Next | La Clery vs. Vieux Fort South | Premier League"): it
     * shows that fixture, and when it were this one [isEventChannel] has already listed it.
     */
    fun isLikely(keys: Keys, channelName: String): Boolean {
        if (FIXTURE.containsMatchIn(channelName)) return false
        if (keys.league.foundIn(CompetitionNames.words(channelName), channel = true)) return true
        if (keys.broadcasters.isEmpty()) return false
        val name = CompetitionNames.words(ProviderTags.parse(channelName).name)
        return keys.broadcasters.any { it.namesOnly(name) }
    }

    /** A playlist's divider row ("##### PREMIER LEAGUE #####"): a heading, never a channel to watch. */
    fun isSeparator(channelName: String): Boolean = SEPARATOR.containsMatchIn(channelName)

    private val FIXTURE = Regex("""\s(vs?\.?|v)\s""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""#{3,}|={4,}|-{4,}|\*{4,}""")

    /**
     * One list, one row per channel, best tier first; within a tier the input order (playlist order)
     * is kept. [remembered] is the channel remembered for this competition, matched by portable name among
     * [rememberedCandidates] (a name search for it, so it is found even when nothing else lists it).
     */
    fun rank(
        guide: List<WatchOption>,
        event: List<ChannelEntity>,
        likely: List<ChannelEntity>,
        rememberedCandidates: List<ChannelEntity>,
        remembered: RememberedChannel?,
    ): List<WatchOption> {
        val out = LinkedHashMap<Long, WatchOption>()
        fun add(option: WatchOption) {
            if (isSeparator(option.channel.name)) return
            val prior = out[option.channel.id]
            if (prior == null || option.tier < prior.tier) out[option.channel.id] = option
        }
        guide.forEach(::add)
        event.forEach { add(WatchOption(it, WatchTier.EVENT)) }
        if (remembered != null) {
            val key = EpgMatcher.normalizeForEpg(remembered.name)
            rememberedCandidates.asSequence()
                .map { WatchOption(it, WatchTier.REMEMBERED) }
                .filter { it.facets.portableKey == key && (remembered.country == null || it.facets.country == null || it.facets.country == remembered.country) }
                .forEach(::add)
        }
        likely.forEach { add(WatchOption(it, WatchTier.LIKELY)) }
        return out.values.sortedBy { it.tier.ordinal }
    }

    private val ASCII_WORD = Regex("[A-Za-z]{4,}")

    private fun asciiWords(name: String): List<String> =
        ASCII_WORD.findAll(name).map { it.value.lowercase() }
            .filter { TeamNames.tokens(it).isNotEmpty() }
            .toList()
}
