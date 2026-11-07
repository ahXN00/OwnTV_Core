package tv.own.owntv.core.sync.local

/**
 * The least time between two successful automatic syncs with one paired device. A pass is only ever
 * started by something happening — the app opening or closing, the Wi-Fi coming back, the phone's
 * hourly check — and this decides whether that moment is soon enough after the last one to skip.
 *
 * [IMMEDIATELY] has no gap: every one of those moments syncs.
 */
enum class LocalAutoSyncTiming(val gapMs: Long) {
    IMMEDIATELY(0L),
    HOURS_6(6 * 3600_000L),
    HOURS_24(24 * 3600_000L),
    DAYS_7(7 * 24 * 3600_000L),
    ;

    companion object {
        val DEFAULT = HOURS_6

        /** A stored name back; anything this build does not know reads as [DEFAULT]. */
        fun parse(name: String?): LocalAutoSyncTiming = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
