package tv.own.owntv.core.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.DownloadDao
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadReason
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.storage.MediaRoot
import tv.own.owntv.core.storage.MediaTarget
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.core.settings.SettingsRepository

/** Free/total bytes of the volume backing the download root. */
data class DownloadStorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    /** The chosen folder is missing (USB removed, permission withdrawn), so the app's own folder is in use. */
    val usingFallback: Boolean = false,
)

/**
 * Downloads movies & series episodes for offline playback. Files go under the user-chosen
 * download folder, organised as `Movies/<name>.<ext>` and `Series/<show>/Season N/<episode>.<ext>`.
 *
 * This is the queue-control half only: it writes [DownloadDao] rows and hands the actual transfers
 * to [DownloadWorker]/[DownloadEngine], which run in a foreground service and therefore survive the
 * user leaving the app. [DownloadDao] remains the single source of truth, and
 * downloads still run strictly one at a time (each one possibly over several connections).
 */
class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val settings: SettingsRepository,
    private val engine: DownloadEngine,
    private val watchSession: tv.own.owntv.core.live.WatchSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Anything left QUEUED — or RUNNING when the process was killed — is picked up again.
        engine.markQueued()
        scope.launch { DownloadWorker.kick(context, settings.downloadsWifiOnlyNow()) }
        // Turning "Wi-Fi only" on has to reach a transfer that is already running on mobile data, and
        // the queue's own work is KEEP — it would keep the constraint it was enqueued with. So the
        // switch replaces the work instead: the running row stays RUNNING, and the replacement worker
        // resumes it from the partial file once the network it is now waiting for arrives.
        scope.launch {
            settings.downloadsWifiOnly.drop(1).distinctUntilChanged().collect { wifiOnly ->
                DownloadWorker.kick(context, wifiOnly, replace = true)
            }
        }
        // A download waiting for the user to stop watching a one-stream playlist starts once they have.
        scope.launch {
            var before = emptySet<Long>()
            watchSession.watching.collect { now ->
                if ((before - now).isNotEmpty()) kick()
                before = now
            }
        }
    }

    fun observe(profileId: Long): Flow<List<DownloadEntity>> = downloadDao.observeForProfile(profileId)

    /** Episode downloads for one series (poster-panel aggregate status). */
    fun observeForSeries(seriesId: Long): Flow<List<DownloadEntity>> = downloadDao.observeForSeries(seriesId)

    /** Free/total space of the volume holding the current download root (for the Downloads storage bar). */
    suspend fun storageInfo(): DownloadStorageInfo = withContext(Dispatchers.IO) {
        val configured = settings.downloadRoot.first()
        val space = runCatching { MediaRoot.of(context, configured).space() }
            .getOrNull() ?: MediaRoot.Path(StorageAccess.defaultRoot(context)).space()
        DownloadStorageInfo(
            freeBytes = space.freeBytes,
            totalBytes = space.totalBytes,
            usingFallback = runCatching { MediaRoot.isFallback(context, configured) }.getOrDefault(false),
        )
    }

    /**
     * Queue a download into `<root>/<relativeDir>/<fileName>`.
     *
     * The destination is made now rather than when the transfer starts, because that is the moment
     * the row's `filePath` has to name something real — a SAF document has to be created before it
     * has a URI at all, where a path could simply be written down.
     *
     * A chosen folder that is missing right now falls back to the app's own folder ([MediaRoot.of]).
     * A root that still cannot produce a file — the volume went away mid-way — still gets a row, with no path. The engine then fails it in the ordinary way and the user sees
     * a failed download saying so, which is what happened before this could fail at all. Queuing
     * nothing would leave the Download button looking broken.
     *
     * A profile with downloads switched off queues nothing: the apps hide every Download action for
     * it, and this is the guard behind them, so a path that was missed still cannot fill the box.
     */
    fun enqueue(
        profileId: Long, mediaType: MediaType, itemId: Long, title: String, posterUrl: String?,
        streamUrl: String, relativeDir: String, fileName: String,
    ) {
        scope.launch {
            if (!settings.downloadsAllowed(profileId).first()) return@launch
            val root = MediaRoot.of(context, settings.downloadRoot.first())
            val target = root.child(relativeDir, fileName)
            if (target == null) {
                android.util.Log.w(TAG, "cannot create download target in ${root.stored} for $fileName")
            }
            downloadDao.upsert(
                DownloadEntity(
                    profileId = profileId, mediaType = mediaType, itemId = itemId, title = title,
                    posterUrl = posterUrl, streamUrl = streamUrl, filePath = target?.stored,
                    status = DownloadStatus.QUEUED,
                ),
            )
            kick()
        }
    }

    fun retry(download: DownloadEntity) {
        scope.launch {
            // Stop a still-running attempt BEFORE deleting its file — otherwise the writer keeps
            // streaming into the unlinked file and "completes" a download that no longer exists.
            engine.suspendTransfer(download.id)
            try {
                // Start fresh. Truncated rather than deleted: dropping a SAF document would throw
                // away the entry the user's folder grant points at, and the retry would then have
                // nowhere to write at all. For an ordinary file the two are the same thing.
                MediaTarget.of(context, download.filePath)?.truncate()
                downloadDao.updateProgress(download.id, DownloadStatus.QUEUED, 0, download.totalBytes, System.currentTimeMillis(), null, null)
            } finally {
                engine.release(download.id)
            }
            kick()
        }
    }

    /** Stop the running download but keep the partial file so it can resume. */
    fun pause(download: DownloadEntity) {
        scope.launch { pauseNow(download.id, null) }
    }

    /**
     * Pause [id] because the user is watching on a playlist that allows one stream
     * ([DownloadWatchGuard]). The row says so; it does not resume by itself.
     */
    internal suspend fun pauseForWatching(id: Long) = pauseNow(id, DownloadReason.PAUSED_FOR_WATCHING)

    private suspend fun pauseNow(id: Long, reason: DownloadReason?) {
        // Wait for the writer to actually stop before recording how far it got — cancel()
        // alone returns while the transfer is still appending bytes.
        engine.suspendTransfer(id)
        try {
            val d = downloadDao.getById(id) ?: return
            downloadDao.updateProgress(d.id, DownloadStatus.PAUSED, bytesOf(d), d.totalBytes, System.currentTimeMillis(), reason, null)
        } finally {
            engine.release(id)
        }
    }

    /** What is really written: the file is always a clean prefix of the download. */
    private fun bytesOf(d: DownloadEntity): Long =
        DownloadResume.bytesOnDisk(MediaTarget.of(context, d.filePath), d.downloadedBytes)

    /** Continue a paused download from where it stopped (HTTP Range). */
    fun resume(download: DownloadEntity) {
        scope.launch { resumeNow(download.id) }
    }

    internal suspend fun resumeNow(id: Long) {
        val d = downloadDao.getById(id) ?: return
        downloadDao.updateProgress(d.id, DownloadStatus.QUEUED, bytesOf(d), d.totalBytes, System.currentTimeMillis(), null, null)
        kick()
    }

    fun delete(download: DownloadEntity) {
        scope.launch {
            engine.suspendTransfer(download.id)
            try {
                MediaTarget.of(context, download.filePath)?.delete()
                downloadDao.delete(download)
            } finally {
                engine.release(download.id)
            }
            kick()
        }
    }

    private fun kick() {
        engine.markQueued()
        scope.launch { DownloadWorker.kick(context, settings.downloadsWifiOnlyNow()) }
    }

    private companion object {
        const val TAG = "DownloadManager"
    }
}
