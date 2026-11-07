package tv.own.owntv.core.sync.local

/** How one automatic pass with one device ended. */
enum class AutoSyncOutcome {
    /** Both halves went through. */
    SYNCED,

    /** A pass with that device was already running (here or there). Not a failure: nothing is counted. */
    SKIPPED,

    /** Nothing answered at its last address, and it was not found on the network. Counted, backs off. */
    UNREACHABLE,

    /** It answered that it has no agreement any more: turned off there. The agreement here ends too. */
    TURNED_OFF,

    /** It no longer knows this device (unpaired there, or paired again). The pairing here is removed. */
    UNPAIRED,

    /** Anything else. Counted, backs off. */
    FAILED,
}

/** One pass's result, for the row's status line. [received] is how many rows landed here. */
data class AutoSyncEvent(val deviceId: String, val outcome: AutoSyncOutcome, val at: Long, val received: Int = 0)
