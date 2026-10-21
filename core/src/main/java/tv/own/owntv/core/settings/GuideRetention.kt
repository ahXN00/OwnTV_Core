package tv.own.owntv.core.settings

/**
 * Bounds for "Guide days to keep" — how much *future* guide is stored, forward from now.
 *
 * The maximum is deliberately lower than the playlist picker's 99 days, and the reason is measured
 * rather than cautious: a large lineup carries roughly 200,000 programme rows per day (7,083 channels
 * on the maintainer's television), so seven days is about 1.4 million rows and fourteen is 2.8
 * million, on a device whose heap ceiling is 192 MB. Beyond a fortnight no provider sends anything
 * anyway, so a larger number would only promise storage the feed cannot fill.
 */
object GuideRetention {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 14
    const val DEFAULT_DAYS = 7

    /** The day counts the picker offers directly; any other value is still valid and is shown. */
    val PRESET_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
}
