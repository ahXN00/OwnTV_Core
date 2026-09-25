package tv.own.owntv.core.player

import java.util.Locale

/**
 * The languages offered for "Preferred audio / subtitle language" (N14), shared by both apps.
 *
 * Codes are ISO 639-2/T, the form stream tracks are tagged with. The list is the app's locale
 * catalogue (`tools/i18n/locales.json`) plus languages common on IPTV that the app itself is not
 * translated into. Names come from the platform in the display language, so the list needs no strings.
 */
object TrackLanguages {

    /** "Original language" — the film's or show's own language (TMDB), else the stream's main track. */
    const val ORIGINAL = "original"

    val CODES: List<String> = listOf(
        // The locale catalogue.
        "ara", "ben", "bul", "ces", "dan", "deu", "ell", "eng", "est", "fas", "fin", "fra", "heb", "hin",
        "hrv", "hun", "ind", "ita", "jpn", "kor", "lav", "lit", "mal", "msa", "nld", "nor", "pol", "por",
        "ron", "rus", "slk", "slv", "spa", "srp", "swe", "tha", "tur", "ukr", "vie", "zho",
        // Common on IPTV, not an app language.
        "bos", "cat", "kur", "mkd", "pan", "sqi", "tam", "tel", "tgl", "urd",
    )

    /** [CODES] sorted by their name in [display], for a picker. */
    fun sortedFor(display: Locale): List<String> = CODES.sortedBy { displayName(it, display).lowercase(display) }

    /** The language's name in [display] ("Deutsch", "German" …); the code itself if the platform has none. */
    fun displayName(code: String, display: Locale): String =
        Locale.forLanguageTag(twoLetter(code) ?: code).getDisplayLanguage(display)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(display) }
            ?: code

    /** The ISO 639-1 form of a 639-2/T code ("deu" → "de"); null when the language has none. */
    fun twoLetter(code: String): String? {
        val t = code.trim().lowercase(Locale.ROOT)
        return Locale.getISOLanguages().firstOrNull {
            runCatching { Locale.forLanguageTag(it).isO3Language }.getOrNull() == t
        }
    }

    /** What an engine is given: [ORIGINAL] is not a language, so the engine gets no preference. */
    fun forEngine(pref: String): String = if (pref == ORIGINAL) "" else pref

    /** A TMDB `original_language` (ISO 639-1, "ko") as the 639-2 code tracks carry ("kor"); null if unknown. */
    fun fromIso6391(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
