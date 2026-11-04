package tv.own.owntv.core.catalog

import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.TitleNormalizer

/** Search/display metadata derived once from the provider's untouched raw title. */
data class ProviderCatalogMetadata(
    val canonicalTitle: String,
    val titleSignature: String,
    val parsedYear: Int?,
    val language: String?,
    val qualityRank: Int,
    val capabilities: String?,
)

/**
 * Runs once for every film and series a sync writes — 230,000 titles for one large playlist — so it
 * runs each pattern only when the title holds the literal text that pattern needs. On a TV every
 * regex call is a trip into ICU through JNI, and those calls were most of the parse thread during an
 * import. A pattern whose text is present still decides, so the result is exactly what running every
 * pattern gives.
 */
object ProviderCatalogMetadataParser {
    fun parse(rawName: String, providerYear: Int?): ProviderCatalogMetadata {
        val t = Title(rawName)
        val normalized = TitleNormalizer.normalize(cleanForTitle(t))
        return ProviderCatalogMetadata(
            canonicalTitle = normalized.query,
            titleSignature = normalized.query.filter(Char::isLetterOrDigit).lowercase()
                .ifBlank { "__unmatchable_${rawName.hashCode().toUInt().toString(16)}" },
            parsedYear = providerYear ?: normalized.year,
            language = extractLanguage(t),
            qualityRank = extractQualityRank(t),
            capabilities = extractCapabilities(t).takeIf { it.isNotEmpty() }?.joinToString(" • "),
        )
    }

    /**
     * A title plus what the gates test: [folded] is the title upper-cased the way a case-insensitive
     * pattern compares it. The Kelvin sign and the long s are the only characters outside ASCII that
     * fold onto an ASCII letter (k, s), so they become K and S here; everything else that is not ASCII
     * matches no letter in these patterns.
     */
    private class Title(val raw: String) {
        val folded: String = buildString(raw.length) {
            for (c in raw) append(
                when (c) {
                    in 'a'..'z' -> c - 32
                    'K' -> 'K'
                    'ſ' -> 'S'
                    else -> c
                },
            )
        }

        fun has(vararg texts: String): Boolean = texts.any { folded.contains(it) }

        /** The case-sensitive language patterns need one of their separator characters. */
        val mayHaveExplicitLanguage = raw.any { it == '|' || it == '[' || it == '(' || it == '{' }
        val mayHaveLeadingLanguage = raw.any { it == '|' || it == ':' || it == '_' || it == '-' }

        val mayHaveResolution = has("8K", "4K", "UHD", "2160P", "3840P", "FHD", "1080P", "HD", "720P", "SD")
        val mayHaveCapability = has("DOLBY", "DOVI", "DV", "HDR")
        val mayHaveAtmos = has("ATMOS")
        val mayHave71 = has("7.1", "7_1", "7 1")
        val mayHave51 = has("5.1", "5_1", "5 1")
        val mayHave20 = has("2.0", "2_0", "2 0")
        val mayHaveAudio = mayHaveAtmos || mayHave71 || mayHave51 || mayHave20 || has("5.0", "5_0", "5 0", "7.0", "7_0", "7 0")
    }

    private fun extractLanguage(t: Title): String? {
        if (t.mayHaveExplicitLanguage) EXPLICIT_LANGUAGE.find(t.raw)?.groupValues?.get(1)?.let { return it.uppercase() }
        if (t.mayHaveLeadingLanguage) LEADING_LANGUAGE.find(t.raw)?.groupValues?.get(1)?.let { return it.uppercase() }
        return TRAILING_LANGUAGE.find(t.raw)?.groupValues?.get(1)?.uppercase()
    }

    private fun cleanForTitle(t: Title): String {
        var s = t.raw
        if (t.mayHaveExplicitLanguage) s = s.replace(EXPLICIT_LANGUAGE, " ")
        if (t.mayHaveLeadingLanguage) s = s.replace(LEADING_LANGUAGE, "")
        s = s.replace(TRAILING_LANGUAGE, "")
        if (t.mayHaveResolution) s = s.replace(ALL_RESOLUTION_MARKERS, " ")
        if (t.has("HDR10+")) s = s.replace(HDR10_PLUS, " ")
        if (t.mayHaveCapability) s = s.replace(ALL_CAPABILITY_MARKERS, " ")
        if (t.mayHaveAudio) s = s.replace(ALL_AUDIO_MARKERS, " ")
        return s
    }

    private fun extractQualityRank(t: Title): Int = when {
        !t.mayHaveResolution -> 0
        t.has("8K") && EIGHT_K.containsMatchIn(t.raw) -> 5
        t.has("4K", "UHD", "2160P", "3840P") && UHD.containsMatchIn(t.raw) -> 4
        t.has("FHD", "1080P") && FHD.containsMatchIn(t.raw) -> 3
        t.has("HD", "720P") && HD.containsMatchIn(t.raw) -> 2
        t.has("SD") && SD.containsMatchIn(t.raw) -> 1
        else -> 0
    }

