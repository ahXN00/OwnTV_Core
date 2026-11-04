package tv.own.owntv.core.customize

/**
 * Rule engine for bulk renaming channel/movie/series names. Pure functions over
 * (name, rules, options) — no Android dependencies, fully JVM-testable (see
 * app/src/test/.../RenameRulesTest.kt).
 *
 * Semantics:
 *  - Rules run top to bottom, each on the result of the previous one.
 *  - ADD prepends/appendends exactly one token (a rule with 2+ tokens is skipped defensively).
 *  - REMOVE PREFIX/SUFFIX is boundary-only. Values are tried left-to-right and the first matching
 *    value is removed. A suffix rule never edits the middle of a name (and vice versa).
 *  - A result that is blank or identical to the input is rejected: the original name is returned
 *    with [Result.changed] = false, so the review dialog lists the row as "unchanged" instead of
 *    offering a broken rename.
 *
 * [Rule.pattern] is engine-internal: when set, the rule matches a raw regex instead of literal
 * tokens. The UI rule builder never produces it; the auto-cleanup preset uses it for emoji
 * (supplementary-plane code points need the regex-engine `\x{...}` syntax).
 */
object RenameRules {

    enum class Action { ADD, REMOVE }
    enum class Placement { PREFIX, SUFFIX }
    enum class AutoLabel { COUNTRY_PROVIDER, QUALITY_CODEC, EMOJI_SYMBOLS }

    data class Rule(
        val action: Action,
        val placement: Placement,
        /** Literal value text; split on ';' into individual tokens at apply time. */
        val value: String = "",
        /** Internal: raw regex applied directly instead of [value] tokens (auto-cleanup emoji). */
        val pattern: String? = null,
        /** Semantic label for an auto-cleanup rule; the UI supplies localized wording. */
        val autoLabel: AutoLabel? = null,
    )

    data class Options(
        /** Eat the separator adjacent to a removed token and clean up stray whitespace. */
        val trimLeftovers: Boolean = true,
        /** Case-insensitive matching for REMOVE rules. */
        val ignoreCase: Boolean = true,
    )

    data class Result(val name: String, val changed: Boolean)

    const val SEPARATOR = ";"

    /** Leading/trailing leftovers cleaned after every rule when the option is enabled. */
    private const val EDGE_SEPARATORS = "[\\s|\\-–—·:._,]+"

    /** Country/provider tags in the agreed tagged forms only: XX|, |XX|, [XX], (XX). */
    internal const val COUNTRY_TAG_PATTERN = "^(?:\\|?[A-Za-z]{2,4}\\s*\\||\\[[A-Za-z]{2,4}]|\\([A-Za-z]{2,4}\\))"

    private val SPACE_RUNS = Regex(" {2,}")
    private val LEADING_LEFTOVERS = Regex("^$EDGE_SEPARATORS")
    private val TRAILING_LEFTOVERS = Regex("${EDGE_SEPARATORS}\\z")

    /** Compiled rule regexes. A standing cleanup runs the same few rules over every channel of a
     *  playlist at each sync ([ChannelNameCleanup]), so compiling them per name would dominate. */
    private const val REGEX_CACHE_MAX = 256
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun regex(pattern: String, ignoreCase: Boolean): Regex {
        if (regexCache.size > REGEX_CACHE_MAX) regexCache.clear()
        val key = if (ignoreCase) "i$pattern" else "c$pattern"
        return regexCache.getOrPut(key) {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        }
    }

    /**
     * The bulk-rename entry point: applies [rules] to [original] and returns the proposed name.
     * Never returns a blank name — a blank result falls back to the original with changed=false.
     */
    fun apply(original: String, rules: List<Rule>, options: Options = Options()): Result {
        val raw = applyRaw(original, rules, options)
        if (raw.isBlank() || raw == original) return Result(original, changed = false)
        return Result(raw, changed = true)
    }

