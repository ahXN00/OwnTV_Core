package tv.own.owntv.core.sports

import java.text.Normalizer

/**
 * Competition and broadcaster names in guide titles and channel names: "Formula 1", "F1", "2. Bundesliga".
 *
 * Unlike team names ([TeamNames]) a competition is matched as a *phrase*: its words in order, side by
 * side, numbers kept. "Formula 1" is not in "Formula E", "Ligue 1" is not in "Ligue 2", and "Top 14" needs
 * the 14. A name found inside a longer competition name ([SportsCatalog.allCompetitionNames]) does not
 * count either: "Premier League" inside "Indian Premier League", "Bundesliga" inside "2. Bundesliga".
 * Pure Kotlin, unit-tested.
 */
object CompetitionNames {

    private val COMBINING = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val NUMBER = Regex("\\p{N}+")

    /**
     * Words a channel may carry beside a broadcaster's name and still be that broadcaster's channel:
     * "PEACOCK EVENT 3", "ESPN+ HD". Anything else ("APPLE TV+ SERIES 1") makes it a different channel.
     */
    private val FILLER = setOf(
        "sport", "sports", "hd", "fhd", "uhd", "sd", "hdr", "4k", "8k", "plus", "extra", "live", "event", "events",
        "ppv", "tv", "channel", "raw", "backup", "alt",
    )

    /** Lower-case, accent-folded words in order, numbers kept. */
    fun words(text: String): List<String> =
        Normalizer.normalize(text.replace('ı', 'i').replace("ß", "ss"), Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase()
            .split(NON_ALNUM)
            .filter { it.isNotEmpty() }

    /**
     * A competition (or broadcaster) prepared once for many titles. [wider] are, for each of its
     * phrases, the longer competition names that contain it.
     */
    class Key internal constructor(
        private val phrases: List<List<String>>,
        private val wider: Map<List<String>, List<List<String>>>,
    ) {
        /**
         * True when one of its names stands in [text] (from [words]) and not as part of a longer
         * competition's name. A [channel] name may number its feeds ("DAZN LaLiga 2" is LaLiga's second
         * channel), so there a longer name that only adds a number after it does not count.
         */
        fun foundIn(text: List<String>, channel: Boolean = false): Boolean = phrases.any { p ->
            occurrences(text, p).any { at -> wider[p].orEmpty().none { w -> (!channel || !onlyAddsNumber(p, w)) && covers(text, at, p, w) } }
        }

        /**
         * True when a channel is named for it and nothing else: a name, then only quality, feed numbers and
         * words like "Sport" or "Event" around it.
         */
        fun namesOnly(text: List<String>): Boolean = phrases.any { p ->
            occurrences(text, p).any { at ->
                text.withIndex().all { (i, w) -> i in at until at + p.size || w in FILLER || NUMBER.matches(w) }
            }
        }
    }

    /** The key of a competition known by [names] (several spellings of one competition). */
    fun keyOf(names: List<String>): Key {
        val phrases = names.map(::words).filter { it.isNotEmpty() }.distinct()
        val others = SportsCatalog.allCompetitionNames.map(::words).filter { it !in phrases }.distinct()
        return Key(phrases, phrases.associateWith { p -> others.filter { it.size > p.size && occurrences(it, p).isNotEmpty() } })
    }

    /** The key of a broadcaster ESPN names ("Peacock", "Apple TV"). */
    fun broadcasterKey(name: String): Key = Key(listOf(words(name)).filter { it.isNotEmpty() }, emptyMap())

    private fun occurrences(text: List<String>, phrase: List<String>): List<Int> =
        if (phrase.isEmpty() || phrase.size > text.size) emptyList()
        else (0..text.size - phrase.size).filter { i -> phrase.indices.all { j -> text[i + j] == phrase[j] } }

    /** True when the longer name [w] stands in [text] around the [phrase] found at [at]. */
    private fun covers(text: List<String>, at: Int, phrase: List<String>, w: List<String>): Boolean =
        occurrences(w, phrase).any { offset ->
            val start = at - offset
            start >= 0 && start + w.size <= text.size && w.indices.all { j -> text[start + j] == w[j] }
        }

    /** "LaLiga 2" around "LaLiga": the longer name is the phrase plus trailing numbers only. */
    private fun onlyAddsNumber(phrase: List<String>, w: List<String>): Boolean =
        w.size > phrase.size && w.subList(0, phrase.size) == phrase && w.drop(phrase.size).all { NUMBER.matches(it) }
}
