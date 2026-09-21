package tv.own.owntv.core.recording

/**
 * Every decision a DASH recording makes that is arithmetic rather than I/O.
 *
 * The same split [RecordingRules] draws, and for the same reason: [RecordingEngine] does the reading
 * and writing, this says *which* segments to read and *when* to ask again, so all of it can be tested
 * without a network, a disk or a clock.
 */
object DashRecordingPlan {

    /**
     * Which Representations this recording is fixed to, chosen once and kept.
     *
     * **Fixed at the start, never followed.** A manifest may drop a Representation or add a new
     * quality mid-programme; switching to it would change the codec or the resolution partway through
     * the file, which is exactly what the mux at the end cannot absorb — the result is a recording
     * that stops playing at the switch. If the chosen Representation disappears, that is a failure
     * with a truthful reason, not a silent substitution.
     *
     * **Highest bitrate wins.** A recorder only moves bytes — it never decodes — so the weak box
     * recording the programme is no argument for recording it badly. The one thing that matters is
     * that it is still the same Representation in an hour's time.
     */
    fun selectTracks(manifest: DashManifest): DashTrackSelection? {
        val usable = manifest.representations
        if (usable.isEmpty()) return null

        // The fast path: one Representation already carrying audio and video, which needs no mux at
        // all and is recorded by straight concatenation, exactly as HLS is.
        val muxed = usable.filter { it.kind == DashTrackKind.MUXED }.maxByOrNull { it.bandwidthBps }
        if (muxed != null) return DashTrackSelection(video = null, audio = null, combined = muxed)

        val video = usable.filter { it.kind == DashTrackKind.VIDEO }.maxByOrNull { it.bandwidthBps }
        val audio = usable.filter { it.kind == DashTrackKind.AUDIO }.maxByOrNull { it.bandwidthBps }
        if (video != null || audio != null) {
            return DashTrackSelection(video = video, audio = audio, combined = null)
        }

        // Nothing declared what it was. A manifest with a single undeclared Representation is almost
        // always one self-contained stream, so it is recorded as one rather than refused.
        val unknown = usable.filter { it.kind == DashTrackKind.UNKNOWN }.maxByOrNull { it.bandwidthBps }
        return unknown?.let { DashTrackSelection(video = null, audio = null, combined = it) }
    }

    /**
     * Which segments of [representation] to fetch now, given the last one already written.
     *
     * **Segments are identified by number, never by URL.** Several providers sign each segment
     * individually, so a URL cached for one cycle is a 403 in the next — the cause behind the Live TV
     * black screen recorded in `owntv-live-403-signed-segments`, and the rule
     * [RecordingEngine.recordHls] already follows for HLS.
     *
     * @param lastNumber the last segment written, or −1 on the first cycle.
     */
    fun nextSegments(
        manifest: DashManifest,
        representation: DashRepresentation,
        lastNumber: Long,
        nowMs: Long,
    ): DashFetchPlan = when (val segments = representation.segments) {
        is DashSegments.Single ->
            // One self-contained file. Fetched once; asking again would append the whole thing twice.
            if (lastNumber >= 0) {
                DashFetchPlan(emptyList(), lastNumber)
            } else {
                DashFetchPlan(listOf(DashSegment(0L, null, segments.url, representation.segmentDurationMs)), 0L)
            }

        is DashSegments.Explicit -> explicitPlan(manifest, segments.segments, lastNumber)

        is DashSegments.Numbered -> numberedPlan(manifest, representation, segments, lastNumber, nowMs)
    }

    /**
     * How long to wait before re-reading the manifest.
     *
     * `minimumUpdatePeriod` is what the provider says, and is preferred when it says anything useful;
     * otherwise half a segment, so a segment is never missed because the poll landed just before it
     * was published. Clamped at both ends so a manifest that reports nonsense — `PT0S` is common —
     * cannot turn into a spin loop.
     */
    fun pollIntervalMs(manifest: DashManifest, representation: DashRepresentation): Long {
        val stated = manifest.minimumUpdatePeriodMs?.takeIf { it > 0L }
        val fromSegments = representation.segmentDurationMs / 2
        return (stated ?: fromSegments).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
    }

    /**
     * The manifest enumerates its segments, so the arithmetic is only "which of these are new".
     *
     * On the **first** cycle of a live stream only the newest segment is taken: the user asked to
     * record from now, and a `SegmentTimeline` can enumerate an hour of window. A **static** manifest
     * is a catch-up programme, where the whole list *is* the recording, so all of it is taken.
     */
    private fun explicitPlan(
        manifest: DashManifest,
        segments: List<DashSegment>,
        lastNumber: Long,
    ): DashFetchPlan {
        if (segments.isEmpty()) return DashFetchPlan(emptyList(), lastNumber)
        if (lastNumber < 0) {
            val take = if (manifest.dynamic) segments.takeLast(1) else segments
            return DashFetchPlan(take, take.last().number)
        }
        // The window has scrolled past us entirely — the provider no longer serves what comes next.
        // Take it from where it is now rather than asking for segments that are gone, the same rule
        // `recordHls` applies when the media sequence has jumped.
        val firstAvailable = segments.first().number
        val from = if (firstAvailable > lastNumber + 1) firstAvailable else lastNumber + 1
        val due = segments.filter { it.number >= from }
        return DashFetchPlan(due, due.lastOrNull()?.number ?: lastNumber)
    }

