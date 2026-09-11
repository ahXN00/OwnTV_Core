package tv.own.owntv.core.parser

import android.os.SystemClock
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.HttpClient
import java.io.InputStream
import java.net.URLEncoder

// --- Parsed Xtream models ---
data class XtCategory(val id: String, val name: String)
data class XtLiveStream(
    val streamId: String, val name: String, val icon: String?, val epgChannelId: String?,
    val categoryId: String?, val num: Int?,
    /** `tv_archive` = 1 → catch-up available; `tv_archive_duration` = days of archive kept. */
    val archive: Boolean = false, val archiveDays: Int = 0,
)
data class XtVod(
    val streamId: String, val name: String, val icon: String?, val rating: Double?, val plot: String?,
    val categoryId: String?, val containerExt: String?, val added: Long?,
)
data class XtSeries(
    val seriesId: String, val name: String, val cover: String?, val plot: String?,
    val rating: Double?, val categoryId: String?, val year: Int?,
    val added: Long? = null, val lastModified: Long? = null,
)
data class XtEpisode(
    val id: String, val seasonNumber: Int, val episodeNumber: Int, val title: String, val containerExt: String?,
    /** When it first aired, epoch ms — the panel's `releasedate`/`air_date`, or its `added` stamp. */
    val airDateMs: Long? = null,
)
data class XtSeriesInfo(val episodes: List<XtEpisode>)

/** What a real request for an `.m3u8` live URL got back — see [XtreamClient.probeHls]. */
sealed interface HlsProbe {
    /** A playlist came back. Proof the panel serves HLS, whatever it advertises. */
    data object Served : HlsProbe
    /** The account was out of connections, so the request never got as far as a format decision. */
    data class Busy(val code: Int) : HlsProbe
    /** The panel answered and the answer was not a playlist. */
    data class NotServed(val reason: HlsNotServedReason) : HlsProbe
    /** No usable answer — network failure, nothing to test with, or an unexpected status. */
    data class Inconclusive(val reason: HlsInconclusiveReason) : HlsProbe
}

sealed interface HlsNotServedReason {
    data object NotPlaylist : HlsNotServedReason
    data class NoEndpoint(val httpCode: Int) : HlsNotServedReason
}

sealed interface HlsInconclusiveReason {
    data class HttpError(val httpCode: Int) : HlsInconclusiveReason
    data class Unexpected(val rawMessage: String) : HlsInconclusiveReason
    data object NoAnswer : HlsInconclusiveReason
    data object NoLiveChannels : HlsInconclusiveReason
    data object DeadTestChannel : HlsInconclusiveReason
}

/** Declaration + real probe. [declared] is null when the panel couldn't be reached at all. */
data class HlsTest(val declared: Boolean?, val probe: HlsProbe)
data class XtEpgEntry(val title: String, val description: String?, val startMs: Long, val stopMs: Long)

/**
 * Xtream Codes `player_api.php` client. Category lists are small and collected to a list; the large
 * stream lists are streamed object-by-object with [android.util.JsonReader] and pushed to a callback,
 * so a 340k-channel response never sits fully in memory.
 */
class XtreamClient(private val http: HttpClient) {

    // --- Categories ---
    suspend fun liveCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_live_categories", onProgress)
    suspend fun vodCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_vod_categories", onProgress)
    suspend fun seriesCategories(s: SourceEntity, onProgress: ((Long, Long?) -> Unit)? = null) = categories(s, "get_series_categories", onProgress)

    private suspend fun categories(s: SourceEntity, action: String, onProgress: ((Long, Long?) -> Unit)? = null): List<XtCategory> {
        val out = ArrayList<XtCategory>()
        http.get(api(s, action), s.userAgent, onProgress, maxAttempts = CATEGORY_MAX_ATTEMPTS) { input ->
            streamObjects(input) { m ->
                val id = m["category_id"] ?: return@streamObjects
                out.add(XtCategory(id, m["category_name"] ?: id))
            }
        }
        return out
    }

    // --- Streams (callback-streamed) ---
    // Each returns true if the full list parsed cleanly, false if the server truncated it mid-stream
    // (issue #15) — the sync uses that to fall back to per-category fetching. [categoryId] filters the
    // request server-side (`&category_id=X`), keeping payloads small enough to dodge the truncation.
    suspend fun streamLive(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtLiveStream) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean =
        streamLive(
            s = s,
            categoryId = categoryId,
            transform = { streamId, name, icon, epgChannelId, itemCategoryId, num, archive, archiveDays ->
                XtLiveStream(streamId, name, icon, epgChannelId, itemCategoryId, num, archive, archiveDays)
            },
            onItem = onItem,
            onProgress = onProgress,
        )

