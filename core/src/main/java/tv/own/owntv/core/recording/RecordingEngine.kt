package tv.own.owntv.core.recording

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.settings.SettingsRepository
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
    private val recordingDao: RecordingDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val streams: OpenStreamRegistry,
    private val settings: SettingsRepository,
    private val activityTracker: RecordingActivityTracker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Recordings currently running, so one can be stopped precisely without touching the others. */
    private val active = ConcurrentHashMap<Long, Job>()

    /** Ids the user has just stopped or deleted; the drain loop steps over them. */
    private val suppressed = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

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
        while (currentCoroutineContext().isActive) {
            queueDirty = false
            val now = clock()
            val reserve = settings.recordingReserveConnection()
            for (row in recordingDao.dueAt(now)) {
                if (active.containsKey(row.id) || row.id in suppressed) continue
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
        val file = File(row.filePath ?: return)
        val startedAt = clock()
        if (!ensureWritable(file)) {
            android.util.Log.w(TAG, "recording target unavailable id=$id path=${row.filePath}")
            finish(row, bytes = 0, failure = RecordingFailure.NO_SPACE, startedAt = startedAt)
            return
        }
        recordingDao.updateProgress(
            id = id,
            status = RecordingStatus.RECORDING,
            failure = RecordingFailure.NONE,
            bytes = bytesOnDisk(file),
            filePath = file.absolutePath,
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
                    attemptRecord(row, file, onProgress)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "recording attempt $attempt failed id=$id: ${e.message}")
                    RecordingFailure.NETWORK
                }
                // Two reasons are terminal, because reconnecting cannot change either answer: out of
                // room would only fill the 500 MB it is protecting, and a scrambled channel will
                // still be scrambled in three seconds.
                if (reason == RecordingFailure.NO_SPACE || reason == RecordingFailure.ENCRYPTED) {
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
            // loop's overrun backstop, still has its bytes written down and its file kept.
            finish(row, bytesOnDisk(file), failure, startedAt)
        }
    }

    /**
     * One connection's worth of recording. Returns [RecordingFailure.NONE] when it ended because the
     * window closed, or the reason it ended early — the caller decides whether to reconnect.
     */
    private suspend fun attemptRecord(
        row: RecordingEntity,
        file: File,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingFailure {
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
            if (HlsMediaPlaylist.looksLikePlaylist(response.header("Content-Type"), peek)) {
                // The playlist's *final* URL, so relative segment URIs resolve against wherever the
                // redirects actually landed rather than where we asked.
                return recordHls(row, file, response.request.url.toString(), agent, headers, onProgress)
            }
            // Always append: a reconnect continues the same file from wherever the stream is now.
            // There is no Range to resume with and nothing to rewind to.
            response.body.byteStream().use { input ->
                java.io.FileOutputStream(file, true).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastTick = 0L
                    while (true) {
                        if (!currentCoroutineContext().isActive) return RecordingFailure.NONE
                        if (RecordingRules.shouldStop(clock(), row.stopMs)) return RecordingFailure.NONE
                        if (!RecordingRules.hasSpace(usableSpace(file))) {
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
                            val bytes = file.length()
                            recordingDao.updateProgress(
                                id = row.id,
                                status = RecordingStatus.RECORDING,
                                failure = RecordingFailure.NONE,
                                bytes = bytes,
                                filePath = file.absolutePath,
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
        file: File,
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
            if (!RecordingRules.hasSpace(usableSpace(file))) {
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
                if (!RecordingRules.hasSpace(usableSpace(file))) return RecordingFailure.NO_SPACE
                val segmentUrl = absoluteUrl(playlistUrl, segment.uri) ?: continue
                val ok = client.newCall(request(segmentUrl, userAgent, headers)).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    java.io.FileOutputStream(file, true).use { out ->
                        response.body.byteStream().use { input -> input.copyTo(out, BUFFER_BYTES) }
                    }
                    true
                }
                // One segment the provider would not serve is a gap, not a failure: the next poll
                // carries on. Giving up here would end a two-hour recording over one bad six seconds.
                if (!ok) android.util.Log.w(TAG, "segment refused id=${row.id} seq=${segment.sequence}")
                lastSequence = segment.sequence
                report(row, file, onProgress)
            }
            // A playlist that says it has ended is a finite stream — a catch-up window, usually. The
            // recording is done whether or not the clock agrees.
            if (playlist.endList) return RecordingFailure.NONE
            delay(playlist.pollIntervalMs)
        }
        return RecordingFailure.NONE
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
    private suspend fun report(row: RecordingEntity, file: File, onProgress: (RecordingProgress) -> Unit) {
        val now = clock()
        if (now - lastReportAt < PROGRESS_INTERVAL_MS) return
        lastReportAt = now
        val bytes = file.length()
        recordingDao.updateProgress(
            id = row.id,
            status = RecordingStatus.RECORDING,
            failure = RecordingFailure.NONE,
            bytes = bytes,
            filePath = file.absolutePath,
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
    private suspend fun finish(row: RecordingEntity, bytes: Long, failure: RecordingFailure, startedAt: Long) {
        val (status, reason) = RecordingRules.outcomeOf(bytes, failure)
        recordingDao.updateProgress(
            id = row.id,
            status = status,
            failure = reason,
            bytes = bytes,
            filePath = row.filePath,
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

    private fun ensureWritable(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists()) runCatching { parent.mkdirs() }
        return parent.isDirectory && parent.canWrite() && RecordingRules.hasSpace(parent.usableSpace)
    }

    private fun usableSpace(file: File): Long = file.parentFile?.usableSpace ?: 0L

    private fun bytesOnDisk(file: File): Long = if (file.exists()) file.length() else 0L

    private companion object {
        const val TAG = "RecordingEngine"
        const val BUFFER_BYTES = 128 * 1024
        const val PROGRESS_INTERVAL_MS = 500L

        /** Enough of the body to see whether the first line is `#EXTM3U`. */
        const val PLAYLIST_PEEK_BYTES = 1024L

        /** How often the drain loop looks for newly due rows and overrunning ones. */
        const val POLL_MS = 2_000L
    }
}
