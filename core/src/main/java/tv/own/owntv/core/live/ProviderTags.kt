package tv.own.owntv.core.live

import java.text.Normalizer

/**
 * A provider name split the way Stage shows it: "DE| Sky Cinema Premieren ᴴᴰ" becomes the name
 * "Sky Cinema Premieren", the tag "HD" and the country "DE". Providers write their quality and
 * source marks as superscript letters; each run of them is one tag. The country prefix is shown
 * once, as the "GROUPS · DE" heading, instead of on every row.
 */
data class ProviderName(val name: String, val tags: List<String>, val country: String?)

object ProviderTags {
    // "DE|", "DE |", "UK |" — two or three capitals and a bar at the very start.
    private val countryPrefix = Regex("""^([A-Z]{2,3})\s*\|\s*""")

    // "UK: Sky Sports", "IT: DAZN 1" — some providers write a colon. A colon also ends plain labels
    // ("TV:", "VIP:", "PRIME:"), so it counts only after a real country code, and "TV" (Tuvalu) never.
    private val colonPrefix = Regex("""^([A-Z]{2}):\s*""")
    private val colonCountries: Set<String> =
        java.util.Locale.getISOCountries().toSet() + "UK" - "TV"

    private fun prefixOf(raw: String): MatchResult? =
        countryPrefix.find(raw) ?: colonPrefix.find(raw)?.takeIf { it.groupValues[1] in colonCountries }

    /** [raw] without the country prefix [parse] takes off ("DE| ", "UK: "); [raw] when it has none. */
    fun withoutCountryPrefix(raw: String): String =
        prefixOf(raw)?.let { raw.substring(it.range.last + 1) } ?: raw

    fun parse(raw: String): ProviderName {
        val prefix = prefixOf(raw)
        var rest = if (prefix != null) raw.substring(prefix.range.last + 1) else raw
        val tags = mutableListOf<String>()
        val name = StringBuilder()
        val run = StringBuilder()
        fun endRun() {
            if (run.isEmpty()) return
            val tag = Normalizer.normalize(run, Normalizer.Form.NFKC).uppercase()
            if (tag !in tags) tags += tag
            run.clear()
        }
        for (ch in rest) {
            if (isSuperscript(ch)) run.append(ch) else { endRun(); name.append(ch) }
        }
        endRun()
        rest = name.toString().replace(Regex("""\s+"""), " ").trim()
        // A name that was nothing but a prefix and tags keeps the provider's own spelling.
        if (rest.isEmpty()) return ProviderName(raw.trim(), emptyList(), null)
        return ProviderName(rest, tags, prefix?.groupValues?.get(1))
    }

    /**
     * The one country every group shares, for the "GROUPS · DE" heading; null when they differ or
     * any group has none (then the heading reads just "GROUPS").
     */
    fun sharedCountry(names: List<ProviderName>): String? =
        names.map { it.country }.distinct().singleOrNull()

    /**
     * A group's label in a category list: the bare name when [headingCountry] already shows its country
     * above the groups, else the name with its country put back ("UK| Sky Max"). A mixed list (UK|, US|)
     * has no shared heading, and stripping the prefix there left rows nobody could tell apart.
     */
    fun groupLabel(name: ProviderName, headingCountry: String?): String =
        if (name.country == null || name.country == headingCountry) name.name else "${name.country}| ${name.name}"

    // Superscript digits and letters, and the small-capital/modifier letters providers use for them
    // (ᴴᴰ, ʳᴬᵂ, ⁴ᴷ). Each folds to a plain letter or digit under NFKC.
    private fun isSuperscript(c: Char): Boolean =
        c in 'ᴬ'..'ᵪ' || c in 'ʰ'..'ʸ' || c in 'ˠ'..'ˤ' ||
            c in '⁰'..'ₜ' || c == '¹' || c == '²' || c == '³' ||
            c == 'ᶜ' || c == 'ᶠ' || c == 'ᶻ' || c == 'ꟸ' || c == 'ꟹ'
}
