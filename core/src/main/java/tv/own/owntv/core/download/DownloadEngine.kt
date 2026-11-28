package tv.own.owntv.core.download

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.WatchSession
import tv.own.owntv.core.model.DownloadReason
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.storage.MediaTarget
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** What the foreground notification shows about the transfer currently running. */
data class DownloadProgress(val id: Long, val title: String, val downloadedBytes: Long, val totalBytes: Long)

/**
 * The transfer half of downloads, split out of [DownloadManager] so it can run inside
 * [DownloadWorker] — a foreground service — instead of an app-scoped coroutine that Android kills
 * as soon as the user leaves OwnTV.
 *
 * [DownloadDao] stays the single source of truth: the engine holds no queue of its own, it just
 * drains whatever rows are QUEUED/RUNNING, one at a time, until none are left.
 *
 * One file at a time, but not necessarily over one connection: see [runBlocks]. A download
 * starts on one connection and adds more, up to [ConnectionRamp.MAX_CONNECTIONS], only while each one
 * makes it faster — and never more than the playlist's own stream limit leaves free.
 */
class DownloadEngine(
    /** To resolve a stored `filePath` into something writable, and for the piece plans' folder. */
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val activityTracker: DownloadActivityTracker,
    /** What is playing, and what Multiview and recordings hold — the streams a download must leave free. */
    private val watchSession: WatchSession,
    private val openStreams: OpenStreamRegistry,
    private val settings: SettingsRepository,
) {
    /** Jobs of transfers currently running, so pause/delete/retry can stop one precisely. */
    private val active = ConcurrentHashMap<Long, Job>()

    /**
     * Ids the user has just paused/deleted. The drain loop skips them: without this, a pause would
     * be undone the moment the loop re-read the queue before the PAUSED row was written.
     */
    private val suppressed = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Set whenever a row is queued, so a drain that is about to finish takes one more look. */
    @Volatile
    private var queueDirty = false

    private val _singleStreamBusy = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Playlists that allow one stream and have a download using it right now. Watching from one of
     * these needs that stream back ([DownloadWatchGuard]); the television's preview pane stays off.
     */
    val singleStreamBusy: StateFlow<Set<Long>> = _singleStreamBusy.asStateFlow()

    fun markQueued() {
        queueDirty = true
    }

    /**
     * Run every pending download, oldest first, one at a time, returning when the queue is empty.
     * Each transfer runs as a child job so that cancelling it (pause/delete) does not tear down the
     * drain itself — the next queued item still starts.
     */
    suspend fun drainQueue(onProgress: (DownloadProgress) -> Unit) = coroutineScope {
        // Every progress report also feeds the shell's status pill, so a transfer is visible in the
        // app and not only in the notification shade.
        val report: (DownloadProgress) -> Unit = { activityTracker.progress(it); onProgress(it) }
        val seen = mutableSetOf<Long>()
        while (currentCoroutineContext().isActive) {
            queueDirty = false
            val next = downloadDao.pending().firstOrNull { it.id !in suppressed && it.id !in seen }
            if (next == null) {
                // Something was queued between the read above and now: look again rather than exit,
                // otherwise the kick that follows it would be dropped by ExistingWorkPolicy.KEEP.
                if (queueDirty) continue else return@coroutineScope
            }
            seen += next.id
            // A one-stream playlist that is being watched: starting would cut the picture off. It
            // waits, and [DownloadManager] kicks the queue again when the watching stops.
            if (waitsForWatching(next)) {
                if (next.reason != DownloadReason.WAITING_FOR_WATCHING) {
                    downloadDao.updateProgress(next.id, next.status, next.downloadedBytes, next.totalBytes, System.currentTimeMillis(), DownloadReason.WAITING_FOR_WATCHING, null)
                }
                continue
            }
            val job = launch { runDownload(next.id, report) }
            active[next.id] = job
            try {
                job.join()
            } finally {
                active.remove(next.id, job)
                // Completed, failed, paused or deleted — either way this transfer is no longer live.
                activityTracker.finished()
            }
        }
    }

    /**
     * Stop the transfer of [id] if it is running and wait for it to actually let go of the file,
     * keeping the drain loop off it until [release] is called. Callers write the row's new status
     * in between, while nothing can touch it.
     */
    suspend fun suspendTransfer(id: Long) {
        suppressed += id
        active.remove(id)?.cancelAndJoin()
    }

    fun release(id: Long) {
        suppressed -= id
    }

    /** The playlist [d] downloads from; null when its catalogue item was pruned by a re-sync. */
    suspend fun sourceIdOf(d: DownloadEntity): Long? = when (d.mediaType) {
        MediaType.EPISODE -> seriesDao.getEpisodeById(d.itemId)?.let { seriesDao.getSeriesById(it.seriesId) }?.sourceId
        else -> movieDao.getById(d.itemId)?.sourceId
    }

    private suspend fun waitsForWatching(d: DownloadEntity): Boolean {
        val sourceId = sourceIdOf(d) ?: return false
        if (!watchSession.isWatching(sourceId)) return false
        if (sourceDao.getById(sourceId)?.maxConnections != 1) return false
        return settings.downloadWatchChoice.first() != SettingsRepository.DownloadWatchChoice.KEEP
    }

    private suspend fun runDownload(id: Long, onProgress: (DownloadProgress) -> Unit) {
        val d = downloadDao.getById(id) ?: return
        val target = MediaTarget.of(context, d.filePath)
        // A download folder on removable storage can simply be gone (card pulled, USB unplugged),
        // and a folder the user picked through the system picker can have its grant revoked.
        // Fail loudly rather than silently re-homing gigabytes onto internal storage.
        if (target == null || !target.ensureWritable()) {
            android.util.Log.w(TAG, "download target unavailable id=$id path=${d.filePath}")
            markFailed(id, 0, d.totalBytes, DownloadFailure(DownloadReason.FOLDER_UNAVAILABLE))
            return
        }
        // Resume whenever a partial file is on disk — including a RUNNING row left behind by a
        // process death — instead of only after an explicit pause. retry() deletes the file first,
        // so a deliberate restart still starts from zero.
        if (target.exists() && target.length() == 0L) target.truncate()
        // Attempt loop: a Stalker `create_link` URL dies after ~2-4 h, so a long download
        // can fail mid-stream. Each attempt re-resolves a FRESH URL from the stored cmd and resumes
        // with an HTTP Range from the bytes already written; a server that ignores Range restarts the
        // file from 0. M3U/Xtream pass through the resolver unchanged, so for them the loop is just a
        // plain transient-error retry with the same URL.
        var attempt = 0
        var last: DownloadFailure? = null
        while (currentCoroutineContext().isActive) {
            attempt++
            val done = try {
                attemptDownload(id, d, target, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadFailure) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                last = e
                false
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                last = DownloadFailure(DownloadReasons.ofError(e))
                false
            }
            if (done) return
            if (!currentCoroutineContext().isActive) return // paused/deleted — status already set by caller
            // The drive, not the network: another attempt would stop at the same byte.
            val driveFull = last?.reason == DownloadReason.NO_SPACE || last?.reason == DownloadReason.FILE_TOO_LARGE
            if (attempt >= MAX_ATTEMPTS || driveFull) {
                markFailed(id, bytesSoFar(target), d.totalBytes, last)
                return
            }
            // A busy account frees its slot on the provider's clock, not ours — often only some
            // seconds after the other stream stopped. Short retries just collect three refusals.
            val wait = if (last?.reason == DownloadReason.ACCOUNT_BUSY) BUSY_RETRY_DELAY_MS else RETRY_DELAY_MS
            delay(wait * attempt)
            // Playback may have opened on a one-stream playlist while this waited: then the next
            // attempt would take the picture's only stream. Wait for the watching to stop instead.
            if (waitsForWatching(d)) {
                downloadDao.updateProgress(id, DownloadStatus.QUEUED, bytesSoFar(target), d.totalBytes, System.currentTimeMillis(), DownloadReason.WAITING_FOR_WATCHING, null)
                return
            }
        }
    }

    /** The file holds a clean prefix of the download (it is always written in order). */
    private fun bytesSoFar(target: MediaTarget): Long = target.length()

    /** One download attempt. Returns true when the file completed; false/throws = retryable failure. */
    private suspend fun attemptDownload(
        id: Long,
        d: DownloadEntity,
        target: MediaTarget,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        // Resolve at download-start time, fresh every attempt — the row keeps the stored cmd as the
        // item's identity; only this attempt's HTTP request sees the minted URL. A resolve failure
        // (portal down / bad auth) is a retryable attempt like any HTTP failure.
        val resolved = resolveTarget(d)
        val busyKey = resolved.sourceId?.takeIf { resolved.maxConnections == 1 }
        busyKey?.let { _singleStreamBusy.value = _singleStreamBusy.value + it }
        try {
            val existing = DownloadResume.resumeOffset(target)
            // Asking for `bytes=0-` even for a fresh file is what tells us whether the server can
            // serve pieces: a 206 with the full size says yes.
            val first = client.newCall(request(resolved, existing, null)).execute()
            try {
                // 416 on a resume = our Range starts at/after the end of the resource — the file already
                // holds every byte the server has (a completed download whose COMPLETED write was lost to
                // a crash/disconnect). Without this, the retry loop would mark a finished file FAILED.
                if (first.code == 416 && existing > 0) {
                    downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = existing, totalBytes = existing, updatedAt = System.currentTimeMillis(), reason = null, httpCode = null))
                    return true
                }
                if (!first.isSuccessful) throw DownloadFailure(DownloadReasons.ofHttp(first.code), first.code)
                val total = totalOf(first)
                // The server serves byte ranges and there is enough left to be worth sharing out.
                if (first.code == 206 && total != null && total - existing >= MIN_SPLIT_BYTES) {
                    first.close()
                    return runBlocks(id, d, target, existing, total, resolved, onProgress)
                }
                return streamWhole(id, d, target, first, existing, onProgress)
            } finally {
                first.close()
            }
        } finally {
            busyKey?.let { _singleStreamBusy.value = _singleStreamBusy.value - it }
        }
    }

    /** The whole file over one connection, appended after [existing] when the server honoured the Range. */
    private suspend fun streamWhole(
        id: Long,
        d: DownloadEntity,
        target: MediaTarget,
        resp: Response,
        existing: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        val body = resp.body
        val append = resp.code == 206 && existing > 0 // server honoured the Range
        val total = DownloadResume.expectedTotal(append, existing, body.contentLength())
        var done = if (append) existing else 0L
        downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, System.currentTimeMillis(), null, null)
        onProgress(DownloadProgress(id, d.title, done, total))
        body.byteStream().use { input ->
            target.openOutput(append).use { out ->
                val buf = ByteArray(BUFFER_BYTES)
                var lastTick = 0L
                while (true) {
                    if (!currentCoroutineContext().isActive) return false
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val t = System.currentTimeMillis()
                    if (t - lastTick > 500) {
                        downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, t, null, null)
                        onProgress(DownloadProgress(id, d.title, done, total))
                        lastTick = t
                    }
                }
            }
        }
        val size = target.length()
        downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = size, totalBytes = size, updatedAt = System.currentTimeMillis(), reason = null, httpCode = null))
        return true
    }

    /**
     * The rest of the file from [existing], over as many connections as help, written **in order**.
     *
     * Each connection fetches the next block of [BLOCK_BYTES] with a Range request into memory; the
     * blocks are appended to the file strictly in sequence. A USB stick (exFAT, FAT32) cannot leave a
     * gap in a file: writing far ahead makes it fill the whole gap with zeros first, which made
     * multi-connection downloads slower there than one connection (5 MB/s against 14). Written in
     * order, the file on disk is always a clean prefix, so a resume is just "Range from its length".
     *
     * At most one block per connection plus [AHEAD_BLOCKS] wait in memory. A block whose connection
     * failed goes back to the front of the queue; the attempt fails only when no connection is left.
     */
    private suspend fun runBlocks(
        id: Long,
        d: DownloadEntity,
        target: MediaTarget,
        existing: Long,
        total: Long,
        resolved: Resolved,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean = coroutineScope {
        val ready = ConcurrentHashMap<Long, ByteArray>()
        val retry = java.util.concurrent.ConcurrentLinkedDeque<Long>()
        val next = java.util.concurrent.atomic.AtomicLong(existing)
        val workers = ArrayList<Job>()
        var written = existing
        var lastError: DownloadFailure? = null
        val ramp = ConnectionRamp()
        val rate = TransferRate()
        // How many connections the ramp has earned so far. Starts at one.
        var wanted = 1

        fun moreToFetch() = retry.isNotEmpty() || next.get() < total

        /** The next block to fetch, or null when every block is taken. */
        fun takeBlock(): Long? = retry.pollFirst() ?: next.getAndAdd(BLOCK_BYTES).takeIf { it < total }

        fun startWorker() {
            workers += launch(Dispatchers.IO) {
                while (isActive) {
                    // Never run far ahead of what is written: memory, not the network, is the limit there.
                    while (isActive && ready.size >= workers.size + AHEAD_BLOCKS) delay(20)
                    val start = takeBlock() ?: return@launch
                    try {
                        ready[start] = fetchBlock(resolved, start, minOf(start + BLOCK_BYTES, total))
                    } catch (e: CancellationException) {
                        retry.addFirst(start)
                        throw e
                    } catch (e: DownloadFailure) {
                        retry.addFirst(start)
                        lastError = e
                        // The provider said no to this connection: hold at what is left, ask for no more.
                        if (e.reason == DownloadReason.ACCOUNT_BUSY || e.reason == DownloadReason.REFUSED) ramp.stop()
                        return@launch
                    } catch (e: Exception) {
                        retry.addFirst(start)
                        lastError = DownloadFailure(DownloadReasons.ofError(e))
                        return@launch
                    }
                }
            }
        }

        downloadDao.updateProgress(id, DownloadStatus.RUNNING, written, total, System.currentTimeMillis(), null, null)
        var lastRampAt = System.currentTimeMillis()
        var lastTick = 0L
        startWorker()
        try {
            target.openOutput(append = existing > 0).use { out ->
                while (written < total) {
                    // Write every block that is next in line.
                    val block = ready.remove(written)
                    if (block != null) {
                        out.write(block)
                        written += block.size
                        continue
                    }
                    delay(WRITE_POLL_MS)
                    val ended = workers.filter { !it.isActive }
                    workers.removeAll(ended)
                    if (ended.isNotEmpty() && moreToFetch()) {
                        // A connection failed. With none left the attempt has failed (the attempt loop
                        // retries it with its back-off); otherwise hold at what is still running.
                        if (workers.isEmpty()) throw lastError ?: DownloadFailure(DownloadReason.CONNECTION_LOST)
                        wanted = workers.size
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastTick < TICK_MS) continue
                    lastTick = now
                    downloadDao.updateProgress(id, DownloadStatus.RUNNING, written, total, now, null, null)
                    onProgress(DownloadProgress(id, d.title, written, total))
                    val speed = rate.sample(now, written)

                    val cap = connectionCap(resolved)
                    wanted = minOf(wanted, cap)
                    // Something else started on this playlist (a channel, a recording): give streams back.
                    workers.drop(cap).forEach { it.cancel() }
                    if (now - lastRampAt >= RAMP_INTERVAL_MS) {
                        lastRampAt = now
                        if (ramp.shouldAdd(workers.size, cap, speed)) wanted = workers.size + 1
                    }
                    while (workers.count { it.isActive && !it.isCancelled } < wanted && moreToFetch()) startWorker()
                }
            }
        } finally {
            workers.forEach { it.cancel() }
        }
        val size = target.length()
        if (size != total) throw DownloadFailure(DownloadReason.CONNECTION_LOST)
        downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = size, totalBytes = size, updatedAt = System.currentTimeMillis(), reason = null, httpCode = null))
        true
    }

    /** One block `[start, end)` into memory; anything but exactly those bytes is a failure. */
    private fun fetchBlock(resolved: Resolved, start: Long, end: Long): ByteArray {
        client.newCall(request(resolved, start, end - 1)).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadFailure(DownloadReasons.ofHttp(resp.code), resp.code)
            // A 200, or a range starting elsewhere, would put the wrong bytes in this block.
            val from = resp.header("Content-Range")?.substringAfter("bytes", "")?.trim()?.substringBefore('-')?.toLongOrNull()
            if (resp.code != 206 || from != start) throw DownloadFailure(DownloadReason.HTTP_ERROR, resp.code)
            val buf = ByteArray((end - start).toInt())
            var off = 0
            resp.body.byteStream().use { input ->
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
            }
            if (off != buf.size) throw DownloadFailure(DownloadReason.CONNECTION_LOST)
            return buf
        }
    }

    /**
     * How many connections this download may hold now: the playlist's limit less what is playing and
     * what Multiview and recordings hold, never below one and never above the ramp's ceiling. An
     * unknown limit leaves the ramp alone to find out.
     */
    private fun connectionCap(resolved: Resolved): Int {
        val max = resolved.maxConnections
        if (max <= 0) return ConnectionRamp.MAX_CONNECTIONS
        val sourceId = resolved.sourceId ?: return ConnectionRamp.MAX_CONNECTIONS
        val others = (if (watchSession.isWatching(sourceId)) 1 else 0) + openStreams.openOn(sourceId).total
        return (max - others).coerceIn(1, ConnectionRamp.MAX_CONNECTIONS)
    }

    private fun totalOf(resp: Response): Long? =
        resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun request(resolved: Resolved, from: Long, to: Long?): Request =
        Request.Builder().url(resolved.url).header("User-Agent", resolved.userAgent)
            .header("Range", "bytes=$from-${to ?: ""}")
            .build()

    private class Resolved(val url: String, val userAgent: String, val sourceId: Long?, val maxConnections: Int)

    /**
     * The URL + User-Agent this download should fetch. Non-Stalker rows return their stored
     * `streamUrl` untouched (byte-identical to the pre-D-3 behavior). Stalker rows mint a playable
     * URL via `create_link` from the stored cmd — episodes carry `series=<ep>` (the season cmd is
     * shared, looked up from the episode row) — and fetch with the source's MAG-style User-Agent.
     * If the catalog item was pruned by a re-sync mid-queue, falls back to the stored URL (a Stalker
     * cmd will then fail → FAILED, which is the honest outcome).
     */
    private suspend fun resolveTarget(d: DownloadEntity): Resolved {
        // A film's portal id, so a Stalker film links its file as playback does.
        var movieId: String? = null
        val (sourceId, episode) = when (d.mediaType) {
            MediaType.EPISODE -> {
                val ep = seriesDao.getEpisodeById(d.itemId)
                val show = ep?.let { seriesDao.getSeriesById(it.seriesId) }
                show?.sourceId to ep?.episodeNumber
            }
            else -> movieDao.getById(d.itemId)?.also { movieId = it.remoteId }?.sourceId to null
        }
        val source = sourceId?.let { sourceDao.getById(it) }
        val max = source?.maxConnections ?: 0
        if (source == null || !streamUrlResolver.needsResolve(source)) {
            return Resolved(d.streamUrl, HttpClient.DEFAULT_USER_AGENT, sourceId, max)
        }
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        val url = withContext(Dispatchers.IO) { streamUrlResolver.resolve(source, d.streamUrl, vod = true, episode = episode, movieId = movieId) }
        return Resolved(url, ua, sourceId, max)
    }

    /** Keep the real partial byte count — a 90%-then-failed download showing 0 bytes is misleading,
     *  and the partial file IS still on disk (resume/retry can use it). */
    private suspend fun markFailed(id: Long, downloaded: Long, total: Long, failure: DownloadFailure?) {
        downloadDao.updateProgress(
            id, DownloadStatus.FAILED, downloaded.coerceAtLeast(0), total, System.currentTimeMillis(),
            failure?.reason ?: DownloadReason.CONNECTION_LOST, failure?.httpCode,
        )
    }

    private companion object {
        const val TAG = "DownloadManager"

        /** Attempts per download — attempt 2/3 re-resolve the URL and resume via Range (D-3). */
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L

        /** Per attempt, after "account busy": 10 s, then 20 s. */
        const val BUSY_RETRY_DELAY_MS = 10_000L

        const val BUFFER_BYTES = 128 * 1024

        /** How often the pieces' progress is written, and the connections looked at. */
        const val TICK_MS = 500L

        /** How often the writer looks for the next block when it is not there yet. */
        const val WRITE_POLL_MS = 10L

        /** How long each connection count is measured before deciding on one more. */
        const val RAMP_INTERVAL_MS = 3_000L

        /** What one connection fetches at a time; also the step in which the file grows. */
        const val BLOCK_BYTES = 4L * 1024 * 1024

        /** Blocks that may wait in memory beyond one per connection, for a slow block ahead of them. */
        const val AHEAD_BLOCKS = 2

        /** Below this much left, a file is fetched over one connection. */
        const val MIN_SPLIT_BYTES = 16L * 1024 * 1024
    }
}