    private fun extractCapabilities(t: Title): List<String> = buildList {
        if (t.mayHaveCapability) when {
            t.has("DOLBY", "DOVI", "DV") && DOLBY_VISION.containsMatchIn(t.raw) -> add("Dolby Vision")
            t.has("HDR10+") && HDR10_PLUS.containsMatchIn(t.raw) -> add("HDR10+")
            t.has("HDR10") && HDR10.containsMatchIn(t.raw) -> add("HDR10")
            t.has("HDR") && HDR.containsMatchIn(t.raw) -> add("HDR")
        }
        if (t.mayHaveAudio) when {
            t.mayHaveAtmos && ATMOS.containsMatchIn(t.raw) -> add("Dolby Atmos")
            t.mayHave71 && SURROUND_7_1.containsMatchIn(t.raw) -> add("7.1 Audio")
            t.mayHave51 && SURROUND_5_1.containsMatchIn(t.raw) -> add("5.1 Audio")
            t.mayHave20 && STEREO_2_0.containsMatchIn(t.raw) -> add("2.0 Audio")
        }
    }

    private const val LANGUAGE_CODES = "DE|EN|FR|ES|PT|AR|NL|PL|TR|RU|BG|ZH|HR|CS|DA|ET|FI|EL|HE|HI|HU|ID|JA|KO|LV|LT|MS|NO|FA|RO|SR|SK|SL|SV|TH|UK|VI"
    private val EXPLICIT_LANGUAGE = Regex("""(?i)[|\[({]\s*($LANGUAGE_CODES)\s*[|\])}]""")
    private val LEADING_LANGUAGE = Regex("""^\s*($LANGUAGE_CODES)(?=\s*[|:_-])""")
    private val TRAILING_LANGUAGE = Regex("""\s($LANGUAGE_CODES)\s*$""")
    private val EIGHT_K = Regex("""(?i)\b8K\b""")
    private val UHD = Regex("""(?i)\b(?:4K|UHD|2160P|3840P)\b""")
    private val FHD = Regex("""(?i)\b(?:FHD|1080P)\b""")
    private val HD = Regex("""(?i)\b(?:HD|720P)\b""")
    private val SD = Regex("""(?i)\bSD\b""")
    private val ALL_RESOLUTION_MARKERS = Regex("""(?i)\b(?:8K|4K|UHD|2160P|3840P|FHD|1080P|HD|720P|SD)\b""")
    private val DOLBY_VISION = Regex("""(?i)\b(?:DOLBY\s*VISION|DOVI|DV)\b""")
    private val HDR10_PLUS = Regex("""(?i)\bHDR10\+(?!\w)""")
    private val HDR10 = Regex("""(?i)\bHDR10\b""")
    private val HDR = Regex("""(?i)\bHDR\b""")
    private val ALL_CAPABILITY_MARKERS = Regex("""(?i)\b(?:DOLBY\s*VISION|DOVI|DV|HDR10\+?|HDR)\b""")
    private val ATMOS = Regex("""(?i)\b(?:DOLBY\s*)?ATMOS\b""")
    private val SURROUND_7_1 = Regex("""(?i)(?:\b7[._ ]1\b|\bDDP?\s*7[._ ]1\b)""")
    private val SURROUND_5_1 = Regex("""(?i)(?:\b5[._ ]1\b|\bDDP?\s*5[._ ]1\b|\bAC-?3\s*5[._ ]1\b)""")
    private val STEREO_2_0 = Regex("""(?i)\b2[._ ]0\b""")
    private val ALL_AUDIO_MARKERS = Regex("""(?i)\b(?:(?:DOLBY\s*)?ATMOS|DDP?\s*[57][._ ]1|AC-?3\s*5[._ ]1|[257][._ ]0|[57][._ ]1)\b""")
}

fun MovieEntity.withProviderCatalogMetadata(): MovieEntity {
    val parsed = ProviderCatalogMetadataParser.parse(name, year)
    return copy(
        canonicalTitle = parsed.canonicalTitle,
        titleSignature = parsed.titleSignature,
        parsedYear = parsed.parsedYear,
        providerLanguage = parsed.language,
        qualityRank = parsed.qualityRank,
        advertisedCapabilities = parsed.capabilities,
    )
}

fun SeriesEntity.withProviderCatalogMetadata(): SeriesEntity {
    val parsed = ProviderCatalogMetadataParser.parse(name, year)
    return copy(
        canonicalTitle = parsed.canonicalTitle,
        titleSignature = parsed.titleSignature,
        parsedYear = parsed.parsedYear,
        providerLanguage = parsed.language,
        qualityRank = parsed.qualityRank,
        advertisedCapabilities = parsed.capabilities,
    )
}
