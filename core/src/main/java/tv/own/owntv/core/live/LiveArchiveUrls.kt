package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StreamUrlResolver

private const val LOG_TAG = "OwnTVHome"

/**
 * The archive URLs Live TV builds: one for a picked catch-up programme, one for a live-rewind offset.
 * Both are pure construction — no state, no playback.
 */
class LiveArchiveUrls(
    private val sourceDao: SourceDao,
    private val xtreamClient: XtreamClient,
    private val streamUrlResolver: StreamUrlResolver,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {
    /** The archive URL for [programme] on [ch], or null when it can't be built. */
    suspend fun forProgramme(ch: ChannelEntity, programme: EpgProgrammeEntity): String? =
        withContext(Dispatchers.IO) {
            val source = sourceDao.getById(ch.sourceId) ?: return@withContext null
            // Stalker archive URLs are minted per-play via create_link; the others
            // are pure string templates handled by CatchupUrl.
            if (source.type == SourceType.STALKER) {
                ch.remoteId?.let { rid ->
                    runCatching { streamUrlResolver.resolveCatchup(source, rid, programme.startMs, programme.stopMs) }
                        .onFailure { Log.w(LOG_TAG, "Stalker catch-up resolve failed channelId=${ch.id}", it) }
                        .getOrNull()
                }
            } else {
                tv.own.owntv.core.epg.CatchupUrl.forSource(ch, programme, source, settings.resolveCatchupTimeZone(source), xtreamClient)
            }
        }

    /**
     * A Stalker archive URL is a single-use `create_link`, so a Retry or a stall reconnect that
     * replays it gets nothing. For a Stalker channel this mints a fresh one for the same [programme];
     * null for every other source, whose archive URL can simply be replayed.
     */
    suspend fun reconnectFor(ch: ChannelEntity, programme: EpgProgrammeEntity): ReconnectUrlProvider? {
        val type = withContext(Dispatchers.IO) { sourceDao.getById(ch.sourceId)?.type }
        return if (type == SourceType.STALKER) ReconnectUrlProvider { forProgramme(ch, programme) } else null
    }

    /** An archive URL, the wall-clock instant it really starts at, and — for Stalker — how to mint a
     *  fresh URL for the same start (see [reconnectFor]). */
    data class Timeshift(val url: String, val startMs: Long, val reconnect: ReconnectUrlProvider? = null)

    /**
     * The rolling-archive URL for watching [ch] [offsetSec] behind the live edge, asked to start at
     * [startMs]. The returned start is the one the archive will really begin at, and the one every
     * "behind live" counter must be measured from: an Xtream timeshift start is written to the
     * minute, so the panel serves from the minute's start — up to 59 s earlier than asked.
     */
    suspend fun forTimeshift(
        ch: ChannelEntity,
        source: tv.own.owntv.core.database.entity.SourceEntity,
        startMs: Long,
        offsetSec: Int,
        tz: java.util.TimeZone,
    ): Timeshift? {
        val onTheMinute = startsOnTheMinute(source.type, ch.catchupType, ch.catchupSource)
        val start = if (onTheMinute) startMs - Math.floorMod(startMs, 60_000L) else startMs
        val reconnect = if (source.type == SourceType.STALKER) {
            ReconnectUrlProvider { timeshiftUrl(ch, source, start, offsetSec, tz) }
        } else {
            null
        }
        return timeshiftUrl(ch, source, start, offsetSec, tz)?.let { Timeshift(it, start, reconnect) }
    }

    private suspend fun timeshiftUrl(
        ch: ChannelEntity,
        source: tv.own.owntv.core.database.entity.SourceEntity,
        startMs: Long,
        offsetSec: Int,
        tz: java.util.TimeZone,
    ): String? {
        val durationMin = (offsetSec / 60 + 5).coerceAtLeast(1) // rewound window + buffer to play up to live
        return when (source.type) {
            SourceType.XTREAM -> ch.remoteId?.let {
                // Always `.ts` — live rewind reads the same timeshift server as catch-up, and "Prefer
                // HLS" deliberately does not apply to it. See [CatchupUrl.forSource] for why: the
                // archive has no HLS repackager, so `.m3u8` there answers with an error page.
                xtreamClient.timeshiftUrl(source, it, startMs, durationMin, tz, "ts")
            }
            // No HLS swap here: [resolveStreamUrl] is Xtream-only, so it would be a no-op on M3U.
            SourceType.M3U -> tv.own.owntv.core.epg.CatchupUrl.forM3u(
                ch.streamUrl,
                ch.catchupType,
                ch.catchupSource,
                startMs,
                startMs + durationMin * 60_000L,
                tz,
                ch.catchupCorrectionMin,
            )
            // Stalker rewind = the same per-play archive create_link as catch-up.
            SourceType.STALKER -> ch.remoteId?.let { rid ->
                runCatching { streamUrlResolver.resolveCatchup(source, rid, startMs, startMs + durationMin * 60_000L) }
                    .onFailure { Log.w(LOG_TAG, "Stalker rewind resolve failed channelId=${ch.id}", it) }
                    .getOrNull()
            }
            else -> null
        }
    }

    internal companion object {
        /** Xtream's timeshift start, and that of an M3U `xc` channel without a template of its own (a
         *  template wins in `CatchupUrl.forM3u`), is written `yyyy-MM-dd:HH-mm`. */
        fun startsOnTheMinute(type: SourceType, catchupType: String?, catchupSource: String?): Boolean =
            type == SourceType.XTREAM ||
                (type == SourceType.M3U && catchupSource.isNullOrBlank() &&
                    catchupType?.trim()?.lowercase().let { it == "xc" || it == "xtream" })
    }
}
