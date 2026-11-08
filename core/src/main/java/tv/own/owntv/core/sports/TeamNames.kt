package tv.own.owntv.core.sports

import java.text.Normalizer
import tv.own.owntv.core.sports.model.Team

/**
 * Team-name matching for "where is it live": ESPN says "Bayern Munich", a German guide says
 * "FC Bayern München – 1. FC Köln". Both sides reduce to *distinctive tokens*: accents folded, club
 * prefixes and numbers dropped, city exonyms mapped to one spelling, doubled letters collapsed (Nassr ↔
 * Nasr). A team is found in a title when every token of one of its name variants is present.
 *
 * Not [tv.own.owntv.core.epg.EpgMatcher.normalizeForEpg]: that one drops country words at the ends of a
 * name, and "Germany" is a team here; it also keeps accents. Pure Kotlin, unit-tested.
 */
object TeamNames {

    // Club-form words that say nothing about *which* club ("FC", "1.", "de"). Never words that do tell
    // two clubs apart: "real", "city", "united", "athletic" stay.
    private val NOISE = setOf(
        "fc", "sc", "ac", "afc", "cf", "sv", "tsv", "tsg", "vfl", "fsv", "bsc", "bv", "sk", "fk", "nk",
        "ssc", "ss", "as", "us", "sd", "cd", "ud", "rc", "rcd", "ca", "club", "calcio", "futbol", "fussball",
        "de", "del", "la", "el", "al", "the", "and",
    )

    // Spellings mapped to one form, after accent folding and letter collapsing.
    private val EXONYMS = mapOf(
        "munchen" to "munich", "muenchen" to "munich",
        "koln" to "cologne", "koeln" to "cologne",
        "milano" to "milan", "torino" to "turin", "roma" to "rome", "napoli" to "naples",
        "firenze" to "florence", "genova" to "genoa", "venezia" to "venice",
        "lisboa" to "lisbon", "sevila" to "seville", "praha" to "prague", "wien" to "vienna",
        "bruxeles" to "brusels", "brusel" to "brusels",
        "moskva" to "moscow", "warszawa" to "warsaw", "kobenhavn" to "copenhagen",
        "goteborg" to "gothenburg", "beograd" to "belgrade", "bucuresti" to "bucharest",
        "nurnberg" to "nuremberg", "nuernberg" to "nuremberg",
        // National sides, as the guides of the default regions write them.
        "deutschland" to "germany", "frankreich" to "france", "spanien" to "spain", "espana" to "spain",
        "italien" to "italy", "italia" to "italy", "turkiye" to "turkey", "turkei" to "turkey",
        "osterreich" to "austria", "schweiz" to "switzerland", "suisse" to "switzerland",
        "niederlande" to "netherlands", "holland" to "netherlands",
        "angleterre" to "england", "allemagne" to "germany", "alemania" to "germany",
        "inglaterra" to "england", "brasil" to "brazil", "brasilien" to "brazil",
    )

    // Words that never stand alone as a team: "Manchester City" minus its city is not "City".
    private val GENERIC = setOf(
        "city", "united", "utd", "town", "county", "rovers", "athletic", "sporting", "real", "fc",
        "club", "team", "women", "w", "u19", "u21", "u23",
    )

    private val COMBINING = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val DIGITS = Regex("\\p{N}+")
    private val DOUBLED = Regex("(\\p{L})\\1+")

    /** The distinctive tokens of [text], in order, exonyms mapped. A raw title gives its tokens the same way. */
    fun tokens(text: String): List<String> {
        val folded = Normalizer.normalize(text.replace('ı', 'i').replace('ß', 's'), Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
        return folded.split(NON_ALNUM)
            .asSequence()
            .filter { it.isNotEmpty() && !DIGITS.matches(it) }
            .map { it.replace(DOUBLED, "$1") }
            .map { t -> (EXONYMS[t] ?: EXONYMS[t.umlautFolded()] ?: t.umlautFolded()).replace(DOUBLED, "$1") }
            .filter { it !in NOISE }
            .toList()
    }

    /** "muenchen" → "munchen": German guides write ü as ue when the font or feed has no umlauts. */
    private fun String.umlautFolded(): String =
        if (length > 3) replace("ae", "a").replace("oe", "o").replace("ue", "u") else this

    /**
     * The name variants of a team, each a set of tokens that must all appear: display, short and
     * location-less names, and the abbreviation (3+ letters, as a single token).
     */
    fun variantsOf(team: Team): List<Set<String>> {
        val out = LinkedHashSet<Set<String>>()
        fun add(tokens: Collection<String>) {
            val set = tokens.toSet()
            if (set.isNotEmpty() && !(set.size == 1 && set.single() in GENERIC)) out += set
        }
        val display = tokens(team.name)
        add(display)
        add(tokens(team.shortName))
        team.location?.let { loc ->
            val locTokens = tokens(loc).toSet()
            add(display.filter { it !in locTokens })
        }
        team.abbreviation.lowercase().takeIf { it.length >= 3 && it.all(Char::isLetter) }?.let { add(listOf(it)) }
        return out.toList()
    }

    /** A team, prepared once for many titles. */
    class Key internal constructor(val variants: List<Set<String>>) {
        fun foundIn(titleTokens: Set<String>): Boolean = variants.any { titleTokens.containsAll(it) }
    }

    fun keyOf(team: Team): Key = Key(variantsOf(team))
}
