package tv.own.owntv.core.recording

import android.content.Context
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.database.dao.RecordingDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The byte pump behind live recording: [tv.own.owntv.core.download.DownloadEngine]'s shape, with the
 * four differences a live stream forces (§1.3).
 *
 * 1. **A time-based stop.** A live `.ts` URL never returns end-of-body, so the recording ends at
 *    `stopMs` and nowhere else.
 * 2. **No `Range` resume.** Resuming a live stream at a byte offset is meaningless. A dropped
 *    connection re-requests from *now* and **appends**, leaving a gap in the file. The gap is honest
 *    and unavoidable, and a `.ts` survives it.
 * 3. **Its own queue.** Recordings must not be serialised behind a 6 GB film — `DownloadEngine`
 *    drains strictly one at a time, deliberately, and a film would eat the nine o'clock news.
 * 4. **Concurrency bounded by the provider, not by us** (D10). Several recordings run at once, up to
 *    what the playlist's `maxConnections` allows, with one stream kept back so the user can still
 *    watch — unless they have said otherwise.
 *
 * [RecordingDao] is the single source of truth, exactly as the download queue is: the engine holds no
 * queue of its own, it drains whatever rows are due.
 */
class RecordingEngine(
    /** Only to resolve a stored `filePath` into something writable — a document needs a resolver. */
    private val context: Context,
    private val recordingDao: RecordingDao,
    /** Only to read the channel's declared DRM — see the refusal at the top of [attemptRecord]. */
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val streams: OpenStreamRegistry,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val activityTracker: RecordingActivityTracker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Recordings currently running, so one can be stopped precisely without touching the others. */
    private val active = ConcurrentHashMap<Long, Job>()

    /** Ids the user has just stopped or deleted; the drain loop steps over them. */
    private val suppressed = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /**
     * The DASH state of each running recording, kept **across reconnects**.
     *
     * A DASH recording fixes on its Representations at the start and must keep them: a second attempt
     * that re-chose would append a different quality to the same file, which is exactly the
     * mid-stream codec change the mux cannot absorb. Removed, and its temp files dealt with, by
     * [finishDashSession] when the recording ends however it ends.
     */
    private val dashSessions = ConcurrentHashMap<Long, DashSession>()

    @Volatile
    private var queueDirty = false

    fun markQueued() {
        queueDirty = true
    }

    /** True while anything is being written — what the worker checks before it lets go. */
    val isRecording: Boolean get() = active.isNotEmpty()

    /**
     * Start everything that is due, keep it running, and return when nothing is recording and
     * nothing is due.
     *
     * Unlike the download drain this does **not** run one at a time: each due row gets its own child
     * job, and the loop then polls so a recording that becomes due while another is still running is
     * picked up without waiting for it.
     */
    suspend fun drainQueue(onProgress: (RecordingProgress) -> Unit) = coroutineScope {
        val report: (RecordingProgress) -> Unit = { activityTracker.progress(it); onProgress(it) }
        // Before anything starts, so it can never race a live recording for the same temp files.
        recoverInterruptedDashRecordings()
        while (currentCoroutineContext().isActive) {
            queueDirty = false
            val now = clock()
            val reserve = settings.recordingReserveConnection()
            // Checked once per pass, not per row: it is one system call and every row this pass gets
            // the same answer.
            val meteredRefused = connectivity.isMeteredNow() && !settings.recordingOverMobileData()
            for (row in recordingDao.dueAt(now)) {
                if (active.containsKey(row.id) || row.id in suppressed) continue
                // A blank stream URL means "somebody else is writing this file": a
                // "record what I'm watching" row, whose bytes come from the player's already-open
                // stream (D3, mode b). Touching it would open a second connection, which is the one
                // thing that mode exists to avoid.
                if (row.streamUrl.isBlank()) continue
                // Said as a MISSED row with a reason rather than by deferring the work. A download
                // waits for Wi-Fi because the film is there tomorrow; a live programme is not.
                if (meteredRefused) {
                    markMissed(row, RecordingFailure.METERED_CONNECTION)
                    continue
                }
                when (val grant = grantFor(row, reserve)) {
                    is StreamGrant.Refused -> markMissed(row, RecordingRules.missedBecause(grant.reason))
                    StreamGrant.Allowed -> start(row, report)
                }
            }
            // A recording whose window has closed while its read was blocked: the pump checks the
            // clock itself, but a socket that never delivers another byte would keep the job alive
            // past its stop time. This is the backstop, and it is why the stop is reliable.
            active.forEach { (id, job) ->
                val row = recordingDao.getById(id)
                if (row == null || RecordingRules.isOverrunning(clock(), row.stopMs)) job.cancel()
            }
            if (active.isEmpty()) {
                if (queueDirty) continue else return@coroutineScope
            }
            delay(POLL_MS)
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.start(row: RecordingEntity, report: (RecordingProgress) -> Unit) {
        val claim = streams.claim(row.sourceId, StreamPurpose.RECORDING)
        // LAZY so the job is in the map before it can finish and try to remove itself.
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                runRecording(row.id, report)
            } finally {
                streams.release(claim)
                activityTracker.finished(row.id)
            }
        }
        active[row.id] = job
        job.invokeOnCompletion { active.remove(row.id, job) }
        job.start()
    }

    /** May this recording have one of the playlist's connections right now? (D10/D11.) */
    private suspend fun grantFor(row: RecordingEntity, reserveOneForWatching: Boolean): StreamGrant =
        connectionBudget(
            source = sourceDao.getById(row.sourceId),
            open = streams.openOn(row.sourceId),
            purpose = StreamPurpose.RECORDING,
            reserveOneForWatching = reserveOneForWatching,
        )

    /**
     * Stop the recording of [id] and wait for it to let go of the file, keeping the drain loop off it
     * until [release] is called — the same handshake pause/delete use for a download.
     */
    suspend fun stop(id: Long) {
        suppressed += id
        active.remove(id)?.cancelAndJoin()
    }

    fun release(id: Long) {
        suppressed -= id
    }

    /**
     * One recording, from its first byte to its last.
     *
     * The attempt loop runs until the window closes rather than for a fixed number of tries: a
     * two-hour recording may legitimately reconnect a dozen times, and a channel that is briefly down
     * at 20:00 should still record the rest of the programme.
     */
    private suspend fun runRecording(id: Long, onProgress: (RecordingProgress) -> Unit) {
        val row = recordingDao.getById(id) ?: return
        val target = MediaTarget.of(context, row.filePath) ?: return
        val startedAt = clock()
        if (!canRecordInto(target)) {
            android.util.Log.w(TAG, "recording target unavailable id=$id path=${row.filePath}")
            finish(row, bytes = 0, failure = RecordingFailure.NO_SPACE, startedAt = startedAt)
            return
        }
        recordingDao.updateProgress(
            id = id,
            status = RecordingStatus.RECORDING,
            failure = RecordingFailure.NONE,
            bytes = target.length(),
            filePath = target.stored,
            startedAt = startedAt,
            endedAt = null,
            timestamp = clock(),
        )

        var failure = RecordingFailure.NONE
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive && !RecordingRules.shouldStop(clock(), row.stopMs)) {
                attempt++
                val reason = try {
                    attemptRecord(row, target, onProgress)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "recording attempt $attempt failed id=$id: ${e.message}")
                    RecordingFailure.NETWORK
                }
                // Three reasons are terminal, because reconnecting cannot change any of the answers:
                // out of room would only fill the 500 MB it is protecting, and a scrambled or
                // DRM-protected channel will still be exactly that in three seconds.
                if (reason == RecordingFailure.NO_SPACE || reason == RecordingFailure.ENCRYPTED ||
                    reason == RecordingFailure.DRM_PROTECTED
                ) {
                    failure = reason
                    break
                }
                // NONE means the attempt ended of its own accord rather than by failing: the window
                // closed, the playlist said `#EXT-X-ENDLIST`, or an archive stream served the whole
                // programme. None of those is worth reconnecting for.
                if (reason == RecordingFailure.NONE) break
                failure = reason
                if (RecordingRules.shouldStop(clock(), row.stopMs)) break
                delay(RecordingRules.retryDelayMs(attempt))
            }
        } finally {
            // Also the path a cancellation takes — a recording stopped by hand, or by the drain
            // loop's overrun backstop, still has its bytes written down and its file kept. A DASH
            // recording's two halves are put together here, before the size is read, so the row
            // records the finished file rather than an empty one.
            val finished = finishDashSession(id, target) ?: target
            finish(row, finished.length(), failure, startedAt, finished.stored)
        }
    }

    /**
     * One connection's worth of recording. Returns [RecordingFailure.NONE] when it ended because the
     * window closed, or the reason it ended early — the caller decides whether to reconnect.
     */
    private suspend fun attemptRecord(
        row: RecordingEntity,
        target: MediaTarget,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingFailure {
        // #115 — a channel whose playlist declares a licence can never be written down: the CDM
        // decrypts only into a secure decoder for immediate display, so there is no point at which
        // these bytes exist in the clear for us to keep. Refused BEFORE the request, so it costs no
        // connection at all — unlike the HLS `#EXT-X-KEY` refusal, which can only be found by asking.
        // Read from the channel rather than the recording row so it follows a re-sync; a channel that
        // has since vanished simply has nothing to declare and falls through to the usual failure.
        if (channelDao.getById(row.channelId)?.drmConfig != null) {
            android.util.Log.w(TAG, "recording refused, DRM-protected channel id=${row.id}")
            return RecordingFailure.DRM_PROTECTED
        }
        val (url, userAgent) = resolveTarget(row)
        val headers = StreamHeaders.decode(row.httpHeaders)
        val agent = StreamHeaders.userAgentOf(headers) ?: userAgent

        client.newCall(request(url, agent, headers)).execute().use { response ->
            if (!response.isSuccessful) {
                return if (response.code == RecordingRules.SESSION_LIMIT_CODE) {
                    // The provider itself says the account is already streaming. The budget thought
                    // there was room — a stale or absent maxConnections — so this is the backstop,
                    // and it is a refusal rather than a fault.
                    RecordingFailure.NO_CONNECTION
                } else {
                    RecordingFailure.STREAM_UNAVAILABLE
                }
            }
            // An HLS channel is a list of segments, not a body of video. Peek at enough of it to tell
            // — the content type is unreliable and the `.m3u8` in the URL disappears behind a
            // redirect, but the first line of the body never lies.
            val peek = response.peekBody(PLAYLIST_PEEK_BYTES).string()
            val contentType = response.header("Content-Type")
            if (HlsMediaPlaylist.looksLikePlaylist(contentType, peek)) {
                // The playlist's *final* URL, so relative segment URIs resolve against wherever the
                // redirects actually landed rather than where we asked.
                return recordHls(row, target, response.request.url.toString(), agent, headers, onProgress)
            }
            // A DASH manifest is a document too, and a few kilobytes of it. Before this existed it
            // fell through to the byte pump below, which wrote the XML into the recording, read
            // end-of-body, reported NETWORK, reconnected and appended the same XML again for the
            // whole window — a file of concatenated manifests and a reconnect storm to produce it.
            if (DashManifest.looksLikeDashManifest(contentType, peek)) {
                return recordDash(row, target, response.request.url.toString(), agent, headers, onProgress)
            }
            // Always append: a reconnect continues the same file from wherever the stream is now.
            // There is no Range to resume with and nothing to rewind to.
            response.body.byteStream().use { input ->
                target.openOutput(append = true).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastTick = 0L
                    while (true) {
                        if (!currentCoroutineContext().isActive) return RecordingFailure.NONE
                        if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
                        if (!RecordingRules.hasSpace(target.usableSpace())) {
                            android.util.Log.w(TAG, "recording stopped, disk reserve reached id=${row.id}")
                            return RecordingFailure.NO_SPACE
                        }
                        val read = input.read(buffer)
                        if (read < 0) {
                            // End of body means opposite things for the two kinds of recording. An
                            // archive stream ends because it has served the whole programme; a live
                            // stream never ends of its own accord, so when it does the provider
                            // dropped us and the right answer is to reconnect and append.
                            return if (RecordingSchedule.isCatchUp(row)) {
                                RecordingFailure.NONE
                            } else {
                                RecordingFailure.NETWORK
                            }
                        }
                        out.write(buffer, 0, read)
                        val now = clock()
                        if (now - lastTick > PROGRESS_INTERVAL_MS) {
                            lastTick = now
                            val bytes = target.length()
                            recordingDao.updateProgress(
                                id = row.id,
                                status = RecordingStatus.RECORDING,
                                failure = RecordingFailure.NONE,
                                bytes = bytes,
                                filePath = target.stored,
                                startedAt = row.startedAt ?: now,
                                endedAt = null,
                                timestamp = now,
                            )
                            onProgress(RecordingProgress(row.id, row.title, row.channelName, bytes, row.stopMs))
                        }
                    }
                }
            }
        }
    }

    /**
     * Record an HLS channel: poll the media playlist, append every segment that is new, repeat until
     * the window closes.
     *
     * **The playlist is re-fetched every cycle and no segment URL is ever kept.** Several providers
     * sign each segment individually, and a URL cached for one cycle is a 403 in the next — the same
     * cause behind the Live TV black screen recorded in `owntv-live-403-signed-segments`. Segments are
     * identified by their **media sequence number**, not by their URL, so a signature that changes
     * between polls does not make an old segment look new.
     *
     * **Encrypted playlists are refused, not attempted** (§1.5). Writing `#EXT-X-KEY`-protected
     * segments out unchanged produces a file of exactly the right size that will not play, and a
     * recording the user only discovers is worthless when they sit down to watch it is worse than
     * one that said no at the start.
     */
    private suspend fun recordHls(
        row: RecordingEntity,
        target: MediaTarget,
        playlistUrl: String,
        userAgent: String,
        headers: Map<String, String>,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingFailure {
        // The sequence number of the last segment written. Survives reconnects within this attempt;
        // a fresh attempt re-reads it as "whatever the playlist offers now", which is correct — a
        // live window has moved on and there is nothing to catch up to.
        var lastSequence = -1L
        while (currentCoroutineContext().isActive) {
            if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
            if (!RecordingRules.hasSpace(target.usableSpace())) {
                android.util.Log.w(TAG, "recording stopped, disk reserve reached id=${row.id}")
                return RecordingFailure.NO_SPACE
            }
            val text = client.newCall(request(playlistUrl, userAgent, headers)).execute().use { response ->
                if (!response.isSuccessful) return RecordingFailure.STREAM_UNAVAILABLE
                response.body.string()
            }
            val playlist = HlsMediaPlaylist.parse(text)
            if (playlist.isEncrypted) {
                android.util.Log.w(TAG, "recording refused, encrypted playlist id=${row.id}")
                return RecordingFailure.ENCRYPTED
            }
            // A window that has scrolled past us entirely: take it from where it is now rather than
            // asking for segments the provider no longer serves.
            if (lastSequence >= 0 && playlist.mediaSequence > lastSequence + 1) lastSequence = playlist.mediaSequence - 1

            for (segment in playlist.segments) {
                if (!currentCoroutineContext().isActive) return RecordingFailure.NONE
                if (lastSequence >= 0 && segment.sequence <= lastSequence) continue
                if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
                if (!RecordingRules.hasSpace(target.usableSpace())) return RecordingFailure.NO_SPACE
                val segmentUrl = absoluteUrl(playlistUrl, segment.uri) ?: continue
                val ok = client.newCall(request(segmentUrl, userAgent, headers)).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    target.openOutput(append = true).use { out ->
                        response.body.byteStream().use { input -> input.copyTo(out, BUFFER_BYTES) }
                    }
                    true
                }
                // One segment the provider would not serve is a gap, not a failure: the next poll
                // carries on. Giving up here would end a two-hour recording over one bad six seconds.
                if (!ok) android.util.Log.w(TAG, "segment refused id=${row.id} seq=${segment.sequence}")
                lastSequence = segment.sequence
                report(row, target, onProgress)
            }
            // A playlist that says it has ended is a finite stream — a catch-up window, usually. The
            // recording is done whether or not the clock agrees.
            if (playlist.endList) return RecordingFailure.NONE
            delay(playlist.pollIntervalMs)
        }
        return RecordingFailure.NONE
    }

    /**
     * Record a DASH channel: poll the manifest, fetch every segment that is new, repeat until the
     * window closes.
     *
     * **The difference from [recordHls], and the only one that matters.** An HLS segment is MPEG-TS
     * with audio and video already multiplexed, so concatenation produces a playable file. DASH
     * normally keeps them in two Representations, so the two halves are written to a temp file each
     * and muxed together at the end — see [finishDashSession]. A Representation that already carries
     * both needs none of that and is written straight into the recording, exactly as HLS is.
     *
     * **The Representations are chosen once and kept**, held in [dashSessions] so a reconnect
     * continues the same two files rather than starting a second pair at a different quality. A
     * resolution or codec change partway through is precisely what the mux cannot absorb.
     *
     * **The manifest is re-read every cycle and no segment URL is ever kept**, for the same reason
     * [recordHls] documents: several providers sign each segment individually, so a URL cached for
     * one cycle is a 403 in the next. Segments are identified by their **number**, never their URL.
     *
     * **A protected manifest is refused, not attempted.** The DASH `#EXT-X-KEY`: it can only be found
     * by asking, and writing the segments out unchanged would produce a file of the right size that
     * will not play.
     */
    private suspend fun recordDash(
        row: RecordingEntity,
        target: MediaTarget,
        manifestUrl: String,
        userAgent: String,
        headers: Map<String, String>,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingFailure {
        while (currentCoroutineContext().isActive) {
            if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
            if (!RecordingRules.hasSpace(target.usableSpace())) {
                android.util.Log.w(TAG, "recording stopped, disk reserve reached id=${row.id}")
                return RecordingFailure.NO_SPACE
            }
            val text = client.newCall(request(manifestUrl, userAgent, headers)).execute().use { response ->
                if (!response.isSuccessful) return RecordingFailure.STREAM_UNAVAILABLE
                response.body.string()
            }
            val manifest = DashManifest.parse(text) ?: return RecordingFailure.STREAM_UNAVAILABLE
            if (manifest.contentProtected) {
                android.util.Log.w(TAG, "recording refused, protected manifest id=${row.id}")
                return RecordingFailure.DRM_PROTECTED
            }

            val session = dashSessions[row.id] ?: run {
                val selection = DashRecordingPlan.selectTracks(manifest)
                    ?: return RecordingFailure.STREAM_UNAVAILABLE
                openDashSession(row, target, selection).also { dashSessions[row.id] = it }
            }

            var wrote = false
            // Whichever track was resolved first sets the poll rate: the tracks of one manifest share
            // a segment duration, and `minimumUpdatePeriod` usually decides it anyway.
            var pace: DashRepresentation? = null
            for (track in session.tracks) {
                // Resolved fresh from this cycle's manifest — a SegmentTimeline lists different
                // segments every time — but always by the id chosen at the start.
                val representation = manifest.representations.firstOrNull { it.id == track.id }
                    ?: run {
                        // The quality we fixed on has gone. Said as a failure rather than quietly
                        // continued at another one, which would change codec or resolution mid-file.
                        android.util.Log.w(TAG, "recording lost its representation id=${row.id} rep=${track.id}")
                        return RecordingFailure.STREAM_UNAVAILABLE
                    }
                if (pace == null) pace = representation
                if (!track.initWritten) {
                    // The initialisation segment carries the codec configuration. Without it first,
                    // the file is a stream of fragments nothing can read — so this is fatal, unlike
                    // a media segment, which is only a gap.
                    val init = representation.initializationUrl
                    if (init != null && !writeDashSegment(row, track, target, manifestUrl, init, userAgent, headers)) {
                        return RecordingFailure.STREAM_UNAVAILABLE
                    }
                    track.initWritten = true
                }
                val plan = DashRecordingPlan.nextSegments(manifest, representation, track.lastNumber, clock())
                if (plan.unschedulable) {
                    android.util.Log.w(TAG, "recording cannot schedule manifest id=${row.id} rep=${track.id}")
                    return RecordingFailure.STREAM_UNAVAILABLE
                }
                for (segment in plan.segments) {
                    if (!currentCoroutineContext().isActive) return RecordingFailure.NONE
                    if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
                    if (!RecordingRules.hasSpace(target.usableSpace())) return RecordingFailure.NO_SPACE
                    val ok = writeDashSegment(
                        row, track, target, manifestUrl, segment.url, userAgent, headers,
                    )
                    // One segment the provider would not serve is a gap, not a failure — the same
                    // judgement recordHls makes, and for the same reason.
                    if (!ok) {
                        android.util.Log.w(TAG, "segment refused id=${row.id} rep=${track.id} n=${segment.number}")
                    }
                    wrote = true
                }
                track.lastNumber = plan.lastNumber
            }
            report(row, target, onProgress)

            // A static manifest is a finite window — catch-up, usually. It is finished when a whole
            // cycle finds nothing new, which is not the same as one pass: a numbered template is
            // deliberately taken a bounded number of segments at a time.
            if (!manifest.dynamic) {
                if (!wrote) return RecordingFailure.NONE
                continue
            }
            pace?.let { delay(DashRecordingPlan.pollIntervalMs(manifest, it)) }
        }
        return RecordingFailure.NONE
    }

    /**
     * Open somewhere for each chosen Representation to be written.
     *
     * A single Representation carrying everything is written **straight into the recording** — no
     * temp file, no mux, nothing to go wrong, exactly as HLS behaves. Two separate tracks each get a
     * temp file, which is what [finishDashSession] later muxes together.
     *
     * Temp files sit **beside the recording** when it is an ordinary file, so they share its volume
     * and the space checks that already guard it. A recording into a Storage Access Framework
     * document has no sibling directory to use, so those fall back to the app's cache.
     */
    private fun openDashSession(
        row: RecordingEntity,
        target: MediaTarget,
        selection: DashTrackSelection,
    ): DashSession {
        val needsMux = selection.needsMux
        val directory = (target as? MediaTarget.Path)?.file?.parentFile ?: context.cacheDir
        val tracks = selection.tracks.mapIndexed { index, representation ->
            DashTrack(
                id = representation.id,
                kind = representation.kind,
                temp = if (needsMux) File(directory, "$TEMP_PREFIX${row.id}-$index$TEMP_SUFFIX") else null,
            )
        }
        // A reconnect reuses this session; a *new* recording must never inherit a previous one's
        // half-written fragments, so anything left behind under these names is cleared first.
        tracks.forEach { it.temp?.delete() }
        android.util.Log.i(
            TAG,
            "dash recording id=${row.id} tracks=${tracks.joinToString(",") { "${it.kind}:${it.id}" }} mux=$needsMux",
        )
        return DashSession(tracks = tracks, needsMux = needsMux)
    }

    /** One segment or initialisation segment, appended to wherever its track is being written. */
    private fun writeDashSegment(
        row: RecordingEntity,
        track: DashTrack,
        target: MediaTarget,
        manifestUrl: String,
        url: String,
        userAgent: String,
        headers: Map<String, String>,
    ): Boolean {
        // Resolved against the manifest's *final* URL, so relative segment paths follow the redirects
        // rather than the address we asked for.
        val absolute = absoluteUrl(manifestUrl, url) ?: return false
        return runCatching {
            client.newCall(request(absolute, userAgent, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use false
                // Opened per segment rather than held open, exactly as recordHls does: a recording
                // that is killed mid-programme keeps every byte already flushed.
                val out = track.temp?.let { java.io.FileOutputStream(it, true) } ?: target.openOutput(append = true)
                out.use { sink -> response.body.byteStream().use { input -> input.copyTo(sink, BUFFER_BYTES) } }
                true
            }
        }.getOrElse { error ->
            android.util.Log.w(TAG, "segment failed id=${row.id} rep=${track.id}: ${error.message}")
            false
        }
    }

    /**
     * End a DASH recording: put its two halves together and clear up after it.
     *
     * Called from [runRecording]'s `finally`, so it runs however the recording ended — the window
     * closing, the user stopping it, the overrun backstop cancelling it, or a failure. Does nothing
     * at all for a recording that was not DASH, or one written straight into the file.
     *
     * Returns where the finished recording now is, when muxing moved it. A muxed recording is an
     * **MP4**, so it is given an `.mp4` name rather than the `.ts` a live recording normally gets:
     * [RecordingRules.fileName] chooses `.ts` because a transport stream survives being cut off, but
     * the file this produces is not one, and a file manager should not be told otherwise.
     */
    private suspend fun finishDashSession(id: Long, target: MediaTarget): MediaTarget? {
        val session = dashSessions.remove(id) ?: return null
        if (!session.needsMux) return null
        val temps = session.tracks.mapNotNull { it.temp }
        val result = withContext(Dispatchers.IO) { muxInto(id, target, temps) }
        // **Kept when the mux failed.** They are the recording — a few gigabytes of it — and deleting
        // them on the one path where the finished file does not exist would throw away everything the
        // recording captured. `recoverInterruptedDashRecordings` tries them once more on the next
        // pass, and clears them then if it still cannot.
        if (result.succeeded) temps.forEach { it.delete() }
        return result.movedTo
    }

    /**
     * Mux [temps] into the recording, and say where it ended up.
     *
     * An ordinary file is muxed **directly** into its `.mp4` sibling and the empty `.ts` removed — a
     * recording can be several gigabytes, and copying it afterwards would double both the time and
     * the space. A Storage Access Framework document has no sibling to write to, so it is muxed in
     * the cache and copied in; that path keeps the name it was given.
     */
    private fun muxInto(id: Long, target: MediaTarget, temps: List<File>): MuxResult {
        if (target is MediaTarget.Path) {
            val muxed = File(target.file.parentFile, RecordingRules.muxedNameOf(target.file.name))
            if (!DashRemux.mux(temps, muxed)) {
                android.util.Log.w(TAG, "dash mux produced nothing id=$id")
                muxed.delete()
                return MuxResult(succeeded = false, movedTo = null)
            }
            // Only once there is something to replace it with.
            target.delete()
            android.util.Log.i(TAG, "dash recording muxed id=$id bytes=${muxed.length()}")
            return MuxResult(succeeded = true, movedTo = MediaTarget.Path(muxed))
        }
        val scratch = File(context.cacheDir, "$TEMP_PREFIX$id$MUXED_SUFFIX")
        return try {
            if (!DashRemux.mux(temps, scratch)) {
                android.util.Log.w(TAG, "dash mux produced nothing id=$id")
                return MuxResult(succeeded = false, movedTo = null)
            }
            scratch.inputStream().use { input ->
                target.openOutput(append = false).use { out -> input.copyTo(out, BUFFER_BYTES) }
            }
            android.util.Log.i(TAG, "dash recording muxed id=$id bytes=${scratch.length()}")
            MuxResult(succeeded = true, movedTo = null)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dash mux could not be stored id=$id: ${e.message}")
            MuxResult(succeeded = false, movedTo = null)
        } finally {
            scratch.delete()
        }
    }

    /**
     * Put back together any DASH recording whose two halves were captured but never muxed, because
     * the process died before it could.
     *
     * **This is what makes the temp files a safety net rather than debris.** The mux runs once, at
     * the end — `MediaMuxer` cannot append to an existing MP4, so remuxing every couple of minutes
     * would mean redoing the whole recording each time, sixty times over a two-hour programme. The
     * temp files are the crash-proof part instead: each is a valid fragmented-MP4 stream, flushed a
     * segment at a time, and this turns them into a playable recording on the next run.
     *
     * Runs once per drain, before anything starts, so it can never collide with a live recording.
     *
     * **A recording whose window is still open is left alone** and simply restarts, losing what it
     * captured before the crash. Resuming into the same temp files would need certainty that this run
     * picks the same Representations as the last one, and nothing on disk records what those were.
     */
    private suspend fun recoverInterruptedDashRecordings() {
        for (row in recordingDao.running()) {
            if (active.containsKey(row.id) || row.id in suppressed) continue
            if (!RecordingRules.shouldStop(clock(), row.stopMs)) continue
            val target = MediaTarget.of(context, row.filePath) ?: continue
            val temps = orphanedDashTemps(row.id, target)
            if (temps.isEmpty()) continue
            android.util.Log.i(TAG, "recovering interrupted dash recording id=${row.id} parts=${temps.size}")
            val result = withContext(Dispatchers.IO) { muxInto(row.id, target, temps) }
            // Cleared either way this time: a second failure on the same bytes will not become a
            // first success, and leaving gigabytes behind forever is its own fault.
            temps.forEach { it.delete() }
            val finished = result.movedTo ?: target
            finish(row, finished.length(), RecordingFailure.NONE, row.startedAt ?: clock(), finished.stored)
        }
    }

    /** Temp files left behind for [id], wherever that recording was writing them. */
    private fun orphanedDashTemps(id: Long, target: MediaTarget): List<File> {
        val directory = (target as? MediaTarget.Path)?.file?.parentFile ?: context.cacheDir
        val prefix = "$TEMP_PREFIX$id-"
        return directory
            ?.listFiles { file -> file.isFile && file.name.startsWith(prefix) && file.name.endsWith(TEMP_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** One request, carrying the channel's own headers and the User-Agent that goes with them. */
    private fun request(url: String, userAgent: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (name, value) -> if (!name.equals("User-Agent", true)) builder.header(name, value) }
        return builder.build()
    }

    /** A segment URI resolved against the playlist it came from; null when it is not a URL at all. */
    private fun absoluteUrl(base: String, uri: String): String? =
        runCatching { java.net.URI(base).resolve(uri).toString() }.getOrNull()

    /** Write the byte count down and tell the pill, at most twice a second. */
    private suspend fun report(row: RecordingEntity, target: MediaTarget, onProgress: (RecordingProgress) -> Unit) {
        val now = clock()
        if (now - lastReportAt < PROGRESS_INTERVAL_MS) return
        lastReportAt = now
        val bytes = target.length()
        recordingDao.updateProgress(
            id = row.id,
            status = RecordingStatus.RECORDING,
            failure = RecordingFailure.NONE,
            bytes = bytes,
            filePath = target.stored,
            startedAt = row.startedAt ?: now,
            endedAt = null,
            timestamp = now,
        )
        onProgress(RecordingProgress(row.id, row.title, row.channelName, bytes, row.stopMs))
    }

    @Volatile
    private var lastReportAt = 0L

    /**
     * Write down how it ended. Uses `updateProgress` and never `upsert`: the table's unique index on
     * `(profileId, channelId, programmeStartMs)` would make a REPLACE delete this row and insert a
     * new one with a different id, orphaning anything still holding the old one.
     */
    private suspend fun finish(
        row: RecordingEntity,
        bytes: Long,
        failure: RecordingFailure,
        startedAt: Long,
        /** Where the file actually is — a muxed DASH recording has moved from `.ts` to `.mp4`. */
        filePath: String? = null,
    ) {
        val (status, reason) = RecordingRules.outcomeOf(bytes, failure)
        recordingDao.updateProgress(
            id = row.id,
            status = status,
            failure = reason,
            bytes = bytes,
            filePath = filePath ?: row.filePath,
            startedAt = row.startedAt ?: startedAt,
            endedAt = clock(),
            timestamp = clock(),
        )
    }

    /** Nothing was written and nothing will be: the programme is gone and the row says why (D10). */
    private suspend fun markMissed(row: RecordingEntity, failure: RecordingFailure) {
        android.util.Log.i(TAG, "recording missed id=${row.id} reason=$failure")
        recordingDao.updateProgress(
            id = row.id,
            status = RecordingStatus.MISSED,
            failure = failure,
            bytes = 0,
            filePath = null,
            startedAt = null,
            endedAt = clock(),
            timestamp = clock(),
        )
    }

    /**
     * The URL and User-Agent this attempt should fetch, resolved **fresh every time**: a Stalker
     * portal mints a single-use link that dies long before a two-hour recording does.
     */
    private suspend fun resolveTarget(row: RecordingEntity): Pair<String, String> {
        val source = sourceDao.getById(row.sourceId)
        if (source == null || !streamUrlResolver.needsResolve(source)) {
            return row.streamUrl to (source?.userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_USER_AGENT)
        }
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        return streamUrlResolver.resolve(source, row.streamUrl, vod = false) to ua
    }

    /**
     * Somewhere to write, **and room to write into it**. The second half is this engine's own and not
     * a download's: a recording has no content length to check against up front, so the only moment
     * it can refuse for want of space is before it starts and then again as it goes.
     */
    private fun canRecordInto(target: MediaTarget): Boolean =
        target.ensureWritable() && RecordingRules.hasSpace(target.usableSpace())

    /**
     * One DASH recording's fixed choice of Representations, and where each is being written.
     *
     * Lives for the whole recording rather than one attempt — see [dashSessions].
     */
    private class DashSession(
        val tracks: List<DashTrack>,
        /** True when the two halves still have to be put back together. */
        val needsMux: Boolean,
    )

    /** Whether the mux produced a playable file, and where it left it. */
    private class MuxResult(val succeeded: Boolean, val movedTo: MediaTarget?)

    /** One Representation being recorded, and how far through it we are. */
    private class DashTrack(
        /** The Representation id fixed on at the start, re-resolved against each new manifest. */
        val id: String,
        val kind: DashTrackKind,
        /** Its temp file, or null when this track is written straight into the recording. */
        val temp: File?,
        /** The last segment number written. −1 until the first cycle has run. */
        var lastNumber: Long = -1L,
        /** Whether the initialisation segment has been written, which it must be before any other. */
        var initWritten: Boolean = false,
    )

    private companion object {
        const val TAG = "RecordingEngine"
        const val BUFFER_BYTES = 128 * 1024
        const val PROGRESS_INTERVAL_MS = 500L

        /** Temp files for a DASH recording's separate tracks, named so a reconnect finds them again. */
        const val TEMP_PREFIX = "owntv-dash-"
        const val TEMP_SUFFIX = ".part"

        /** What a muxed DASH recording is, as opposed to the `.ts` a live recording normally gets. */
        const val MUXED_SUFFIX = ".mp4"

        /** Enough of the body to see whether the first line is `#EXTM3U`. */
        const val PLAYLIST_PEEK_BYTES = 1024L

        /** How often the drain loop looks for newly due rows and overrunning ones. */
        const val POLL_MS = 2_000L
    }
}
