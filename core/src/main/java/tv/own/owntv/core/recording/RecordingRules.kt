package tv.own.owntv.core.recording

import tv.own.owntv.core.R
import tv.own.owntv.core.live.StreamRefusal
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.storage.StorageAccess
import java.util.Locale

/**
 * Every decision the recorder makes that is arithmetic rather than I/O, kept apart from it so each
 * one can be tested without a network, a disk or a clock.
 *
 * [RecordingEngine] does the reading and writing; this says what the reading and writing *mean*.
 */
object RecordingRules {

    /**
     * The floor free space may not fall through, 500 MB (D8).
     *
     * Not zero, and never an eviction: a recording that runs out of room **stops and says so**,
     * keeping everything it captured, and it never deletes a download or another recording to make
     * space (D2). Leaving half a gigabyte behind is what stops a full volume from taking the rest of
     * the device down with it.
     */
    const val RESERVE_BYTES = 500L * 1024L * 1024L

    /** HTTP 458 — "your account's session is already in use", the same code the player treats as BUSY. */
    const val SESSION_LIMIT_CODE = 458

    /** True while there is still room to write. [freeBytes] is the target volume's usable space. */
    fun hasSpace(freeBytes: Long): Boolean = freeBytes > RESERVE_BYTES

    /**
     * **The stop condition.** A live stream never returns end-of-body, so this — and nothing else —
     * is what ends a recording: the wall clock reaching the window's end.
     */
    fun shouldStop(now: Long, stopMs: Long): Boolean = now >= stopMs

    /**
     * Whether a recording has run so far past its stop time that it must be cut off rather than
     * asked to stop politely.
     *
     * [shouldStop] is checked between reads, so a socket that has gone quiet without closing would
     * otherwise hold a recording open forever — the read blocks and the clock is never consulted
     * again. This is the backstop the drain loop uses, and it is why the stop is reliable rather
     * than merely likely.
     */
    fun isOverrunning(now: Long, stopMs: Long): Boolean = now >= stopMs + OVERRUN_GRACE_MS

    /**
     * What a recording that could not start should be recorded as.
     *
     * Always [RecordingStatus.MISSED], never FAILED: nothing was written, the programme has gone,
     * and the difference matters to the user — FAILED invites a retry that cannot work.
     */
    fun missedBecause(reason: StreamRefusal): RecordingFailure = when (reason) {
        // Both mean "the provider had nothing left to give", and they read differently only because
        // one-connection accounts are the common case and deserve their own sentence.
        StreamRefusal.SINGLE_CONNECTION -> RecordingFailure.NO_CONNECTION
        StreamRefusal.ALL_IN_USE -> RecordingFailure.CLASH
        StreamRefusal.RESERVED_FOR_WATCHING -> RecordingFailure.NO_CONNECTION
    }

    /**
     * How a recording that actually ran should be closed out.
     *
     * **Any bytes at all is a success.** A `.ts` file is playable to whatever point it reached, so
     * ten minutes of a programme that dropped is a recording the user can watch, not a failure — and
     * marking it FAILED would hide a file that is on disk. The one exception is running out of room,
     * which stays a failure however much was captured, because the user has to know why it is short.
     *
     * Nothing written at all is a failure whatever the reason: there is no file to offer.
     */
    fun outcomeOf(bytes: Long, failure: RecordingFailure): Pair<RecordingStatus, RecordingFailure> = when {
        failure == RecordingFailure.NO_SPACE -> RecordingStatus.FAILED to RecordingFailure.NO_SPACE
        bytes > 0 -> RecordingStatus.COMPLETED to RecordingFailure.NONE
        failure != RecordingFailure.NONE -> RecordingStatus.FAILED to failure
        else -> RecordingStatus.FAILED to RecordingFailure.STREAM_UNAVAILABLE
    }

    /**
     * How long to wait before reconnecting after attempt [attempt], capped so a channel that is down
     * for an hour is still retried every half minute rather than once more at bedtime.
     */
    fun retryDelayMs(attempt: Int): Long =
        (RETRY_BASE_MS * attempt).coerceAtMost(RETRY_MAX_MS)

    /**
     * The file one recording is written to, relative to the `TV/` folder:
     * `BBC One - The Nine O'Clock News - 2026-09-12 21-00.ts`.
     *
     * The channel and the date are in the name because a file manager sorts by name and a user
     * looking for last Tuesday's news has nothing else to go on — and because two showings of the
     * same programme must not overwrite each other. `.ts` and not `.mp4` on purpose: a `.ts` is
     * playable while it is still being written and survives being cut off, which is exactly what an
     * interrupted recording is.
     */
    fun fileName(channelName: String, title: String, startMs: Long): String {
        val stamp = java.text.SimpleDateFormat(STAMP_PATTERN, Locale.US).format(java.util.Date(startMs))
        val stem = StorageAccess.sanitize(
            listOf(channelName, title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "recording" },
        )
        return "$stem - $stamp.ts"
    }

    /**
     * The sentence shown on a recording that failed or was missed, or null when there is nothing to
     * explain. Lives in core because both apps show the same list and a reason worded two ways is a
     * reason the user cannot compare.
     */
    fun displayTextOf(failure: RecordingFailure, res: android.content.res.Resources): String? = when (failure) {
        RecordingFailure.NONE -> null
        RecordingFailure.NO_SPACE -> res.getString(R.string.recording_failed_no_space)
        RecordingFailure.NO_CONNECTION -> res.getString(R.string.recording_failed_no_connection)
        RecordingFailure.CLASH -> res.getString(R.string.recording_failed_clash)
        RecordingFailure.NETWORK -> res.getString(R.string.recording_failed_network)
        RecordingFailure.STREAM_UNAVAILABLE -> res.getString(R.string.recording_failed_unavailable)
        RecordingFailure.CHANNEL_GONE -> res.getString(R.string.recording_failed_channel_gone)
        RecordingFailure.ENCRYPTED -> res.getString(R.string.recording_failed_encrypted)
        RecordingFailure.UNKNOWN -> res.getString(R.string.recording_failed_unknown)
    }

    private const val RETRY_BASE_MS = 3_000L
    private const val RETRY_MAX_MS = 30_000L
    private const val STAMP_PATTERN = "yyyy-MM-dd HH-mm"

    /** How far past its stop time a recording may run before it is cancelled outright. */
    private const val OVERRUN_GRACE_MS = 15_000L
}
