package tv.own.owntv.core.settings

/** The three parts of the TV's top-right clock that can be coloured. */
enum class ClockPart { TIME, DATE, WEATHER }

/**
 * The clock's colours as stored: each blank (the shipped look: white time, grey date and weather),
 * [ACCENT] (follows the accent colour) or a "#RRGGBB" hex code.
 */
data class ClockColors(val time: String = "", val date: String = "", val weather: String = "") {
    fun of(part: ClockPart): String = when (part) {
        ClockPart.TIME -> time
        ClockPart.DATE -> date
        ClockPart.WEATHER -> weather
    }

    companion object {
        const val ACCENT = "accent"
    }
}
