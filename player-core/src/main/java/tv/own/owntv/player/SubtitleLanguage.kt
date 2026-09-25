package tv.own.owntv.player

import java.util.Locale

/** Matches ISO-639-1/2 and regional tags (for example eng, en, and en-US). */
internal fun subtitleLanguageMatches(preferred: String, actual: String?): Boolean {
    if (preferred.isBlank() || actual.isNullOrBlank()) return false
    return languageIdentity(preferred) == languageIdentity(actual)
}

/**
 * ISO 639-2/B ("bibliographic") codes and their 639-2/T equivalents. Broadcast and Matroska tracks
 * often carry the B form (`ger`, `fre`), which [Locale] does not know, so `ger` never matched `de`.
 */
private val BIBLIOGRAPHIC_TO_TERMINOLOGY = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
    "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
    "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
    "tib" to "bod", "wel" to "cym",
)

private fun languageIdentity(code: String): String {
    val raw = code.trim().replace('_', '-').lowercase(Locale.ROOT)
    val normalized = BIBLIOGRAPHIC_TO_TERMINOLOGY[raw.substringBefore('-')]
        ?.let { it + raw.removePrefix(raw.substringBefore('-')) } ?: raw
    val locale = Locale.forLanguageTag(normalized)
    return runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
        .getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: normalized.substringBefore('-')
}

/**
 * An mpv `alang` / `slang` value for one preferred language: every spelling a track may carry, since
 * mpv compares the text. "deu" → "deu,ger,de". Blank stays blank (no preference).
 */
internal fun mpvLanguageList(code: String): String {
    val t = code.trim().lowercase(Locale.ROOT)
    if (t.isEmpty()) return ""
    val b = BIBLIOGRAPHIC_TO_TERMINOLOGY.entries.firstOrNull { it.value == t }?.key
    return listOfNotNull(t, b, tv.own.owntv.core.player.TrackLanguages.twoLetter(t)).distinct().joinToString(",")
}
