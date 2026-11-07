package tv.own.owntv.core.categorylock

/** What one PIN entry came to. */
sealed interface PinAttempt {
    data object Accepted : PinAttempt
    data object Wrong : PinAttempt
    /** Refused without checking: too many wrong entries in a row. */
    data class Wait(val seconds: Int) : PinAttempt
}

/**
 * Slows down guessing a PIN: after [maxFailures] wrong entries in a row every entry is
 * refused, unchecked, for [lockoutMs]. A right entry clears the count. Memory only, so the count is gone
 * when the app process is; [clock] is a monotonic millisecond clock, never the wall clock the user can set.
 */
class PinAttemptLimiter(
    private val clock: () -> Long,
    private val maxFailures: Int = 5,
    private val lockoutMs: Long = 30_000L,
) {
    private var failures = 0
    private var blockedUntil = 0L

    @Synchronized
    fun attempt(verify: () -> Boolean): PinAttempt {
        waitSeconds().takeIf { it > 0 }?.let { return PinAttempt.Wait(it) }
        if (verify()) {
            failures = 0
            return PinAttempt.Accepted
        }
        failures++
        if (failures < maxFailures) return PinAttempt.Wrong
        failures = 0
        blockedUntil = clock() + lockoutMs
        return PinAttempt.Wait(waitSeconds())
    }

    /** Whole seconds left before an entry is checked again, rounded up; 0 when entries are open. */
    @Synchronized
    fun waitSeconds(): Int {
        val left = blockedUntil - clock()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }
}

/** The doors a PIN opens. Each has its own PIN and its own count of wrong entries. */
enum class PinDoor { PROFILE, CATEGORY, SETTINGS }

/**
 * One [PinAttemptLimiter] per [PinDoor]: guessing at one door never makes another wait, so a child
 * trying Settings PINs cannot lock the parent out of a category, and the reverse.
 */
class PinAttemptLimiters(clock: () -> Long) {
    private val limiters = PinDoor.entries.associateWith { PinAttemptLimiter(clock) }

    fun of(door: PinDoor): PinAttemptLimiter = limiters.getValue(door)
}
