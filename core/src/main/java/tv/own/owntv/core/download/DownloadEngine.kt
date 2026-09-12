package tv.own.owntv.core.download

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** What the foreground notification shows about the transfer currently running. */
data class DownloadProgress(val title: String, val downloadedBytes: Long, val totalBytes: Long)

/**
 * The transfer half of downloads, split out of [DownloadManager] so it can run inside
 * [DownloadWorker] — a foreground service — instead of an app-scoped coroutine that Android kills
 * as soon as the user leaves OwnTV (audit item DL1).
 *
 * [DownloadDao] stays the single source of truth: the engine holds no queue of its own, it just
 * drains whatever rows are QUEUED/RUNNING, one at a time, until none are left.
 */
class DownloadEngine(
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val streamUrlResolver: StreamUrlResolver,
    private val activityTracker: DownloadActivityTracker,
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

    private suspend fun runDownload(id: Long, onProgress: (DownloadProgress) -> Unit) {
        val d = downloadDao.getById(id) ?: return
        val file = d.filePath?.let { File(it) }
        // A download folder on removable storage can simply be gone (card pulled, USB unplugged).
        // Fail loudly rather than silently re-homing gigabytes onto internal storage.
        if (file == null || !ensureWritable(file)) {
            android.util.Log.w(TAG, "download target unavailable id=$id path=${d.filePath}")
            markFailed(id, 0, d.totalBytes)
            return
        }
        // Resume whenever a partial file is on disk — including a RUNNING row left behind by a
        // process death — instead of only after an explicit pause. retry() deletes the file first,
        // so a deliberate restart still starts from zero.
        if (file.exists() && file.length() == 0L) runCatching { file.delete() }
        // Attempt loop (plan D-3): a Stalker `create_link` URL dies after ~2-4 h, so a long download
        // can fail mid-stream. Each attempt re-resolves a FRESH URL from the stored cmd and resumes
        // with an HTTP Range from the bytes already written; a server that ignores Range restarts the
        // file from 0. M3U/Xtream pass through the resolver unchanged, so for them the loop is just a
        // plain transient-error retry with the same URL.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val done = try {
                attemptDownload(id, d, file, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed id=$id: ${e.message}")
                false
            }
            if (done) return
            if (!currentCoroutineContext().isActive) return // paused/deleted — status already set by caller
            if (attempt >= MAX_ATTEMPTS) { markFailed(id, file.length(), d.totalBytes); return }
            delay(RETRY_DELAY_MS * attempt)
        }
    }

    /** True when the file's directory exists and is writable — i.e. the volume is actually mounted. */
    private fun ensureWritable(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists()) runCatching { parent.mkdirs() }
        return parent.isDirectory && parent.canWrite()
    }

    /** One download attempt. Returns true when the file completed; false/throws = retryable failure. */
    private suspend fun attemptDownload(
        id: Long,
        d: DownloadEntity,
        file: File,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean {
        // Resolve at download-start time, fresh every attempt — the row keeps the stored cmd as the
        // item's identity; only this attempt's HTTP request sees the minted URL. A resolve failure
        // (portal down / bad auth) is a retryable attempt like any HTTP failure.
        val (url, userAgent) = resolveTarget(d)
        val existing = DownloadResume.resumeOffset(file)
        val rb = Request.Builder().url(url).header("User-Agent", userAgent)
        if (existing > 0) rb.header("Range", "bytes=$existing-")
        client.newCall(rb.build()).execute().use { resp ->
            // 416 on a resume = our Range starts at/after the end of the resource — the file already
            // holds every byte the server has (a completed download whose COMPLETED write was lost to
            // a crash/disconnect). Without this, the retry loop would mark a finished file FAILED.
            if (resp.code == 416 && existing > 0) {
                downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = existing, totalBytes = existing, updatedAt = System.currentTimeMillis()))
                return true
            }
            val body = resp.body
            if (!resp.isSuccessful) return false
            val append = resp.code == 206 && existing > 0 // server honoured the Range
            val total = DownloadResume.expectedTotal(append, existing, body.contentLength())
            var done = if (append) existing else 0L
            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, System.currentTimeMillis())
            onProgress(DownloadProgress(d.title, done, total))
            body.byteStream().use { input ->
                java.io.FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var lastTick = 0L
                    while (true) {
                        if (!currentCoroutineContext().isActive) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val t = System.currentTimeMillis()
                        if (t - lastTick > 500) {
                            downloadDao.updateProgress(id, DownloadStatus.RUNNING, done, total, t)
                            onProgress(DownloadProgress(d.title, done, total))
                            lastTick = t
                        }
                    }
                }
            }
            val size = file.length()
            downloadDao.upsert(d.copy(status = DownloadStatus.COMPLETED, downloadedBytes = size, totalBytes = size, updatedAt = System.currentTimeMillis()))
            return true
        }
    }

    /**
     * The URL + User-Agent this download should fetch. Non-Stalker rows return their stored
     * `streamUrl` untouched (byte-identical to the pre-D-3 behavior). Stalker rows mint a playable
     * URL via `create_link` from the stored cmd — episodes carry `series=<ep>` (the season cmd is
     * shared, looked up from the episode row) — and fetch with the source's MAG-style User-Agent.
     * If the catalog item was pruned by a re-sync mid-queue, falls back to the stored URL (a Stalker
     * cmd will then fail → FAILED, which is the honest outcome).
     */
    private suspend fun resolveTarget(d: DownloadEntity): Pair<String, String> {
        val (sourceId, episode) = when (d.mediaType) {
            MediaType.EPISODE -> {
                val ep = seriesDao.getEpisodeById(d.itemId)
                val show = ep?.let { seriesDao.getSeriesById(it.seriesId) }
                show?.sourceId to ep?.episodeNumber
            }
            else -> movieDao.getById(d.itemId)?.sourceId to null
        }
        val source = sourceId?.let { sourceDao.getById(it) }
        if (source == null || !streamUrlResolver.needsResolve(source)) {
            return d.streamUrl to HttpClient.DEFAULT_USER_AGENT
        }
        val ua = source.userAgent?.takeIf { it.isNotBlank() } ?: StalkerClient.DEFAULT_MAG_USER_AGENT
        return streamUrlResolver.resolve(source, d.streamUrl, vod = true, episode = episode) to ua
    }

    /** Keep the real partial byte count — a 90%-then-failed download showing 0 bytes is misleading,
     *  and the partial file IS still on disk (resume/retry can use it). */
    private suspend fun markFailed(id: Long, downloaded: Long, total: Long) {
        downloadDao.updateProgress(id, DownloadStatus.FAILED, downloaded.coerceAtLeast(0), total, System.currentTimeMillis())
    }

    private companion object {
        const val TAG = "DownloadManager"

        /** Attempts per download — attempt 2/3 re-resolve the URL and resume via Range (D-3). */
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