    /**
     * Like [apply] but without the blank guard: returns the raw pipeline output, which MAY be
     * blank. The review dialog uses this to distinguish "blank name rejected" from "no rule
     * matched" for the row list; everything else uses [apply].
     */
    fun applyRaw(original: String, rules: List<Rule>, options: Options = Options()): String {
        var name = original
        for (rule in rules) {
            name = if (rule.pattern != null) {
                removePatternAnywhere(name, rule.pattern, options)
            } else when (rule.action) {
                Action.ADD -> {
                    val tokens = tokensOf(rule)
                    // ADD validates exactly one value in the UI; the engine stays defensive.
                    if (tokens.size != 1) name
                    else {
                        // WYSIWYG: retain the user's intentional space around the added value.
                        // Validation counts trimmed, non-empty tokens. Use that same non-empty
                        // segment here (while preserving its intentional surrounding spaces), so
                        // input such as "; ★ " does not accidentally add the empty first segment.
                        val raw = rule.value.split(SEPARATOR).first { it.trim().isNotEmpty() }
                        if (rule.placement == Placement.PREFIX) raw + name else name + raw
                    }
                }
                Action.REMOVE -> removeFirstBoundaryMatch(name, tokensOf(rule), rule.placement, options)
            }
            if (options.trimLeftovers) name = cleanLeftovers(name)
        }
        return name
    }

    /** Splits a rule's value on ';', trimming each token and dropping blanks. */
    fun tokensOf(rule: Rule): List<String> =
        rule.value.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The ✨ Auto cleanup preset: country tags, quality tags, emoji — each emitted
     * twice (PREFIX + SUFFIX) because tags hang off both ends of names ("DE | FUSS-TV 3 [HD]").
     * Space collapse comes from [Options.trimLeftovers], on by default.
     */
    fun autoCleanupRules(): List<Rule> {
        val country = COUNTRY_TAG_PATTERN
        // Quality/codec tags are the auto-cleanup exception to boundary-only user rules: remove
        // them anywhere as standalone tokens, including their optional []/() wrappers.
        val quality =
            "(?:\\[|\\()?\\b(?:4K|8K|UHD|FHD|HD|SD|RAW|VIP|HEVC|H265|H264|DOLBY|MULTI|QHD|2K|HDR|DV|2160P|2160|1080P|1080|720P|720|480P|480)\\b(?:\\]|\\))?"
        // Emoji and pictographic symbols (arrows, dingbats, misc symbols, misc symbols + arrows,
        // variation selector, the large emoji blocks U+1F000–U+1FAFF, and regional indicators for
        // flags U+1F1E6–U+1F1FF). `+` so a multi-codepoint emoji (a flag is two regional
        // indicators) is consumed as one unit.
        val emoji = "(?:[\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{25A0}-\\x{25FF}\\x{2600}-\\x{27BF}" +
            "\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{1F000}-\\x{1FAFF}\\x{1F1E6}-\\x{1F1FF}])+"
        return listOf(
            Rule(
                Action.REMOVE,
                Placement.PREFIX,
                pattern = country,
                autoLabel = AutoLabel.COUNTRY_PROVIDER,
            ),
            Rule(
                Action.REMOVE,
                Placement.PREFIX,
                pattern = quality,
                autoLabel = AutoLabel.QUALITY_CODEC,
            ),
            Rule(
                Action.REMOVE,
                Placement.PREFIX,
                pattern = emoji,
                autoLabel = AutoLabel.EMOJI_SYMBOLS,
            ),
        )
    }

    /** Tries values in order and removes the first one found at the requested boundary. */
    private fun removeFirstBoundaryMatch(
        name: String,
        tokens: List<String>,
        placement: Placement,
        options: Options,
    ): String {
        tokens.forEach { token ->
            val escaped = Regex.escape(token)
            val wrapped = "(?:\\[$escaped\\]|\\($escaped\\)|$escaped)"
            // A bare token must also be a complete boundary component. Without this guard a
            // suffix rule for "MAN" would rename "BATMAN" to "BAT". Wrapped tokens are naturally
            // delimited by their closing bracket; the look-around remains valid for them too.
            val regex = regex(
                if (placement == Placement.PREFIX) "^$wrapped(?![\\p{L}\\p{N}])"
                else "(?<![\\p{L}\\p{N}])$wrapped$",
                options.ignoreCase,
            )
            if (regex.containsMatchIn(name)) return regex.replaceFirst(name, "")
        }
        return name
    }

    /** Auto-cleanup-only regex removal. Unlike user REMOVE rules, this intentionally applies
     *  anywhere because quality/codec tags are specified as anywhere noise. */
    private fun removePatternAnywhere(name: String, pattern: String, options: Options): String =
        regex(pattern, options.ignoreCase).replace(name, "")

    /** Collapse repeated spaces and strip only edge separators; interior punctuation is preserved. */
    private fun cleanLeftovers(name: String): String =
        name.replace(SPACE_RUNS, " ")
            .replace(LEADING_LEFTOVERS, "")
            .replace(TRAILING_LEFTOVERS, "")
}
