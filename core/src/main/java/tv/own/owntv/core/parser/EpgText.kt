package tv.own.owntv.core.parser

/**
 * Guide text as a feed should have sent it. Some providers double-escape their synopses, so the words
 * arrive with a literal backslash-r-backslash-n between them ("England\r\nAt 29-9-2026") and the guide
 * showed the escape codes. Those become real line breaks (a tab a space), runs of spaces collapse, and
 * blank lines at the ends go.
 */
object EpgText {
    private val lineBreaks = Regex("""(\\r\\n|\\n|\\r)+""")
    private val spaces = Regex("""[ \t]{2,}""")

    fun clean(text: String?): String? = text
        ?.replace(lineBreaks, "\n")
        ?.replace("\\t", " ")
        ?.replace(spaces, " ")
        ?.lines()?.joinToString("\n") { it.trim() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
