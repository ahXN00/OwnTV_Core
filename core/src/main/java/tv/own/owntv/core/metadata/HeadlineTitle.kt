package tv.own.owntv.core.metadata

/**
 * A film's or show's name as a headline: the big title on its page, in a hero, in a preview. Providers
 * lead names with a tag before or between bars ("|MULTI| Trailer Park Boys (2001)", "EN| …"), and at
 * headline size that tag is the loudest thing on screen while saying nothing about the title. Only
 * those leading tags go; the rest is the provider's own spelling, as the poster label shows it —
 * "4K-NF - …" and "EN - …" stay, because they tell copies of one title apart.
 *
 * Unlike [TitleNormalizer], which builds a search query, nothing else is touched: the year, brackets
 * and quality marks are part of what the user reads.
 */
object HeadlineTitle {
    // "|MULTI| ", "|EN|", "EN| ", "4K | " — up to eight capitals, digits or '+' at the very start.
    private val LEADING_TAG = Regex("""^\s*\|?\s*[A-Z0-9+]{1,8}\s*\|\s*""")

    fun of(raw: String): String {
        var s = raw
        var prev: String
        // Stacked tags ("|EN| |4K| Title") peel one at a time.
        do { prev = s; s = s.replaceFirst(LEADING_TAG, "") } while (s != prev)
        // A name that was nothing but tags keeps the provider's spelling.
        return s.trim().ifEmpty { raw.trim() }
    }
}
