package tv.own.owntv.core.sports

/**
 * The "Watch live" filter. Empty [languages], [countries] or [sourceIds] means any.
 * A language is an upper-case ISO 639-1 code, [UNKNOWN] for a channel whose name carries none; a
 * country is the provider's prefix ("DE", "UK"). [onlyConfirmed] keeps the guide's and the event
 * channels and hides "likely" and remembered ones. Unknown picture quality never hides a channel:
 * most names carry no quality tag, and a guess must not empty the list. [strict] decides what a
 * channel that fails the filter does: hidden when on, listed after the passing ones when off.
 */
data class WatchFilter(
    val languages: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val minQuality: MinQuality = MinQuality.ANY,
    val sourceIds: Set<Long> = emptySet(),
    val onlyConfirmed: Boolean = false,
    val showLikely: Boolean = true,
    val strict: Boolean = true,
) {
    fun accepts(o: WatchOption): Boolean =
        (languages.isEmpty() || (o.facets.language ?: UNKNOWN) in languages) &&
            (countries.isEmpty() || o.facets.country in countries) &&
            (o.facets.qualityRank == 0 || o.facets.qualityRank >= minQuality.rank) &&
            (sourceIds.isEmpty() || o.channel.sourceId in sourceIds) &&
            !(onlyConfirmed && o.tier > WatchTier.EVENT) &&
            !(!showLikely && o.tier == WatchTier.LIKELY)

    companion object {
        const val UNKNOWN = ""

        /**
         * The filter a match page opens with, from Settings → Sports: the preferred languages plus
         * Unknown (a channel naming no language is never hidden by default), the countries, the
         * minimum quality, the playlists and strict filtering. Remembered filters (`rememberFilters`)
         * are these same fields, saved. Only the chosen playlists among [liveSources] (this device's
         * live playlists) count: ids restored from another device name nothing here, and must not
         * hide every channel.
         */
        fun from(config: SportsConfig, appLanguage: String, liveSources: Collection<Long>): WatchFilter = WatchFilter(
            languages = config.preferredLanguages(appLanguage).let { if (it.isEmpty()) it else it + UNKNOWN },
            countries = config.countries,
            minQuality = config.minQuality,
            sourceIds = config.sourceIds.filterTo(HashSet()) { it in liveSources },
            onlyConfirmed = config.onlyConfirmed,
            showLikely = config.showLikely,
            strict = config.strictFilter,
        )
    }
}

/** What "Watch live" shows: the [shown] rows, how many [hidden], and the counts beside each choice. */
data class WatchList(
    val shown: List<WatchOption>,
    val total: Int,
    val hiddenLanguages: List<String>,
    val languageCounts: Map<String, Int>,
    val countryCounts: Map<String, Int>,
    val sourceCounts: Map<Long, Int>,
) {
    val hidden: Int get() = total - shown.size

    companion object {
        /**
         * Applies [filter] to [options] (already ranked). Within a tier, channels in the profile's
         * preferred languages come first ("German first · from Settings"). With [WatchFilter.strict]
         * off, the channels the filter rejects are listed too, after every passing one, so nothing is
         * hidden. Counts are over *every* option, so a choice always says how many it would bring back;
         * [alsoList] adds languages to list even at zero (the preferred ones). Rows the "likely" switch
         * removes are not counted at all: they are switched off, not filtered out.
         */
        fun of(
            options: List<WatchOption>,
            filter: WatchFilter,
            preferredLanguages: List<String> = emptyList(),
            alsoList: Set<String> = emptySet(),
        ): WatchList {
            val pool = if (filter.showLikely) options else options.filter { it.tier != WatchTier.LIKELY }
            fun langOf(o: WatchOption) = o.facets.language ?: WatchFilter.UNKNOWN
            fun prefIndex(o: WatchOption) = preferredLanguages.indexOf(langOf(o)).let { if (it < 0) Int.MAX_VALUE else it }
            val order = compareBy<WatchOption>({ it.tier.ordinal }, ::prefIndex)
            val (passing, failing) = pool.partition(filter::accepts)
            val shown = passing.sortedWith(order) + if (filter.strict) emptyList() else failing.sortedWith(order)
            val languageCounts = LinkedHashMap<String, Int>()
            (preferredLanguages + alsoList).forEach { languageCounts[it] = 0 }
            pool.forEach { languageCounts.merge(langOf(it), 1, Int::plus) }
            languageCounts.remove(WatchFilter.UNKNOWN)?.let { languageCounts[WatchFilter.UNKNOWN] = it }
            return WatchList(
                shown = shown,
                total = pool.size,
                hiddenLanguages = (pool - shown.toSet()).map(::langOf).distinct(),
                languageCounts = languageCounts,
                countryCounts = pool.mapNotNull { it.facets.country }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value },
                sourceCounts = pool.groupingBy { it.channel.sourceId }.eachCount(),
            )
        }
    }
}
