package tv.own.owntv.core.timeshift

/**
 * The arithmetic of a timeshift buffer (N4): how much to keep, when to refuse, when to reconnect.
 * Pure, so the storage rules — the part that can fill a television's flash — are pinned by unit tests.
 */
object TimeshiftRules {

    /** Owner decision 25: whatever the window, at least this much of the device stays free. */
    const val FREE_FLOOR_BYTES = 1024L * 1024 * 1024

    /** Not worth starting at all below the floor plus this: the buffer would only ever hold seconds. */
    const val START_MARGIN_BYTES = 64L * 1024 * 1024

    /** The newest pieces are never dropped, whatever the space: they are what is on screen at the live edge. */
    const val KEEP_NEWEST = 3

    /** The choices Settings offers, in minutes. */
    val WINDOW_CHOICES_MINUTES = listOf(15, 30, 45, 60)
    const val DEFAULT_WINDOW_MINUTES = 15

    fun windowMinutesOf(stored: Int): Int =
        stored.takeIf { it in WINDOW_CHOICES_MINUTES } ?: DEFAULT_WINDOW_MINUTES

    /** Enough room to start a buffer on this device. */
    fun canStart(usableBytes: Long): Boolean = usableBytes >= FREE_FLOOR_BYTES + START_MARGIN_BYTES

    /**
     * How many of the oldest pieces to delete now.
     *
     * [durationsMs] and [sizes] run oldest first and include the piece being written. A piece goes when
     * the window without it would still cover [windowMs] — so the buffer holds *at least* the window,
     * never less — or while the device is under [floorBytes] free. The newest [keepNewest] always stay.
     */
    fun piecesToDrop(
        durationsMs: List<Long>,
        sizes: List<Long>,
        windowMs: Long,
        usableBytes: Long,
        floorBytes: Long = FREE_FLOOR_BYTES,
        keepNewest: Int = KEEP_NEWEST,
    ): Int {
        var span = durationsMs.sum()
        var free = usableBytes
        var drop = 0
        val limit = (durationsMs.size - keepNewest).coerceAtLeast(0)
        while (drop < limit) {
            val overWindow = span - durationsMs[drop] >= windowMs
            val underFloor = free < floorBytes
            if (!overWindow && !underFloor) break
            span -= durationsMs[drop]
            free += sizes[drop]
            drop++
        }
        return drop
    }

    /** Reconnects in a row before a live buffer gives up — the same budget mpv's live reconnect has. */
    const val MAX_RECONNECTS = 5

    /** Unbroken downloading that earns the reconnect budget back (the live engines' own 60 s rule). */
    const val HEALTHY_MS = 60_000L

    fun reconnectDelayMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 3))

    /** A parked buffer (the user left the channel) is kept this long for a "Resume from buffer" (decision 25). */
    const val PARKED_GRACE_MS = 5 * 60_000L

    /** Watching another channel this long deletes a parked buffer at once (decision 25). */
    const val REPLACED_AFTER_MS = 2 * 60_000L
}