    /**
     * A `$Number$` template lists nothing, so which segment exists is a function of the wall clock.
     *
     * Segment `n` covers `availabilityStartTime + (n − startNumber) × duration`, and is complete — and
     * therefore fetchable — one duration after it begins. Without `availabilityStartTime` a live
     * stream has no zero point to count from, and the plan says so rather than guessing: a guess here
     * requests thousands of segments that do not exist.
     */
    private fun numberedPlan(
        manifest: DashManifest,
        representation: DashRepresentation,
        segments: DashSegments.Numbered,
        lastNumber: Long,
        nowMs: Long,
    ): DashFetchPlan {
        val duration = segments.durationMs.coerceAtLeast(1L)

        if (!manifest.dynamic) {
            // A static numbered template: the whole programme, counted off its own duration.
            val total = manifest.mediaPresentationDurationMs
                ?: return DashFetchPlan(emptyList(), lastNumber, unschedulable = true)
            val last = segments.startNumber + (total / duration) - 1
            val from = if (lastNumber < 0) segments.startNumber else lastNumber + 1
            return plan(representation, segments, from, last, lastNumber, biasNewest = false)
        }

        val start = manifest.availabilityStartTimeMs
            ?: return DashFetchPlan(emptyList(), lastNumber, unschedulable = true)
        val elapsed = nowMs - start
        if (elapsed < duration) return DashFetchPlan(emptyList(), lastNumber)
        // The newest segment that has finished publishing.
        val live = segments.startNumber + (elapsed / duration) - 1

        // How far back the provider still serves. Asking for anything older is a 404 per segment.
        val oldest = manifest.timeShiftBufferDepthMs
            ?.let { live - (it / duration) }
            ?.coerceAtLeast(segments.startNumber)
            ?: segments.startNumber

        // First cycle: start at the live edge. The window may be an hour deep, and backfilling it
        // would record an hour nobody asked for and hammer the provider to do it.
        val from = if (lastNumber < 0) live else maxOf(lastNumber + 1, oldest)
        return plan(representation, segments, from, live, lastNumber, biasNewest = true)
    }

    /**
     * [from]..[to] turned into segments, capped at one cycle's worth.
     *
     * **Which end the cap takes from is not a detail.** A live stream keeps the *newest*, so a
     * recorder coming back from a stall rejoins the edge instead of falling further and further
     * behind. A static window — catch-up — keeps the *oldest*, because those segments are the start
     * of the programme and dropping them would silently lose its opening minutes; nothing is expiring
     * behind us there, so the next cycle collects the rest.
     */
    private fun plan(
        representation: DashRepresentation,
        segments: DashSegments.Numbered,
        from: Long,
        to: Long,
        lastNumber: Long,
        biasNewest: Boolean,
    ): DashFetchPlan {
        if (to < from) return DashFetchPlan(emptyList(), lastNumber)
        // A long stall leaves a large gap. Catching all of it up in one cycle would burst dozens of
        // requests at a provider that has just been unreachable; the next cycle takes the rest.
        val first = if (biasNewest) maxOf(from, to - MAX_SEGMENTS_PER_CYCLE + 1) else from
        val last = if (biasNewest) to else minOf(to, from + MAX_SEGMENTS_PER_CYCLE - 1)
        val due = (first..last).map { number ->
            DashSegment(
                number = number,
                time = null,
                url = DashManifest.expandTemplate(segments.mediaTemplate, number = number),
                durationMs = representation.segmentDurationMs,
            )
        }
        return DashFetchPlan(due, due.last().number)
    }

    /** At most this many segments in one cycle, so catching up never becomes a burst. */
    internal const val MAX_SEGMENTS_PER_CYCLE = 24L

    private const val MIN_POLL_MS = 1_000L
    private const val MAX_POLL_MS = 10_000L
}

/**
 * The Representations one recording is fixed to.
 *
 * Either [combined] alone — one stream carrying everything, recorded by concatenation — or [video]
 * and [audio] as two separate streams that have to be muxed together at the end.
 */
data class DashTrackSelection(
    val video: DashRepresentation?,
    val audio: DashRepresentation?,
    val combined: DashRepresentation?,
) {
    /** Everything being fetched, one temp file each. */
    val tracks: List<DashRepresentation> get() = listOfNotNull(combined, video, audio)

    /** True when the two halves have to be put back together before the file will play. */
    val needsMux: Boolean get() = combined == null && video != null && audio != null
}

/** What to fetch this cycle, and what to remember afterwards. */
data class DashFetchPlan(
    val segments: List<DashSegment>,
    /** The value to carry into the next cycle as `lastNumber`. */
    val lastNumber: Long,
    /**
     * The manifest cannot be scheduled at all — a live `$Number$` template with no
     * `availabilityStartTime`, which has no zero point to count segments from. Said plainly rather
     * than guessed at, so the recording fails with a reason instead of requesting segments that were
     * never published.
     */
    val unschedulable: Boolean = false,
)
