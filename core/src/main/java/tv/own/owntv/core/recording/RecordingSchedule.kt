package tv.own.owntv.core.recording

import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/**
 * When a recording should actually run, and which ones get in each other's way.
 *
 * Arithmetic only — no `AlarmManager`, no database, no clock of its own — so every rule here can be
 * checked in a unit test. [RecordingScheduler] does the Android part.
 */
object RecordingSchedule {

    /** Minutes before the programme starts, and after it ends, by default. */
    const val DEFAULT_PRE_ROLL_MINUTES = 1
    const val DEFAULT_POST_ROLL_MINUTES = 3

    /** The most either padding may be set to. Beyond this a padding is a second recording. */
    const val MAX_ROLL_MINUTES = 30

    /**
     * The extra head start taken when exact alarms are not available.
     *
     * Without `SCHEDULE_EXACT_ALARM` the alarm is inexact and Android may run it minutes late — and
     * late is the one direction a recording cannot afford, because the missed minutes are the
     * beginning of the programme. Starting early costs disk space; starting late costs the opening.
     */
    const val INEXACT_HEAD_START_MINUTES = 3

    /**
     * The window to record, from the programme's own guide times.
     *
     * Both paddings exist because EPG times drift: providers round to five minutes, programmes
     * overrun, and a guide that is ninety seconds out is entirely ordinary. The padding is what turns
     * "the guide said 21:00" into "the programme is on the recording".
     */
    fun windowFor(
        programmeStartMs: Long,
        programmeStopMs: Long,
        preRollMinutes: Int = DEFAULT_PRE_ROLL_MINUTES,
        postRollMinutes: Int = DEFAULT_POST_ROLL_MINUTES,
        exactAlarms: Boolean = true,
    ): LongRange {
        val head = preRollMinutes.coerceIn(0, MAX_ROLL_MINUTES) +
            if (exactAlarms) 0 else INEXACT_HEAD_START_MINUTES
        val tail = postRollMinutes.coerceIn(0, MAX_ROLL_MINUTES)
        val start = programmeStartMs - head * MINUTE_MS
        // A guide that lists a stop at or before its start is not unheard of. One minute of recording
        // is a truthful "we tried"; a negative window would never run at all.
        val stop = maxOf(programmeStopMs + tail * MINUTE_MS, start + MINUTE_MS)
        return start..stop
    }

    /**
     * Do these two windows contend for the same connection? The usual half-open test: one starts
     * before the other ends, and ends after it starts. Touching at a single instant is not a clash —
     * the 21:00 programme's recording starting exactly as the 20:00 one's stops is the normal case.
     */
    fun overlaps(aStart: Long, aStop: Long, bStart: Long, bStop: Long): Boolean =
        aStart < bStop && aStop > bStart

    /**
     * Which of [candidates] would contend with a recording over [startMs]..[stopMs] on the same
     * playlist. Shown to the user **before** they commit to anything, because a live programme
     * cannot wait its turn: "start when the other finishes" means "start half-way through" (D10).
     */
    fun clashesAmong(
        candidates: List<RecordingEntity>,
        sourceId: Long,
        startMs: Long,
        stopMs: Long,
        excludeId: Long = 0,
    ): List<RecordingEntity> = candidates.filter { other ->
        other.id != excludeId &&
            other.sourceId == sourceId &&
            (other.status == RecordingStatus.SCHEDULED || other.status == RecordingStatus.RECORDING) &&
            overlaps(startMs, stopMs, other.startMs, other.stopMs)
    }

    /**
     * Is this a **catch-up** recording — a programme that has already aired, being pulled from the
     * provider's archive now — rather than a live one waiting for its moment?
     *
     * Derived rather than stored, and the derivation is true by construction: a catch-up recording's
     * window begins *after* the programme it captures has ended, which can never be the case for a
     * live one. That is why [RecordingEntity] keeps two windows.
     *
     * It matters to the recorder because a finite source **ends**. An archive stream returns
     * end-of-body when it has served the whole programme, and a live stream never does — so the same
     * end-of-body is "finished" for one and "the provider dropped us, reconnect" for the other.
     */
    fun isCatchUp(recording: RecordingEntity): Boolean = recording.programmeStopMs <= recording.startMs

    /**
     * The window for recording a past programme from the archive: starting **now**, and running for
     * as long as the programme itself did.
     *
     * No pre-roll and no post-roll. Those exist to absorb EPG drift at the live edge; an archive
     * request already names the exact times it wants, and padding here would only record the end of
     * the previous programme.
     */
    fun catchUpWindowFor(programmeStartMs: Long, programmeStopMs: Long, now: Long): LongRange {
        val duration = (programmeStopMs - programmeStartMs).coerceAtLeast(MINUTE_MS)
        // A slack tail so a server that streams the archive slower than real time is not cut off
        // mid-programme. The recording ends on `#EXT-X-ENDLIST` or end-of-body long before this.
        return now..(now + duration + ARCHIVE_SLACK_MS)
    }

    /**
     * Can this programme still be fetched from [catchupDays] of archive? A provider that keeps seven
     * days has nothing to offer for last month, and a programme that has not finished airing is not
     * in the archive yet.
     */
    fun isWithinArchive(programmeStopMs: Long, catchupDays: Int, now: Long): Boolean {
        if (programmeStopMs > now) return false
        val days = (if (catchupDays > 0) catchupDays else DEFAULT_ARCHIVE_DAYS).coerceAtMost(MAX_ARCHIVE_DAYS)
        return programmeStopMs >= now - days * DAY_MS
    }

    /**
     * A scheduled recording whose window has closed without it ever running — the app was off, or
     * the device was. There is nothing to retry, so it becomes MISSED with a reason rather than
     * sitting in the list forever claiming it is about to start.
     */
    fun hasBeenMissed(recording: RecordingEntity, now: Long): Boolean =
        recording.status == RecordingStatus.SCHEDULED && now >= recording.stopMs

    /**
     * When to wake up for this recording — its start, or right now if it is already overdue but its
     * window is still open. Null when there is nothing left to wake up for.
     */
    fun wakeAtFor(recording: RecordingEntity, now: Long): Long? = when {
        recording.status != RecordingStatus.SCHEDULED -> null
        now >= recording.stopMs -> null
        else -> maxOf(recording.startMs, now)
    }

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * MINUTE_MS

    /**
     * Extra time allowed on an archive fetch beyond the programme's own length. The recording
     * normally ends well inside it, on `#EXT-X-ENDLIST` or end-of-body; this only protects a server
     * that serves the archive slower than real time.
     */
    private const val ARCHIVE_SLACK_MS = 10 * MINUTE_MS

    /** What to assume when a playlist claims catch-up but never says how deep it goes. */
    private const val DEFAULT_ARCHIVE_DAYS = 7

    /** A `catchup-days` from a playlist is not to be trusted; a month is already more than anyone keeps. */
    private const val MAX_ARCHIVE_DAYS = 31
}
