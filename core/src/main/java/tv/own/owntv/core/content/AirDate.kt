package tv.own.owntv.core.content

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When an episode first aired, merged from the two places that might know: the provider's own date,
 * stored on the episode, and TMDB's, held in the metadata cache.
 *
 * The provider wins when it has one — it is describing the very file being played, and a panel that
 * dates its episodes is usually describing the same broadcast run the user is watching. TMDB fills
 * the (very common) gap where the panel dates nothing at all.
 *
 * Both apps show this, so the merge and the parsing live here rather than being written twice.
 */
object AirDate {

    /** The episode's air date as epoch ms, or null when neither source knows one. */
    fun of(providerMs: Long?, tmdbDate: String?): Long? = providerMs ?: parse(tmdbDate)

    /**
     * TMDB's `yyyy-MM-dd` as epoch ms **at UTC midnight**.
     *
     * UTC deliberately, and it is not a detail: an air date is a calendar day, not an instant. Parsed
     * in the device's own zone and then formatted back in it, the day survives — but only because the
     * two cancel out. Anything that formats it elsewhere, or a device whose zone changes between the
     * two, would slide the date by one and show a user the day before the episode aired. Pinning both
     * ends to UTC removes the coincidence (see [formatter]).
     */
    fun parse(date: String?): Long? {
        val text = date?.trim()?.take(10)?.takeIf { it.isNotEmpty() } ?: return null
        if (!PATTERN.matches(text) || text.startsWith("0000")) return null
        return runCatching { formatter().parse(text)?.time }.getOrNull()
    }

    /** A `yyyy-MM-dd` parser fixed to UTC — see [parse]. Not shared: `SimpleDateFormat` is not thread-safe. */
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }

    /** The zone an air date is both parsed and displayed in, so the calendar day cannot drift. */
    val UTC: TimeZone get() = TimeZone.getTimeZone("UTC")

    private val PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
}
