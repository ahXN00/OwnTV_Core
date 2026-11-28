package tv.own.owntv.core.skip

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * TheIntroDB's API (`api.theintrodb.org/v4`), called straight from this device.
 *
 * Its terms license the data to client-side apps only: no server may sit in between, so this never
 * goes through a Worker. Only a TMDB id, season, episode and the file's length leave the device —
 * plus, when the user entered one, their own key. With no key the service allows 5 000 lookups a day
 * per network; a key raises that to 100 000 for that account.
 */
class TheIntroDbClient(private val http: OkHttpClient) {

    /** The daily lookup allowance, from the `X-UsageLimit-*` headers every answer carries. */
    data class Quota(val limit: Int, val remaining: Int, val resetSeconds: Long, val atMs: Long)

    sealed interface Lookup {
        data class Found(val segments: List<RawSkipSegment>) : Lookup
        /** The service knows the title but has no times for this film or episode yet. */
        data object NotFound : Lookup
        /** The service does not list the title at all. */
        data object Unlisted : Lookup
        /** Over the limit: ask again no earlier than [untilMs]. */
        data class Limited(val untilMs: Long) : Lookup
        data object Failed : Lookup
    }

    sealed interface KeyCheck {
        data class Valid(val submissions: Int, val accepted: Int) : KeyCheck
        data object Invalid : KeyCheck
        data object Unreachable : KeyCheck
    }

    /** [tmdbId] is the film's, or the show's for an episode; season and episode are in TMDB's numbering. */
    suspend fun media(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        durationMs: Long?,
        apiKey: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Lookup, Quota?> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(BASE).append("/media?tmdb_id=").append(tmdbId)
            if (season != null && episode != null) append("&season=").append(season).append("&episode=").append(episode)
            if (durationMs != null && durationMs > 0) append("&duration_ms=").append(durationMs)
        }
        try {
            http.newCall(request(url, apiKey)).execute().use { response ->
                val quota = quotaOf(response, nowMs)
                val lookup = when {
                    response.code == 404 ->
                        if (runCatching { JSONObject(response.body.string()).optString("code") }.getOrNull() == "media_not_found") Lookup.Unlisted
                        else Lookup.NotFound
                    response.code == 429 -> Lookup.Limited(nowMs + retryAfterMs(response))
                    !response.isSuccessful -> {
                        Log.w(TAG, "lookup answered HTTP ${response.code}")
                        Lookup.Failed
                    }
                    else -> {
                        val segments = SkipSegments.parse(response.body.string())
                        if (segments.isEmpty()) Lookup.NotFound else Lookup.Found(segments)
                    }
                }
                lookup to quota
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "lookup failed: ${e.message}")
            Lookup.Failed to null
        }
    }

    /** Whether [apiKey] is accepted, with the account's submission counts. Only `/user/stats` can tell:
     *  `/media` silently treats a wrong key as no key. */
    suspend fun checkKey(apiKey: String): KeyCheck = withContext(Dispatchers.IO) {
        try {
            http.newCall(request("$BASE/user/stats", apiKey)).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> KeyCheck.Invalid
                    !response.isSuccessful -> KeyCheck.Unreachable
                    else -> {
                        val o = JSONObject(response.body.string())
                        if (o.optString("error").isNotBlank()) KeyCheck.Invalid
                        else KeyCheck.Valid(submissions = o.optInt("total", 0), accepted = o.optInt("accepted", 0))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "key check failed: ${e.message}")
            KeyCheck.Unreachable
        }
    }

    private fun request(url: String, apiKey: String?): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .apply { apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
        .build()

    private fun quotaOf(response: Response, nowMs: Long): Quota? {
        val limit = response.header("X-UsageLimit-Limit")?.toIntOrNull() ?: return null
        val remaining = response.header("X-UsageLimit-Remaining")?.toIntOrNull() ?: return null
        val reset = response.header("X-UsageLimit-Reset")?.toLongOrNull() ?: 0L
        return Quota(limit, remaining, reset, nowMs)
    }

    /** How long a 429 asks us to wait: the longer of the burst and the daily window, at least a minute. */
    private fun retryAfterMs(response: Response): Long {
        val seconds = listOfNotNull(
            response.header("X-RateLimit-Reset")?.toLongOrNull(),
            response.header("X-UsageLimit-Reset")?.toLongOrNull(),
            response.header("X-UsageLimit-SpecificMedia-Reset")?.toLongOrNull(),
        ).maxOrNull() ?: 0L
        return maxOf(seconds * 1000, MIN_BACKOFF_MS)
    }

    companion object {
        private const val TAG = "TheIntroDB"
        private const val BASE = "https://api.theintrodb.org/v4"
        private const val USER_AGENT = "OwnTV"
        private const val MIN_BACKOFF_MS = 60_000L
    }
}
