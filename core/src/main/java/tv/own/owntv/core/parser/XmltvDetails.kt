package tv.own.owntv.core.parser

/**
 * The optional parts of an XMLTV `<programme>` behind the guide's details line
 * ("Animation · Family · 2023 · FSK 0 · 95 min", Stage G1). Every field is null when the feed does not
 * carry it — the screens show only what is there, never a guessed value.
 *
 * [categories] holds up to [MAX_CATEGORIES] distinct `<category>` values joined by [CATEGORY_SEPARATOR];
 * a category name can itself contain a comma, so the join uses a character no feed writes.
 */
data class EpgDetails(
    val categories: String? = null,
    val year: Int? = null,
    val rating: String? = null,
    val lengthMin: Int? = null,
    val episode: String? = null,
) {
    /** The categories as a list, in feed order. */
    val categoryList: List<String>
        get() = categories?.split(CATEGORY_SEPARATOR).orEmpty()

    companion object {
        const val MAX_CATEGORIES = 3
        const val CATEGORY_SEPARATOR = '\u001F'
        val NONE = EpgDetails()
    }
}

/** Pure rules for [EpgDetails]; the streaming reader feeds them raw element text. */
internal object XmltvDetailRules {

    /** `<date>` is `YYYY`, `YYYYMMDD` or longer; the year is its first four digits when plausible. */
    fun year(raw: String?): Int? {
        val digits = raw?.trim()?.takeWhile { it.isDigit() } ?: return null
        if (digits.length < 4) return null
        return digits.substring(0, 4).toInt().takeIf { it in 1880..2100 }
    }

    /**
     * `<rating system="FSK"><value>16</value></rating>` → "FSK 16". A value that already names its
     * system ("FSK 16", "TV-MA", "PG-13") is kept as written.
     */
    fun rating(system: String?, value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val s = system?.trim()?.takeIf { it.isNotEmpty() }
        return if (s != null && v.all { it.isDigit() || it == '+' }) "$s $v" else v
    }

    /** `<length units="minutes|seconds|hours">95</length>` → minutes; nothing below one minute. */
    fun lengthMinutes(value: String?, units: String?): Int? {
        val n = value?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val minutes = when (units?.trim()?.lowercase()) {
            "seconds" -> n / 60
            "hours" -> n * 60
            else -> n
        }
        return minutes.takeIf { it > 0 }
    }

    /**
     * `<episode-num system="xmltv_ns">0.2.0/1</episode-num>` (zero-based season.episode.part) → "S1 E3";
     * `system="onscreen"` is kept as the broadcaster writes it. Any other system is ignored.
     */
    fun episode(system: String?, value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (system?.trim()?.lowercase()) {
            "xmltv_ns" -> {
                val parts = v.split('.')
                val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
                val episode = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
                when {
                    season != null && episode != null -> "S${season + 1} E${episode + 1}"
                    episode != null -> "E${episode + 1}"
                    season != null -> "S${season + 1}"
                    else -> null
                }
            }
            "onscreen" -> v
            else -> null
        }
    }

    /** Distinct, non-blank, at most [EpgDetails.MAX_CATEGORIES], joined; null when there are none. */
    fun categories(values: List<String>): String? = values.asSequence()
        .map { it.trim().replace(EpgDetails.CATEGORY_SEPARATOR, ' ') }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .take(EpgDetails.MAX_CATEGORIES)
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(EpgDetails.CATEGORY_SEPARATOR.toString())
}
