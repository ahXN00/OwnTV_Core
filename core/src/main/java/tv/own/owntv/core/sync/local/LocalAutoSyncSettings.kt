package tv.own.owntv.core.sync.local

/**
 * Every paired device's own auto-sync answer.
 *
 * Keyed by pairing id, so "keep the television current every night and leave the tablet alone" is
 * one map rather than one setting plus a list of exceptions to it. A pairing with no entry has never
 * been asked and is [LocalAutoSyncTiming.OFF]: a device does not start syncing by itself because it
 * was paired, it starts because the user said so, on the device's own row.
 *
 * Entries for devices that are no longer paired are kept on disk — re-pairing the same device should
 * not forget the answer — but [LocalAutoSyncStore] reads this map against the live pairing list, so
 * a forgotten device neither schedules anything nor takes part in a pass.
 */
data class LocalAutoSyncSettings(
    val byDevice: Map<String, LocalAutoSyncConfig> = emptyMap(),
) {
    /** One device's answer. Never asked means off, not "everything, nightly". */
    operator fun get(deviceId: String): LocalAutoSyncConfig =
        byDevice[deviceId] ?: LocalAutoSyncConfig()

    /**
     * The shortest timer any device asks for, or null when nothing runs on a timer.
     *
     * The schedule is one work, not one per device: WorkManager is happy to wake up and find
     * nothing due, and a handful of separate periodic jobs would each need cancelling on their own
     * when the device that asked for them changed its mind. Waking at the shortest interval and
     * letting [dueAt] skip the devices whose own interval has not elapsed gives every device exactly
     * its own schedule from one timer.
     */
    val periodicIntervalMs: Long?
        get() = byDevice.values.mapNotNull { it.timing.intervalMs }.minOrNull()

    /** True when at least one device asked to run at the first opportunity. */
    val hasStartup: Boolean
        get() = byDevice.values.any { it.timing == LocalAutoSyncTiming.STARTUP }

    /**
     * The devices whose own timer has elapsed by [now].
     *
     * A device that has never run is due as soon as its interval is: "daily" set an hour ago means
     * a run tomorrow, not a run now, because nobody asked for now.
     */
    fun dueAt(now: Long): Map<String, LocalAutoSyncConfig> = byDevice.filterValues { config ->
        val interval = config.timing.intervalMs ?: return@filterValues false
        config.lastRunAt + interval <= now
    }

    /** The devices that asked to run at the first opportunity, whoever that turns out to be. */
    fun startups(): Map<String, LocalAutoSyncConfig> =
        byDevice.filterValues { it.timing == LocalAutoSyncTiming.STARTUP }

    /** Only the answers that belong to a device that is still paired. */
    fun only(deviceIds: Set<String>): LocalAutoSyncSettings =
        copy(byDevice = byDevice.filterKeys { it in deviceIds })
}
