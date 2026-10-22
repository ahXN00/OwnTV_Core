package tv.own.owntv.core.sync.local

/**
 * What came of one automatic pass, device by device.
 *
 * A pass that touches three devices and reaches one of them is not a failure and not a success: it
 * is one device that was not there. Saying so is the difference between a user who leaves
 * auto-sync on because it works and one who turns it off after a week of a screen that only ever
 * said "failed".
 */
data class LocalAutoSyncReport(
    val devices: List<LocalAutoSyncDeviceReport>,
    val startedAt: Long,
    val finishedAt: Long,
) {
    /** The devices that actually moved something. */
    val synced: List<LocalAutoSyncDeviceReport>
        get() = devices.filter { it.outcome == LocalAutoSyncOutcome.SYNCED }

    /** True when at least one device was reached and synced. */
    val didAnything: Boolean
        get() = synced.isNotEmpty()
}

/** One device's share of a pass. */
data class LocalAutoSyncDeviceReport(
    val deviceId: String,
    val name: String,
    val outcome: LocalAutoSyncOutcome,
    /** How many rows landed here. Zero for a send, and for a device that was not there. */
    val items: Int = 0,
)

/** How one device's share of a pass ended. */
enum class LocalAutoSyncOutcome {
    /** It answered and the data was applied. */
    SYNCED,

    /** Nothing answered at that address — the other device is off, or on another network. */
    UNREACHABLE,

    /** It answered, but did not recognise the pairing any more. */
    NOT_AUTHORIZED,

    /** It answered with something that was not an OwnTV backup. */
    BAD_PAYLOAD,

    /** Anything else. */
    UNKNOWN,
}
