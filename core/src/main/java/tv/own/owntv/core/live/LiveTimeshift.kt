package tv.own.owntv.core.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Live rewind / timeshift: watching a catch-up-capable channel some seconds behind the live edge,
 * out of the provider's rolling archive, and the "watching" clock that says which wall-clock instant
 * the picture actually belongs to.
 *
 * Split out of `LiveViewModel`, which was doing four jobs at once. The seam is deliberately narrow —
 * **offset in, archive load out**: this class owns how far back the user is, when to (re)load, and the
 * two counters that tick while an archive plays. It owns nothing about *how* a stream is opened: the
 * URL construction, the engine handover and `player.play` stay with the view model and arrive here as
 * [loadArchive]. The ladder and its outcome watcher are untouched by design.
 *
 * Everything time-related is injected ([nowMs], [coalesceMs], [tickMs]) and playback is read through
 * [Playback], so the sequencing is unit-testable without a player, a database or a view model.
 */
class LiveTimeshift(
    private val scope: CoroutineScope,
    private val playback: Playback,
    /** Open [ChannelEntity]'s archive starting at the given wall-clock instant. Returns false when the
     *  URL could not be built, or the user returned to live while it was being resolved. A false ends
     *  the rewind — see [scheduleLoad]. */
    private val loadArchive: suspend (ChannelEntity, Long, Int) -> Boolean,
    /** Scrubbing forward reached the live edge — or an archive could not be opened at all: the caller
     *  puts the real-time stream back on screen. */
    private val onLiveEdge: () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val coalesceMs: Long = 350,
    private val tickMs: Long = 1_000,
    /** N4 — the channel's own saved copy, for a channel with no provider archive; null where there is none. */
    private val local: Local? = null,
) {

    /**
     * N4 — a copy of the channel saved on this device while it is watched (see
     * `tv.own.owntv.player.LiveTuneController`), rewound instead of the provider's archive when the
     * channel has none. Everything here is the copy of the channel on screen, so the answers are null
     * for any other channel and whenever there is no copy.
     */
    interface Local {
        /** The length of rewind the copy is kept to — the rewind bar's length — or null without a copy. */
        fun windowSec(ch: ChannelEntity): Int?
        /** How far back the copy reaches right now, in seconds. */
        fun depthSec(): Int
        /** The wall-clock instant on screen. Null when nothing is playing from the copy. */
        fun watchingWallMs(): Long?
        /** The newest instant the copy holds — "live" for this channel. */
        fun liveEdgeWallMs(): Long?
        /** Show the copy from [wallMs], or from its live edge when null. */
        fun seek(wallMs: Long?)
    }

    /** The three things the counters need from whatever is playing. */
    interface Playback {
        val positionMs: Long
        val hasError: Boolean
        val hasActiveStream: Boolean
    }

    private val _offsetSec = MutableStateFlow<Int?>(null) // null = at the live edge; >0 = N s behind
    /** How far behind live the picture is, in seconds. Null while the live edge is on screen. */
    val offsetSec: StateFlow<Int?> = _offsetSec.asStateFlow()

    private val _watchingWallMs = MutableStateFlow<Long?>(null)
    /** The wall-clock instant on screen, for the HUD's second clock. Null when the picture IS the
     *  present (live edge, or a movie/episode). */
    val watchingWallMs: StateFlow<Long?> = _watchingWallMs.asStateFlow()

    /** True while the user is rewound — the guard that stops an engine swap re-tuning at the edge. */
    val isRewound: Boolean get() = _offsetSec.value != null

    private var loadJob: Job? = null
    private var tickJob: Job? = null

    /** Wall-clock instant the loaded archive starts at. Both counters are derived from it plus the
     *  playback position, so one field serves the rewind and the guide catch-up paths alike. */
    private var archiveBaseWall: Long? = null

    /** Whether [ch] can be rewound at all: a provider archive, or its own saved copy (N4). */
    fun canRewind(ch: ChannelEntity): Boolean = ch.catchup || local?.windowSec(ch) != null

    /** How deep [ch]'s archive is, in seconds — the bound every jump and scrub is clamped to. A saved
     *  copy says how long it is kept to, so the rewind bar has a steady length while the copy fills. */
    fun windowSec(ch: ChannelEntity): Int = if (!ch.catchup) local?.windowSec(ch) ?: 0 else
        // Capped: `catchup-days` comes from the playlist, and a silly value there overflowed the
        // seconds to a NEGATIVE window, which made beginAt's coerceIn(1, window) an empty range —
        // an IllegalArgumentException in the middle of tuning. A month of archive is already more
        // than any provider keeps.
        (ch.catchupDays.takeIf { it > 0 } ?: DEFAULT_CATCHUP_DAYS).coerceAtMost(MAX_CATCHUP_DAYS) * 24 * 3600

    /** Offsets worth offering for [ch], nearest first. Empty when the channel has no archive. */
    fun jumpOptions(ch: ChannelEntity): List<Int> =
        if (!canRewind(ch)) emptyList() else CatchupJumps.optionsFor(windowSec(ch))

    /**
     * Put [ch] at [offsetSec] behind live (absolute, not relative — this is aiming, so a second pick
     * from the "Go back to…" list must not stack on top of the first).
     */
    fun beginAt(ch: ChannelEntity, offsetSec: Int) {
        if (!canRewind(ch)) return
        val off = offsetSec.coerceIn(1, reachSec(ch).coerceAtLeast(1))
        _offsetSec.value = off
        scheduleLoad(ch, off)
    }

    /**
     * Move [deltaSec] further back (+) or toward live (−) — the rewind/forward buttons and the timeline
     * scrubber. Coalesced, so holding a key scrubs freely and loads the archive once at the final point;
     * reaching the live edge hands back to [onLiveEdge].
     */
    fun scrub(ch: ChannelEntity, deltaSec: Int) {
        if (!canRewind(ch)) return
        val next = ((_offsetSec.value ?: 0) + deltaSec).coerceIn(0, reachSec(ch))
        if (next == 0) { onLiveEdge(); return }
        _offsetSec.value = next
        scheduleLoad(ch, next)
    }

    /**
     * Follow an archive that someone else already started — the guide catch-up path, which plays one
     * fixed programme rather than a rewind. The "behind live" offset stays null (a programme replay
     * keeps VOD chrome, not the live rewind chrome); only the watching clock runs.
     */
    fun followArchiveFrom(baseWall: Long) {
        loadJob?.cancel() // a fixed programme supersedes any rewind load still coalescing
        _offsetSec.value = null
        archiveBaseWall = baseWall
        startTick(rewinding = false)
    }

    /**
     * Drop every trace of a live rewind: the pending archive load, the counters and the offset the HUD
     * reads from.
     *
     * Anything that puts the channel back on a real-time stream has to call this. The rewind lives in
     * its own state, apart from tune state, so an engine change (compatibility mode, a ladder fallback)
     * would otherwise throw the user back to the live edge while the counter kept ticking upward
     * against a stream that was no longer the archive.
     */
    fun clear() {
        loadJob?.cancel()
        tickJob?.cancel()
        followingLocal = false
        _offsetSec.value = null
        archiveBaseWall = null
        _watchingWallMs.value = null
    }

    /** How far back a jump or scrub may go: the archive's depth, or what the saved copy holds now. */
    private fun reachSec(ch: ChannelEntity): Int =
        if (ch.catchup) windowSec(ch) else local?.takeIf { it.windowSec(ch) != null }?.depthSec() ?: 0

    private fun scheduleLoad(ch: ChannelEntity, offsetSec: Int) {
        loadJob?.cancel()
        tickJob?.cancel()
        if (!ch.catchup && local != null) {
            // The saved copy: re-open it at the aimed instant. The follower below keeps the counters.
            loadJob = scope.launch {
                delay(coalesceMs)
                val edge = local.liveEdgeWallMs() ?: nowMs()
                localSettleUntil = nowMs() + LOCAL_SETTLE_MS
                local.seek(edge - offsetSec * 1000L)
            }
            return
        }
        loadJob = scope.launch {
            delay(coalesceMs) // coalesce rapid rewind/forward presses into one archive load
            val startMs = nowMs() - offsetSec * 1000L
            if (!loadArchive(ch, startMs, offsetSec)) {
                // No archive opened — the provider has no recording, or the URL could not be built.
                // The offset used to survive that, so the HUD went on claiming the viewer was ten
                // minutes behind live over a picture that had never left the live edge, and the engine
                // toggle stayed hidden behind [isRewound] for a rewind that did not exist. Hand back to
                // live instead: it is the one state that is honest whichever way the rewind was entered
                // — including from the browse list, where the channel was never tuned at all.
                //
                // Skipped when the offset is already null, which is the other reason for a false: the
                // user reached the live edge while this load was being resolved, and re-tuning would
                // interrupt the stream they are already watching.
                if (_offsetSec.value != null) {
                    _offsetSec.value = null
                    onLiveEdge()
                }
                return@launch
            }
            archiveBaseWall = startMs
            startTick(rewinding = true)
        }
    }

    /**
     * The one 1 Hz ticker (P-F2). There used to be two — one for the rewind counter, one for the
     * watching clock — and they were not exclusive: jumping into a rewind from a catch-up programme
     * left the clock's loop running alongside the new one, both writing the same value every second.
     *
     * Both counters read the same two inputs (the archive's start instant and the playback position),
     * so one loop serves both. [rewinding] is the only difference and it is small: a rewind also
     * recomputes "behind live", and treats a failed archive as the end of the rewind rather than just
     * the end of the clock.
     */
    private fun startTick(rewinding: Boolean) {
        tickJob?.cancel()
        tickJob = scope.launch {
            // The catch-up path paints its clock straight away; the rewind path already has an offset
            // on screen from the caller, and its first honest reading needs a position to exist.
            if (!rewinding) emitWatching()
            while (true) {
                delay(tickMs)
                if (archiveBaseWall == null) break
                if (rewinding && _offsetSec.value == null) break
                // The archive failed. For a rewind that ends the rewind outright: leaving the offset set
                // kept the rewind UI (and its "behind live" figure) alive over an error screen.
                if (playback.hasError) {
                    if (rewinding) clear() else _watchingWallMs.value = null
                    break
                }
                // The stream is gone entirely (player stopped, another item took over). Stop ticking, but
                // a rewind keeps its offset — a reload in flight still counts as the same rewind.
                if (!playback.hasActiveStream) {
                    if (!rewinding) _watchingWallMs.value = null
                    break
                }
                if (rewinding) {
                    // offset = realNow − watched time = realNow − (archive start + playback position).
                    // Pausing makes it grow: you fall further behind.
                    val base = archiveBaseWall ?: break
                    val behindSec = (nowMs() - (base + playback.positionMs)) / 1000
                    _offsetSec.value = behindSec.toInt().coerceAtLeast(0)
                }
                emitWatching()
            }
        }
    }

    private fun emitWatching() {
        _watchingWallMs.value = archiveBaseWall?.let { it + playback.positionMs }
    }

    // --- The saved copy (N4) -----------------------------------------------------------------------

    /** Until then a re-opened copy is still starting, so its position says nothing yet. */
    private var localSettleUntil = 0L
    private var followingLocal = false

    init {
        if (local != null) scope.launch { followLocal(local) }
    }

    /**
     * How far behind its own newest picture the copy on screen is — read once a second. Nothing has to
     * tell this that the user paused: a paused picture simply falls behind, and the HUD's rewind chrome
     * appears with the right figure, exactly as after a rewind. Back within [LOCAL_LIVE_SLACK_SEC] of the
     * edge is live again. Measured against the copy's edge rather than the clock, so the few seconds a
     * saved piece takes to arrive never read as "behind".
     */
    private suspend fun followLocal(l: Local) {
        while (true) {
            delay(tickMs)
            if (archiveBaseWall != null) continue // the provider's archive is in charge
            val watching = l.watchingWallMs()
            val edge = l.liveEdgeWallMs()
            if (watching == null || edge == null) {
                if (followingLocal) {
                    followingLocal = false
                    _offsetSec.value = null
                    _watchingWallMs.value = null
                }
                continue
            }
            if (nowMs() < localSettleUntil) continue
            val behind = ((edge - watching) / 1000).toInt()
            if (behind > LOCAL_LIVE_SLACK_SEC) {
                followingLocal = true
                _offsetSec.value = behind
                _watchingWallMs.value = watching
            } else if (followingLocal) {
                followingLocal = false
                _offsetSec.value = null
                _watchingWallMs.value = null
            }
        }
    }

    private companion object {
        /** Behind the copy's edge by no more than this is "live" (a player keeps a few seconds buffered). */
        const val LOCAL_LIVE_SLACK_SEC = 8
        const val LOCAL_SETTLE_MS = 3_000L
    }
}
