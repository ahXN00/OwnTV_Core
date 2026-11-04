package tv.own.owntv.core.player

/**
 * Size (as a percentage of screen width) and screen corner/edge for the docked mini-player. Both are
 * user-configurable in Settings → Watching & recording → Mini-player and adjustable on the fly from
 * the mini-player's controls.
 * The window is laid out as `fillMaxWidth(pct/100f).aspectRatio(16f/9f)` aligned to [MiniPlayerPosition],
 * so it scales consistently across TV sizes and the UI zoom (unlike the old fixed 340×191 dp box).
 *
 * The alignment each position resolves to, and each position's display label, are rendering concerns
 * and live with the mini-player in the app module.
 */
object MiniPlayerSize {
    const val MIN = 10
    const val MAX = 50
    const val DEFAULT = 25
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun fraction(percent: Int): Float = clamp(percent) / 100f

    /** Next size for the on-the-fly resize button — steps up by [STEP], wrapping [MAX] back to [MIN]. */
    fun next(percent: Int): Int {
        val stepped = clamp(percent) + STEP
        return if (stepped > MAX) MIN else stepped
    }
}

/** The six docking spots for the mini-player. */
enum class MiniPlayerPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT;

    /** Next spot for the on-the-fly move button — cycles through all six in order. */
    fun next(): MiniPlayerPosition = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = BOTTOM_RIGHT
        fun fromName(name: String?): MiniPlayerPosition = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
