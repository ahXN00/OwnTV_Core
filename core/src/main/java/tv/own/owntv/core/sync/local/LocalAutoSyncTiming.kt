package tv.own.owntv.core.sync.local

/**
 * How often a device syncs with its paired devices without anybody asking.
 *
 * The same three questions the EPG refresh modes ask — is this done at all, on a timer, or the
 * moment the app is there — and named the same way for the same reason: a user reading two lists
 * of "off / on startup / every few hours" should not have to learn two vocabularies.
 *
 * [intervalMs] is null for the two modes that are not on a timer. That is not an oversight:
 * [OFF] never runs, and [STARTUP] runs whenever it is told to, which is a different thing from
 * running every X milliseconds.
 */
enum class LocalAutoSyncTiming(val intervalMs: Long?) {
    /** Never. Auto-sync is off and nothing is scheduled. */
    OFF(null),

    /**
     * At the first opportunity: when the app starts, and again whenever the network comes back.
     *
     * The one-time work this asks for waits for the network rather than failing, so a television
     * that was woken up with the router still booting syncs as soon as the router is up.
     */
    STARTUP(null),

    /** Every six hours — often enough for a phone that is charging in the next room. */
    HOURS_6(6 * 3600_000L),

    /** Once a day. */
    HOURS_24(24 * 3600_000L),

    /** Once a week. */
    DAYS_7(7 * 24 * 3600_000L),
    ;

    /** True for the modes that are on a timer, and so can be handed to WorkManager as a period. */
    val isPeriodic: Boolean
        get() = intervalMs != null

    /** The mode a stored name falls back to when it is not one of these. */
    companion object {
        val DEFAULT = OFF
    }
}