    suspend fun <T : Any> streamLive(
        s: SourceEntity,
        categoryId: String? = null,
        transform: (
            streamId: String,
            name: String,
            icon: String?,
            epgChannelId: String?,
            categoryId: String?,
            num: Int?,
            archive: Boolean,
            archiveDays: Int,
        ) -> T?,
        onItem: suspend (T) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_live_streams", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamItems("get_live_streams", input, { reader -> readLiveStreamAs(reader, transform) }, onItem)
        }
    }

    suspend fun streamVod(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtVod) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean =
        streamVod(
            s = s,
            categoryId = categoryId,
            transform = { streamId, name, icon, rating, plot, itemCategoryId, containerExt, added ->
                XtVod(streamId, name, icon, rating, plot, itemCategoryId, containerExt, added)
            },
            onItem = onItem,
            onProgress = onProgress,
        )

    suspend fun <T : Any> streamVod(
        s: SourceEntity,
        categoryId: String? = null,
        transform: (
            streamId: String,
            name: String,
            icon: String?,
            rating: Double?,
            plot: String?,
            categoryId: String?,
            containerExt: String?,
            added: Long?,
        ) -> T?,
        onItem: suspend (T) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_vod_streams", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamItems("get_vod_streams", input, { reader -> readVodAs(reader, transform) }, onItem)
        }
    }

    suspend fun streamSeries(
        s: SourceEntity,
        categoryId: String? = null,
        onItem: suspend (XtSeries) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean =
        streamSeries(
            s = s,
            categoryId = categoryId,
            transform = { seriesId, name, cover, plot, rating, itemCategoryId, year, added, lastModified ->
                XtSeries(seriesId, name, cover, plot, rating, itemCategoryId, year, added, lastModified)
            },
            onItem = onItem,
            onProgress = onProgress,
        )

    suspend fun <T : Any> streamSeries(
        s: SourceEntity,
        categoryId: String? = null,
        transform: (
            seriesId: String,
            name: String,
            cover: String?,
            plot: String?,
            rating: Double?,
            categoryId: String?,
            year: Int?,
            added: Long?,
            lastModified: Long?,
        ) -> T?,
        onItem: suspend (T) -> Unit,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): Boolean {
        return http.get(api(s, "get_series", categoryParam(categoryId)), s.userAgent, onProgress) { input ->
            streamItems("get_series", input, { reader -> readSeriesAs(reader, transform) }, onItem)
        }
    }

    /**
     * Fetches seasons/episodes for a series (lazy, on open). Panels vary in how they shape the `episodes`
     * field — usually an OBJECT mapping season-number → array of episode objects, but some return a flat
     * ARRAY of episodes (season taken from each episode's own `season` field). Both are handled, so series
     * that showed no episodes on stricter panels now populate.
     */
    suspend fun getSeriesInfo(s: SourceEntity, seriesId: String): XtSeriesInfo {
        val episodes = ArrayList<XtEpisode>()
        http.get(api(s, "get_series_info", "&series_id=$seriesId"), s.userAgent) { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return@use }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "episodes") {
                        when (reader.peek()) {
                            JsonToken.BEGIN_OBJECT -> readEpisodesObject(reader, episodes) // { "1": [ep,…], … }
                            JsonToken.BEGIN_ARRAY -> readEpisodesArray(reader, episodes)    // [ ep, ep, … ]
                            else -> reader.skipValue()
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return XtSeriesInfo(episodes)
    }

    /** `episodes` as `{ season → [episodes] }`. */
    private fun readEpisodesObject(reader: JsonReader, out: MutableList<XtEpisode>) {
        reader.beginObject()
        while (reader.hasNext()) {
            val season = reader.nextName().toIntOrNull() ?: 0
            if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); continue }
            reader.beginArray()
            while (reader.hasNext()) readEpisode(reader, out, season)
            reader.endArray()
        }
        reader.endObject()
    }

    /** `episodes` as a flat `[episodes]` — season comes from each episode's own field. */
    private fun readEpisodesArray(reader: JsonReader, out: MutableList<XtEpisode>) {
        reader.beginArray()
        while (reader.hasNext()) readEpisode(reader, out, 0)
        reader.endArray()
    }

    /** One episode object — tolerant of string/number/null fields and an in-object `season`. */
    private fun readEpisode(reader: JsonReader, out: MutableList<XtEpisode>, seasonFallback: Int) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return }
        var id: String? = null
        var epNum = 0
        var title = ""
        var ext: String? = null
        var season = seasonFallback
        var airDate: Long? = null
        var added: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrNull()
                "episode_num" -> epNum = reader.nextIntOrNull() ?: epNum
                "title" -> title = reader.nextStringOrNull() ?: title
                "container_extension" -> ext = reader.nextStringOrNull()
                "season" -> reader.nextIntOrNull()?.let { if (it > 0) season = it }
                // Panels disagree about where the air date lives and what it is called; the episode
                // object may carry it directly, or bury it in `info`. Whichever arrives first and
                // parses wins, and `added` (when the panel got the file) is the last resort.
                "release_date", "releaseDate", "releasedate", "air_date", "airdate" ->
                    airDate = airDate ?: parseEpisodeDate(reader.nextStringOrNull())
                "added" -> added = reader.nextStringOrNull()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
                "info" -> airDate = airDate ?: readEpisodeInfoDate(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // Keep a missing provider title empty. The Compose episode renderer supplies a localized
        // episode-number fallback; storing English here would freeze the device language in the DB.
        id?.let { out.add(XtEpisode(it, season, epNum, title.trim(), ext, airDate ?: added)) }
    }

    /** The `info` sub-object of an episode — read for its air date, everything else skipped. */
    private fun readEpisodeInfoDate(reader: JsonReader): Long? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        var date: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "release_date", "releaseDate", "releasedate", "air_date", "airdate" ->
                    date = date ?: parseEpisodeDate(reader.nextStringOrNull())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return date
    }

    /**
     * A panel's episode date, as epoch ms. `yyyy-MM-dd` is what they overwhelmingly send (sometimes
     * with a time after it, which is ignored); a bare epoch-seconds number is accepted too. Anything
     * else — and the "0000-00-00" a panel writes for "we don't know" — reads as no date at all.
     */
    private fun parseEpisodeDate(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A bare number is epoch seconds. Everything else is a calendar day, parsed by the same code
        // that reads TMDB's — and at UTC, for the reason spelled out there.
        text.toLongOrNull()?.let { return if (it > 0) it * 1000 else null }
        return tv.own.owntv.core.content.AirDate.parse(text)
    }

    /** Reads a string, coercing numbers and tolerating JSON null. */
    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    /** Reads an int from a number or a numeric string, tolerating null/other. */
    private fun JsonReader.nextIntOrNull(): Int? = when (peek()) {
        JsonToken.NUMBER -> nextInt()
        JsonToken.STRING -> nextString().trim().toIntOrNull()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    /** Reads a long from a number or a numeric string, tolerating null/other. */
    private fun JsonReader.nextLongOrNull(): Long? = when (peek()) {
        JsonToken.NUMBER -> nextLong()
        JsonToken.STRING -> nextString().trim().toLongOrNull()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    /** Reads a double from a number or a numeric string, tolerating null/other. */
    private fun JsonReader.nextDoubleOrNull(): Double? = when (peek()) {
        JsonToken.NUMBER -> nextDouble()
        JsonToken.STRING -> nextString().trim().toDoubleOrNull()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    /**
     * Short EPG (now + a few upcoming programmes) for a single live channel via `get_short_epg`.
     * Titles/descriptions are base64-encoded; timestamps are unix seconds. Returns entries sorted by
     * start time (empty if the panel has no guide for this channel).
     */
    suspend fun getShortEpg(s: SourceEntity, streamId: String, limit: Int = 6): List<XtEpgEntry> {
        val out = ArrayList<XtEpgEntry>()
        http.get(api(s, "get_short_epg", "&stream_id=$streamId&limit=$limit"), s.userAgent) { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return@use }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "epg_listings" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        readEpgListings(reader, out)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun readEpgListings(reader: JsonReader, out: MutableList<XtEpgEntry>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var title = ""
            var desc: String? = null
            var startTs = 0L
            var stopTs = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                when (name) {
                    "title" -> title = decodeBase64(reader.nextString())
                    "description" -> desc = decodeBase64(reader.nextString()).takeIf { it.isNotBlank() }
                    "start_timestamp" -> startTs = reader.nextString().toLongOrNull() ?: 0
                    "stop_timestamp" -> stopTs = reader.nextString().toLongOrNull() ?: 0
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (startTs > 0 && stopTs > startTs) {
                out.add(XtEpgEntry(title.ifBlank { "—" }, desc, startTs * 1000, stopTs * 1000))
            }
        }
        reader.endArray()
    }

    private fun decodeBase64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrDefault(s)

    // --- Stream URL builders ---
    // Live uses the raw MPEG-TS endpoint (.ts) — the universal Xtream live format. The .m3u8/HLS
    // wrapper isn't served by every panel (mpegts-only providers 404 on it, so channels won't load),
    // whereas every panel serves .ts and mpv plays it natively.
    fun liveUrl(s: SourceEntity, streamId: String) = "${base(s)}/live/${s.username}/${s.password}/$streamId.ts"

    /**
     * Catch-up (timeshift) URL for a past programme:
     * `…/timeshift/user/pass/{durationMinutes}/{yyyy-MM-dd:HH-mm}/{streamId}.ts`. The start is formatted
     * in [tz] (UTC by default — EPG timestamps are UTC; some panels expect server-local, hence the knob).
     */
    fun timeshiftUrl(s: SourceEntity, streamId: String, startMs: Long, durationMinutes: Int, tz: java.util.TimeZone = java.util.TimeZone.getTimeZone("UTC"), ext: String = "ts"): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = tz }
        return "${base(s)}/timeshift/${s.username}/${s.password}/$durationMinutes/${fmt.format(java.util.Date(startMs))}/$streamId.$ext"
    }
    fun movieUrl(s: SourceEntity, streamId: String, ext: String?) =
        "${base(s)}/movie/${s.username}/${s.password}/$streamId.${ext ?: "mp4"}"
    fun seriesEpisodeUrl(s: SourceEntity, episodeId: String, ext: String?) =
        "${base(s)}/series/${s.username}/${s.password}/$episodeId.${ext ?: "mp4"}"

    /** Full XMLTV guide for the whole account (all channels) — the bulk EPG used by the guide grid. */
    data class XtAccountDetails(
        val expiryMs: Long? = null,
        val hlsSupported: Boolean = false,
        /** `user_info.max_connections` — simultaneous streams the account may open; 0 when the panel
         *  omits it or reports nonsense. `1` is the one that changes playback (F30). */
        val maxConnections: Int = 0,
        /** `user_info.active_cons` — streams open right now; -1 when the panel omits it. */
        val activeConnections: Int = -1,
        /** `user_info.status` verbatim ("Active", "Expired", "Banned", …); null when omitted. Panels
         *  invent their own words here, so it is shown as-is rather than mapped to an enum. */
        val status: String? = null,
        /** `user_info.auth` — the panel says 0 when it rejected these credentials. */
        val authOk: Boolean = true,
        /** `user_info.is_trial` — "1" on a trial line. */
        val trial: Boolean = false,
    )

    /**
     * Account details (expiry + HLS support) from the bare `player_api.php` call
     * (`user_info.exp_date` and `user_info.allowed_output_formats`).
     *
     * - [XtAccountDetails.expiryMs]: null when the panel reports none/unlimited or payload is malformed.
     * - [XtAccountDetails.hlsSupported]: true only if `"m3u8"` is present in `allowed_output_formats`;
     *   defaults to false when omitted, unlisted, or on network/parse failure.
     */
    suspend fun fetchAccountDetails(s: SourceEntity): XtAccountDetails? = try {
        fetchAccountStatus(s)
    } catch (e: Exception) {
        Log.w(TAG, "fetchAccountDetails failed for source ${s.id}", e)
        null
    }

    /**
     * The same call as [fetchAccountDetails], but **propagating** the failure instead of returning null.
     * "Test source" has to tell "the host never answered" apart from "the host said no", and a null
     * cannot carry that difference.
     */
    suspend fun fetchAccountStatus(s: SourceEntity): XtAccountDetails {
        val u = URLEncoder.encode(s.username.orEmpty(), "UTF-8")
        val p = URLEncoder.encode(s.password.orEmpty(), "UTF-8")
        return http.get("${base(s)}/player_api.php?username=$u&password=$p", s.userAgent) { input ->
            var expSec: Long? = null
            var hlsSupported = false
            var maxConnections = 0
            var activeConnections = -1
            var status: String? = null
            var authOk = true
            var trial = false
            JsonReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                if (r.peek() != JsonToken.BEGIN_OBJECT) return@use
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "user_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "exp_date" -> {
                                    expSec = when (r.peek()) {
                                        JsonToken.STRING -> r.nextString().toLongOrNull()
                                        JsonToken.NUMBER -> r.nextLong()
                                        else -> { r.skipValue(); null }
                                    }
                                }
                                // Panels send this as a string ("1") about as often as a number.
                                "max_connections" -> {
                                    maxConnections = when (r.peek()) {
                                        JsonToken.STRING -> r.nextString().trim().toIntOrNull() ?: 0
                                        JsonToken.NUMBER -> r.nextInt()
                                        else -> { r.skipValue(); 0 }
                                    }.coerceAtLeast(0)
                                }
                                "active_cons" -> {
                                    activeConnections = when (r.peek()) {
                                        JsonToken.STRING -> r.nextString().trim().toIntOrNull() ?: -1
                                        JsonToken.NUMBER -> r.nextInt()
                                        else -> { r.skipValue(); -1 }
                                    }.coerceAtLeast(-1)
                                }
                                "status" -> {
                                    status = if (r.peek() == JsonToken.STRING) {
                                        r.nextString().trim().takeIf { it.isNotEmpty() }
                                    } else { r.skipValue(); null }
                                }
                                // Panels send auth/is_trial as a number or a string, interchangeably.
                                "auth" -> {
                                    authOk = when (r.peek()) {
                                        JsonToken.STRING -> r.nextString().trim() != "0"
                                        JsonToken.NUMBER -> r.nextInt() != 0
                                        else -> { r.skipValue(); true }
                                    }
                                }
                                "is_trial" -> {
                                    trial = when (r.peek()) {
                                        JsonToken.STRING -> r.nextString().trim() == "1"
                                        JsonToken.NUMBER -> r.nextInt() == 1
                                        else -> { r.skipValue(); false }
                                    }
                                }
                                "allowed_output_formats", "user_output_formats" -> {
                                    when (r.peek()) {
                                        JsonToken.BEGIN_ARRAY -> {
                                            r.beginArray()
                                            while (r.hasNext()) {
                                                val fmt = r.nextStringOrNull()
                                                if (fmt?.equals("m3u8", ignoreCase = true) == true) {
                                                    hlsSupported = true
                                                }
                                            }
                                            r.endArray()
                                        }
                                        JsonToken.STRING -> {
                                            val str = r.nextString()
                                            if (str.contains("m3u8", ignoreCase = true)) {
                                                hlsSupported = true
                                            }
                                        }
                                        else -> r.skipValue()
                                    }
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
            }
            XtAccountDetails(
                expiryMs = expSec?.takeIf { it > 0 }?.times(1000),
                hlsSupported = hlsSupported,
                maxConnections = maxConnections,
                activeConnections = activeConnections,
                status = status,
                authOk = authOk,
                trial = trial,
            )
        }
    }

    /** Convenience delegate returning only [XtAccountDetails.expiryMs]. */
    suspend fun accountExpiryMs(s: SourceEntity): Long? = fetchAccountDetails(s)?.expiryMs

    // --- "Test HLS support" (playlist form) ---

    /** Thrown from the first-item callback to abort a streamed list early. Never escapes [firstLiveStreamId]. */
    private class StopStreaming : RuntimeException(null, null, false, false)

    /**
     * The stream id of the FIRST live channel the panel lists. Used to build a probe URL for a playlist
     * that hasn't been synced yet — `get_live_streams` is parsed incrementally, so this aborts after one
     * object instead of downloading a 340k-channel response.
     *
     * Returns the id even if the abort exception is swallowed downstream (the value is captured before
     * the throw), so the early-exit trick can never cost a correct answer.
     */
    suspend fun firstLiveStreamId(s: SourceEntity): String? {
        var first: String? = null
        return try {
            streamLive(s, onItem = { item ->
                if (first == null) {
                    first = item.streamId
                    throw StopStreaming()
                }
            })
            first
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            first ?: run {
                Log.w(TAG, "firstLiveStreamId failed for source ${s.id}", e)
                null
            }
        }
    }

    /**
     * Ask the panel for `…/live/user/pass/{id}.m3u8` and see what actually comes back. A panel's
     * `allowed_output_formats` is a *claim*; plenty serve HLS without listing it and a few list it
     * without serving it, so only this answers the question.
     *
     * Reads just the first few hundred bytes — an HLS playlist starts `#EXTM3U`, an error page doesn't —
     * and never plays anything, so it costs one short request.
     */
    suspend fun probeHls(s: SourceEntity, streamId: String): HlsProbe {
        val url = "${base(s)}/live/${s.username}/${s.password}/$streamId.m3u8"
        return try {
            http.get(url, s.userAgent) { input ->
                val head = ByteArray(HLS_PROBE_BYTES)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (String(head, 0, read, Charsets.UTF_8).contains("#EXTM3U")) {
                    HlsProbe.Served
                } else {
                    HlsProbe.NotServed(HlsNotServedReason.NotPlaylist)
                }
            }
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: HttpClient.HttpStatusException) {
            when (e.code) {
                // Xtream's "too many connections". Every live URL returns this while the account is
                // maxed out, `.ts` included, so it says nothing about HLS either way — not a "no".
                458, 509 -> HlsProbe.Busy(e.code)
                404, 400 -> HlsProbe.NotServed(HlsNotServedReason.NoEndpoint(e.code))
                else -> HlsProbe.Inconclusive(HlsInconclusiveReason.HttpError(e.code))
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeHls failed for source ${s.id}", e)
            HlsProbe.Inconclusive(
                HlsInconclusiveReason.Unexpected(
                    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                ),
            )
        }
    }

    /**
     * Control probe: does this channel play at all over plain MPEG-TS? An MPEG-TS packet starts with
     * the sync byte `0x47`, which an HTML error page never does — one byte settles it.
     *
     * Only used to interpret a *negative* HLS result. Panels routinely list a dead placeholder entry
     * ("### INFO ###") as their first channel, and without this a dead channel would be reported as
     * "your provider doesn't do HLS" when it means nothing of the sort.
     */
    private suspend fun probeTsPlayable(s: SourceEntity, streamId: String): Boolean = try {
        http.get(liveUrl(s, streamId), s.userAgent) { input -> input.read() == 0x47 }
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w(TAG, "probeTsPlayable failed for source ${s.id}", e)
        false
    }

    /**
     * The full "Test HLS support" run: the panel's own declaration, plus a real request for an `.m3u8`
     * channel. [knownStreamId] skips the channel-list fetch when the playlist is already synced.
     */
    suspend fun testHlsSupport(s: SourceEntity, knownStreamId: String? = null): HlsTest {
        val details = fetchAccountDetails(s)
            ?: return HlsTest(declared = null, probe = HlsProbe.Inconclusive(HlsInconclusiveReason.NoAnswer))
        val streamId = knownStreamId?.takeIf { it.isNotBlank() } ?: firstLiveStreamId(s)
            ?: return HlsTest(details.hlsSupported, HlsProbe.Inconclusive(HlsInconclusiveReason.NoLiveChannels))
        val probe = probeHls(s, streamId)
        // A "no" is only trustworthy once the same channel is known to play over MPEG-TS. If it doesn't,
        // the test channel is the problem, not HLS — say so instead of recording a false verdict.
        if (probe is HlsProbe.NotServed && !probeTsPlayable(s, streamId)) {
            return HlsTest(
                details.hlsSupported,
                HlsProbe.Inconclusive(HlsInconclusiveReason.DeadTestChannel),
            )
        }
        return HlsTest(details.hlsSupported, probe)
    }

    fun xmltvUrl(s: SourceEntity): String {
        val u = URLEncoder.encode(s.username.orEmpty(), "UTF-8")
        val p = URLEncoder.encode(s.password.orEmpty(), "UTF-8")
        return "${base(s)}/xmltv.php?username=$u&password=$p"
    }

    // --- helpers ---
    private fun base(s: SourceEntity) = s.url.trimEnd('/')

    private fun api(s: SourceEntity, action: String, extra: String = ""): String {
        val u = URLEncoder.encode(s.username.orEmpty(), "UTF-8")
        val p = URLEncoder.encode(s.password.orEmpty(), "UTF-8")
        return "${base(s)}/player_api.php?username=$u&password=$p&action=$action$extra"
    }

    /** `&category_id=X` query suffix (server-side filter), or "" when fetching everything. */
    private fun categoryParam(categoryId: String?): String =
        categoryId?.takeIf { it.isNotBlank() }?.let { "&category_id=$it" } ?: ""

    /** Category lists are small, so keeping the map reader here keeps that path simple. */
    private suspend fun streamObjects(input: InputStream, onObject: suspend (Map<String, String?>) -> Unit): Boolean =
        streamItems("objects", input, ::readObject, onObject)

    private suspend fun <T> streamItems(
        label: String,
        input: InputStream,
        readItem: (JsonReader) -> T?,
        onItem: suspend (T) -> Unit,
    ): Boolean =
        // Per-item timing costs millions of elapsedRealtime() syscalls on a 170k+ catalog, so the
        // detailed metrics only run when the tag is debuggable (`setprop log.tag.XtreamClient DEBUG`).
        if (DEBUG) {
            streamArray(label, input) { reader, metrics ->
                val parseStart = SystemClock.elapsedRealtime()
                val item = readItem(reader)
                metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                if (item != null) {
                    val callbackStart = SystemClock.elapsedRealtime()
                    onItem(item)
                    metrics.callbackMs += SystemClock.elapsedRealtime() - callbackStart
                }
            }
        } else {
            streamArray(label, input) { reader, _ ->
                readItem(reader)?.let { onItem(it) }
            }
        }

    /**
     * Streams a top-level JSON array. Returns true if the array parsed to its end, false if the server
     * truncated it mid-stream (issue #15). A failure before any item is read is fatal and rethrown.
     */
    private suspend fun streamArray(label: String, input: InputStream, readItem: suspend (JsonReader, StreamMetrics) -> Unit): Boolean {
        val ctx = currentCoroutineContext()
        val startedAt = SystemClock.elapsedRealtime()
        var lastLogAt = startedAt
        var lastParseOrReadMs = 0L
        var lastCallbackMs = 0L
        var count = 0
        val metrics = StreamMetrics()
        if (DEBUG) Log.d(TAG, "streamArray start label=$label")
        try {
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    // Some servers return {} or an error object instead of an array.
                    reader.skipValue()
                    Log.d(TAG, "streamArray non-array label=$label totalMs=${SystemClock.elapsedRealtime() - startedAt}")
                    return true
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    ctx.ensureActive()
                    readItem(reader, metrics)
                    count++
                    if (DEBUG && count % STREAM_LOG_ITEM_STEP == 0) {
                        val now = SystemClock.elapsedRealtime()
                        val parseOrReadDelta = metrics.parseOrReadMs - lastParseOrReadMs
                        val callbackDelta = metrics.callbackMs - lastCallbackMs
                        Log.d(
                            TAG,
                            "streamArray parsed count=$count label=$label deltaMs=${now - lastLogAt} " +
                                "parseOrReadMs=$parseOrReadDelta callbackMs=$callbackDelta " +
                                "totalParseOrReadMs=${metrics.parseOrReadMs} totalCallbackMs=${metrics.callbackMs} " +
                                "totalMs=${now - startedAt}",
                        )
                        lastLogAt = now
                        lastParseOrReadMs = metrics.parseOrReadMs
                        lastCallbackMs = metrics.callbackMs
                    }
                }
                reader.endArray()
            }
            Log.i(TAG, "streamArray end count=$count label=$label totalMs=${SystemClock.elapsedRealtime() - startedAt}")
            return true
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            // Truncated mid-stream (JsonReader reports "Unterminated string …"). Keep everything parsed
            // so far; only a failure before ANY item is read is fatal.
            if (count == 0) throw e
            Log.w(
                TAG,
                "Stream truncated after $count items label=$label — partial list kept " +
                    "parseOrReadMs=${metrics.parseOrReadMs} callbackMs=${metrics.callbackMs} " +
                    "totalMs=${SystemClock.elapsedRealtime() - startedAt}",
                e,
            )
            return false
        }
    }

    private class StreamMetrics {
        var parseOrReadMs = 0L
        var callbackMs = 0L
    }

    private companion object {
        const val TAG = "XtreamClient"
        const val STREAM_LOG_ITEM_STEP = 10_000
        /** Detailed per-item parse metrics — off in normal runs, enabled via `setprop log.tag.XtreamClient DEBUG`. */
        val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        /** Category lists are tiny; a retry on a connect/DNS blip saves the whole sync phase. */
        const val CATEGORY_MAX_ATTEMPTS = 3
        /** Enough of an HLS response to see `#EXTM3U` (or that an HTML error page came instead). */
        const val HLS_PROBE_BYTES = 512
    }

    private fun <T : Any> readLiveStreamAs(
        reader: JsonReader,
        transform: (
            streamId: String,
            name: String,
            icon: String?,
            epgChannelId: String?,
            categoryId: String?,
            num: Int?,
            archive: Boolean,
            archiveDays: Int,
        ) -> T?,
    ): T? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var streamId: String? = null
        var name = ""
        var icon: String? = null
        var epgChannelId: String? = null
        var categoryId: String? = null
        var num: Int? = null
        var archive = false
        var archiveDays = 0

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stream_id" -> streamId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "stream_icon" -> icon = reader.nextScalarStringOrNull()
                "epg_channel_id" -> epgChannelId = reader.nextScalarStringOrNull()?.takeIf { it.isNotBlank() }
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "num" -> num = reader.nextIntOrNull()
                "tv_archive" -> archive = (reader.nextIntOrNull() ?: 0) > 0
                "tv_archive_duration" -> archiveDays = reader.nextIntOrNull() ?: 0
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return streamId?.let { transform(it, name, icon, epgChannelId, categoryId, num, archive, archiveDays) }
    }

    private fun <T : Any> readVodAs(
        reader: JsonReader,
        transform: (
            streamId: String,
            name: String,
            icon: String?,
            rating: Double?,
            plot: String?,
            categoryId: String?,
            containerExt: String?,
            added: Long?,
        ) -> T?,
    ): T? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var streamId: String? = null
        var name = ""
        var icon: String? = null
        var rating: Double? = null
        var plot: String? = null
        var description: String? = null
        var categoryId: String? = null
        var containerExt: String? = null
        var added: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stream_id" -> streamId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "stream_icon" -> icon = reader.nextScalarStringOrNull()
                "rating" -> rating = reader.nextDoubleOrNull()
                "plot" -> plot = reader.nextScalarStringOrNull()
                "description" -> description = reader.nextScalarStringOrNull()
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "container_extension" -> containerExt = reader.nextScalarStringOrNull()
                "added" -> added = reader.nextLongOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val cleanPlot = plot?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
        return streamId?.let { transform(it, name, icon, rating, cleanPlot, categoryId, containerExt, added) }
    }

    private fun <T : Any> readSeriesAs(
        reader: JsonReader,
        transform: (
            seriesId: String,
            name: String,
            cover: String?,
            plot: String?,
            rating: Double?,
            categoryId: String?,
            year: Int?,
            added: Long?,
            lastModified: Long?,
        ) -> T?,
    ): T? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var seriesId: String? = null
        var name = ""
        var cover: String? = null
        var plot: String? = null
        var rating: Double? = null
        var categoryId: String? = null
        var year: Int? = null
        var added: Long? = null
        var lastModified: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "series_id" -> seriesId = reader.nextScalarStringOrNull()
                "name" -> name = reader.nextScalarStringOrNull().orEmpty()
                "cover" -> cover = reader.nextScalarStringOrNull()
                "plot" -> plot = reader.nextScalarStringOrNull()
                "rating" -> rating = reader.nextDoubleOrNull()
                "category_id" -> categoryId = reader.nextScalarStringOrNull()
                "year" -> year = reader.nextIntOrNull()
                "added" -> added = reader.nextScalarStringOrNull()?.toLongOrNull()
                "last_modified" -> lastModified = reader.nextScalarStringOrNull()?.toLongOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return seriesId?.let {
            transform(
                it,
                name,
                cover,
                plot,
                rating,
                categoryId,
                year,
                added,
                lastModified,
            )
        }
    }

    private fun readObject(reader: JsonReader): Map<String, String?> {
        val map = HashMap<String, String?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    map[name] = null
                }
                JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> reader.skipValue()
                else -> map[name] = reader.nextString()
            }
        }
        reader.endObject()
        return map
    }

    private fun JsonReader.nextScalarStringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            null
        }
    }
}
