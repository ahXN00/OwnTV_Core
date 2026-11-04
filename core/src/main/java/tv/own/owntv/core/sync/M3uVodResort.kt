package tv.own.owntv.core.sync

import android.util.Log
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.ProgressiveVideoUrl
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * Re-sorts, once per install, the M3U playlists saved before untagged video-file entries were read as
 * movies ([ProgressiveVideoUrl]). Entries are sorted only when a playlist is downloaded, so after the
 * update those films stay in Live TV until the source's next refresh, which can be a day away.
 *
 * It is the source's ordinary sync, so everything a refresh keeps is kept (local ids, favourites,
 * order, guide matches) and only the films leave Live TV, through [M3uReclassifiedLive]. A source is
 * left alone unless it is an M3U playlist with a live row whose URL is a video file, and also when its
 * Auto refresh is Off: that user decides when it downloads. The job carries [AutoRefresh.REASON], so
 * it waits while something is playing.
 */
class M3uVodResort(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val importFinalizer: ImportFinalizer,
) {
    suspend fun runOnce() {
        if (settings.m3uVodResortChecked.first()) return
        runCatching {
            val modes = settings.playlistAutoRefresh.first()
            sourceDao.observeAll().first().forEach { source ->
                val mode = (modes[source.id] ?: PlaylistRefresh.OFF).mode
                if (!needsResort(source.type, mode) { candidateUrls(source.id) }) return@forEach
                val counts = importFinalizer.contentCounts(source.id)
                Log.i(TAG, "m3uVodResort sourceId=${source.id} — video files in Live TV, re-syncing")
                catalogSyncScheduler.enqueueSync(
                    source.id,
                    reason = AutoRefresh.REASON,
                    contentTypes = SyncContentTypes.enabledOf(source),
                    baseItemCount = counts.channels + counts.movies + counts.series,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }.onFailure { Log.w(TAG, "m3uVodResort check failed", it) }
        // Marked regardless, like the guide refill: a failed check must not rerun on every launch.
        settings.markM3uVodResortChecked()
    }

    /** A few live URLs per video extension; one small indexed scan each, run once per install. */
    private suspend fun candidateUrls(sourceId: Long): List<String> =
        ProgressiveVideoUrl.EXTENSIONS.flatMap { channelDao.streamUrlsLike(sourceId, "%.$it%", CANDIDATES) }

    companion object {
        private const val TAG = "M3uVodResort"

        /** Enough candidates that an odd URL like `/movies.mp4/live/1.ts` cannot hide a real film. */
        private const val CANDIDATES = 20

        /** Whether a saved source needs the re-sort. [liveUrls] is read only for an M3U source that auto-refreshes. */
        internal suspend fun needsResort(
            type: SourceType,
            refresh: PlaylistAutoRefresh,
            liveUrls: suspend () -> List<String>,
        ): Boolean = type == SourceType.M3U && refresh != PlaylistAutoRefresh.OFF && liveUrls().any(ProgressiveVideoUrl::matches)
    }
}
