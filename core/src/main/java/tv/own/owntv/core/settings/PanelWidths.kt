package tv.own.owntv.core.settings

import kotlin.math.roundToInt

/** The three browse sections that own a 3-panel layout (category rail · item list/grid · preview). */
enum class PanelSection { LIVE, MOVIES, SERIES }

/**
 * Manual panel-width adjustment (per section, per panel).
 *
 * Each panel holds its SHARE OF THE SCREEN in percent, and the three must add up to exactly 100 — the
 * user sees a running total and can't save until it reads 100%. That keeps the numbers meaning what
 * they look like they mean: "preview panel 40%" really is 40% of the row.
 *
 * Values are whole multiples of [STEP], so a total of exactly 100 is always reachable by stepping.
 */
object PanelWidthLimits {
    /** Category and list panels stay usable; only the third panel may use 0 to mean hidden. */
    const val MIN = 10
    const val MAX = 80
    const val STEP = 5
    const val TOTAL = 100

    fun clamp(pct: Int): Int = pct.coerceIn(MIN, MAX)

    /** Snap to the nearest [STEP] and clamp — every stored/displayed value goes through here. */
    fun snap(pct: Int): Int = clamp((pct.toFloat() / STEP).roundToInt() * STEP)

    /** The third panel has one extra state: exactly 0 means that it is not composed at all. */
    fun snapPreview(pct: Int): Int = if (pct <= 0) 0 else snap(pct)
}

/**
 * The Cinematic detail block's height, as a percentage of the screen. Its own scale, because it is
 * a height and takes part in no 100% row budget: 0 really does mean "no detail block", and the
 * ceiling only stops the posters being squeezed off the screen entirely.
 */
const val CINEMATIC_DETAILS_MAX = 60
const val CINEMATIC_DETAILS_DEFAULT = 35

/** One section's three shares, in percent of the row. */
data class PanelShares(val category: Int, val list: Int, val preview: Int) {
    val total: Int get() = category + list + preview
    val isValid: Boolean get() =
        category in PanelWidthLimits.MIN..PanelWidthLimits.MAX &&
            list in PanelWidthLimits.MIN..PanelWidthLimits.MAX &&
            (preview == 0 || preview in PanelWidthLimits.MIN..PanelWidthLimits.MAX) &&
            total == PanelWidthLimits.TOTAL
}

/**
 * Nudges [shares] until they add up to exactly 100, moving the difference onto the biggest panel
 * first (it can absorb it least visibly) and spilling onto the others if that one hits a limit.
 */
fun balanceToTotal(shares: PanelShares): PanelShares {
    val values = intArrayOf(
        PanelWidthLimits.snap(shares.category),
        PanelWidthLimits.snap(shares.list),
        PanelWidthLimits.snapPreview(shares.preview),
    )
    val minimums = intArrayOf(PanelWidthLimits.MIN, PanelWidthLimits.MIN, if (values[2] == 0) 0 else PanelWidthLimits.MIN)
    // Biggest first, so the correction lands where it shows least.
    val order = values.indices.sortedByDescending { values[it] }
    var diff = PanelWidthLimits.TOTAL - values.sum()
    for (i in order) {
        if (diff == 0) break
        val moved = (values[i] + diff).coerceIn(minimums[i], PanelWidthLimits.MAX)
        diff -= moved - values[i]
        values[i] = moved
    }
    return PanelShares(values[0], values[1], values[2])
}
