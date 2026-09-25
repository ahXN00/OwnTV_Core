package tv.own.owntv.core.metadata

import android.util.Log
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.settings.SettingsRepository
import java.net.URLEncoder

/**
 * TMDB-backed [MetadataProvider]. All three access tiers run through this one class — [resolveEndpoint]
 * turns the current [MetadataConfig] into a base URL + optional api_key, so search/details calls are
 * tier-agnostic (plan §4).
 *
 * Only small JSON calls go through here. Images load directly from image.tmdb.org (no key), never via the
 * provider, so a proxy/Worker never sees heavy image traffic.
 */
class TmdbProvider(
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val clientId: OwnTVClientId,
    private val budget: MetadataBudget,
) : MetadataProvider {

    /**
     * Resolved base URL + auth + content language for one call. [apiKey] is null for Worker / self-host
     * tiers; [language] is blank when the user hasn't chosen one (TMDB then defaults to en-US).
     * [headers] carries the default Worker's identity headers and is empty on every other tier.
     */
    private data class Endpoint(
        val baseUrl: String,
        val apiKey: String?,
        val language: String,
        val headers: Map<String, String> = emptyMap(),
        /** True only for the shared default Worker — the one tier whose usage is rationed. */
        val metered: Boolean = false,
    )

    /** Precedence per plan §4: self-host URL > user key > default Worker. */
    private suspend fun resolveEndpoint(): Endpoint {
        val cfg = settings.metadataConfig()
        val lang = cfg.resolvedLanguage
        return when (cfg.tier) {
            MetadataConfig.Tier.SELF_HOST -> Endpoint(cfg.customServerUrl.trimEnd('/'), apiKey = null, language = lang)
            MetadataConfig.Tier.OWN_KEY -> Endpoint(TMDB_DIRECT_BASE, apiKey = cfg.tmdbApiKey.trim(), language = lang)
            // Identity headers are sent on THIS TIER ONLY. A self-hoster's Worker has no such rule and a
            // user calling TMDB with their own key must never carry the maintainer's shared secret.
            MetadataConfig.Tier.DEFAULT_WORKER -> Endpoint(
                baseUrl = defaultWorkerBase(),
                apiKey = null,
                language = lang,
                headers = defaultWorkerHeaders(),
                metered = true,
            )
        }
    }

    /**
     * Identity for the maintainer's Worker: the shared edge-rule secret plus an opaque per-install id.
     * Empty when the build carries no key (fork CI, fresh clone) — that build talks to the unprotected
     * legacy address instead, so sending a bare client id there would be pointless.
     */
    private suspend fun defaultWorkerHeaders(): Map<String, String> {
        val key = CoreBuildInfo.edgeKey
        if (key.isBlank()) return emptyMap()
        return mapOf(
            "x-owntv-key" to key,
            // The default Worker validates the key per app version, so it needs the version alongside it.
            "x-owntv-version" to CoreBuildInfo.versionName,
            "x-owntv-client" to runCatching { clientId.get() }.getOrDefault(""),
        )
    }

    /**
     * Every TMDB call goes through here so the per-install allowance is impossible to bypass by adding
     * a new endpoint later.
     *
     * Returns null both when the allowance is spent and when the request fails. That is deliberate:
     * callers already treat null as a transport failure and skip negative-caching, so a refused call
     * behaves exactly like being offline — provider data still shows, and nothing is poisoned for the
     * next 7 days. Only the shared default Worker is metered; a user's own key or server is not.
     */
    private suspend fun fetch(ep: Endpoint, url: String, label: String): String? {
        if (ep.metered && !budget.tryConsume()) {
            Log.w(TAG, "metadata allowance spent, skipping $label")
            return null
        }
        // Dev-only accounting: the allowance counter in Settings shows a total, not what spent it. This
        // is the single choke point every metered call passes through, so one line here answers "what
        // actually consumed my allowance". Compiled out of shipping builds.
        if (CoreBuildInfo.devTools && ep.metered) Log.i(SPEND_TAG, "spend: $label")
        return runCatching { http.getText(url, headers = ep.headers) }
            .onFailure { Log.w(TAG, "TMDB $label failed: ${it.message}") }
            .getOrNull()
    }

    /** `&language=<code>`, or "" when no language is configured (TMDB falls back to en-US). */
    private fun Endpoint.langParam(): String =
        if (language.isBlank()) "" else "&language=" + enc(language)

    /**
     * `include_image_language` for detail calls. Always keeps `en,null` (null = textless art, which is
     * what most posters/backdrops are) and prepends the chosen language so localized artwork wins when
     * TMDB has it. Uses the base language only — TMDB indexes images by ISO 639-1, not by region.
     */
    private fun Endpoint.imageLangParam(): String {
        val base = language.substringBefore('-').takeIf { it.isNotBlank() && it != "en" }
        return "&include_image_language=" + (if (base != null) "$base,en,null" else "en,null")
    }

    override suspend fun trendingPage(type: MetadataType, page: Int): TrendingFeedPage? {
        require(type == MetadataType.MOVIE || type == MetadataType.TV)
        require(page > 0)
        val endpoint = resolveEndpoint()
        val url = buildTrendingUrl(endpoint.baseUrl, endpoint.apiKey, endpoint.language, type, page)
        val body = fetch(endpoint, url, "Trending type=$type page=$page") ?: return null
        return TmdbTrendingParser.parsePage(type, page, body).also {
            if (it == null) Log.w(TAG, "TMDB Trending parse failed type=$type page=$page")
        }
    }

    override suspend fun searchMovie(title: String, year: Int?, includeAdult: Boolean): List<MetadataSearchResult>? =
        search(MetadataType.MOVIE, title, year, includeAdult)

    override suspend fun searchTv(title: String, year: Int?, includeAdult: Boolean): List<MetadataSearchResult>? =
        search(MetadataType.TV, title, year, includeAdult)

    private suspend fun search(type: MetadataType, title: String, year: Int?, includeAdult: Boolean): List<MetadataSearchResult>? {
        val query = title.trim()
        if (query.isEmpty()) return emptyList()
        val ep = resolveEndpoint()
        val path = if (type == MetadataType.TV) "/3/search/tv" else "/3/search/movie"
        val yearParam = when {
            year == null -> ""
            type == MetadataType.TV -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val url = buildString {
            append(ep.baseUrl).append(path)
            append("?query=").append(enc(query))
            append(yearParam)
            append("&include_adult=").append(includeAdult)
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        // Transport failure (network down, HTTP 429 rate limit, proxy/Worker error) → null, NOT empty:
        // an empty list means "TMDB said no results" and gets negative-cached for 7 days upstream.
        val json = fetch(ep, url, "search type=$type") ?: return null

        return parseResults(type, json, includeAdult)
    }

    override suspend fun movieDetails(tmdbId: Int): MovieDetails? {
        if (tmdbId <= 0) return null
        val ep = resolveEndpoint()
        val url = buildString {
            append(ep.baseUrl).append("/3/movie/").append(tmdbId)
            append("?append_to_response=credits,external_ids,videos,images")
            append(ep.imageLangParam())
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        val json = fetch(ep, url, "movie details id=$tmdbId") ?: return null
        return runCatching { parseMovieDetails(json, ep.language.substringBefore('-')) }.getOrNull()
    }

    override suspend fun tvDetails(tmdbId: Int): MovieDetails? {
        if (tmdbId <= 0) return null
        val ep = resolveEndpoint()
        val url = buildString {
            append(ep.baseUrl).append("/3/tv/").append(tmdbId)
            append("?append_to_response=credits,external_ids,videos,images")
            append(ep.imageLangParam())
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        val json = fetch(ep, url, "tv details id=$tmdbId") ?: return null
        return runCatching { parseTvDetails(json, ep.language.substringBefore('-')) }.getOrNull()
    }

    override suspend fun tvSeasonBundle(tvId: Int, seasons: List<Int>): List<SeasonEpisode>? {
        if (tvId <= 0 || seasons.isEmpty()) return null
        val ep = resolveEndpoint()
        // TMDB rejects the WHOLE request with status_code 27 past 20 appended parts, so this must clamp
        // rather than rely on the server to trim. Verified against the Worker: 20 parts pass, 21 fails.
        val wanted = seasons.distinct().sorted().take(MAX_BUNDLED_SEASONS)
        val url = buildString {
            append(ep.baseUrl).append("/3/tv/").append(tvId)
            append("?append_to_response=").append(wanted.joinToString(",") { "season/$it" })
            append(ep.langParam())
            ep.apiKey?.takeIf { it.isNotBlank() }?.let { append("&api_key=").append(enc(it)) }
        }
        val json = fetch(ep, url, "season bundle tv=$tvId seasons=${wanted.firstOrNull()}..${wanted.lastOrNull()}")
            ?: return null
        return runCatching {
            val root = JSONObject(json)
            val out = mutableListOf<SeasonEpisode>()
            for (season in wanted) {
                // Absent = that season does not exist. Not an error, and not worth logging per show.
                val episodes = root.optJSONObject("season/$season")?.optJSONArray("episodes") ?: continue
                for (i in 0 until episodes.length()) {
                    val o = episodes.optJSONObject(i) ?: continue
                    val number = o.optInt("episode_number", -1)
                    if (number < 0) continue
                    // season_number is echoed per episode; trust it over the key for specials ordering.
                    out += SeasonEpisode(o.optInt("season_number", season), number, parseEpisode(o))
                }
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Shared by the single-episode endpoint and the bundled season payload — identical field shapes. */
    private fun parseEpisode(o: JSONObject): EpisodeDetails =
        EpisodeDetails(
            name = o.optString("name").takeIf { it.isNotBlank() },
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            stillPath = o.optString("still_path").takeIf { it.isNotBlank() && it != "null" },
            airDate = o.optString("air_date").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
        )

    private fun parseTvDetails(body: String, preferredLang: String): MovieDetails? {
        val o = JSONObject(body)
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val cast = parseCast(o)
        val imdb = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() && it != "null" }
        return MovieDetails(
            tmdbId = id,
            imdbId = imdb,
            title = o.optString("name").ifBlank { "?" },
            year = o.optString("first_air_date").take(4).toIntOrNull(),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            genres = genres,
            cast = cast,
            trailerKey = parseTrailerKey(o),
            logoPath = parseLogoPath(o, preferredLang),
            originalLanguage = o.optString("original_language").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parseMovieDetails(body: String, preferredLang: String): MovieDetails? {
        val o = JSONObject(body)
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val cast = parseCast(o)
        val imdb = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" }
        return MovieDetails(
            tmdbId = id,
            imdbId = imdb,
            title = o.optString("title").ifBlank { "?" },
            year = o.optString("release_date").take(4).toIntOrNull(),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            genres = genres,
            cast = cast,
            trailerKey = parseTrailerKey(o),
            logoPath = parseLogoPath(o, preferredLang),
            originalLanguage = o.optString("original_language").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /**
     * Best YouTube trailer key from an `append_to_response=videos` payload (plan §7.3):
     * official Trailer > any Trailer > Teaser. Only `site == "YouTube"` entries qualify
     * (the in-app player is a YouTube IFrame wrapper). Null when the title has no usable video.
     */
    private fun parseTrailerKey(details: JSONObject): String? {
        val arr = details.optJSONObject("videos")?.optJSONArray("results") ?: return null
        var trailer: String? = null
        var officialTrailer: String? = null
        var teaser: String? = null
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            if (!v.optString("site").equals("YouTube", ignoreCase = true)) continue
            val key = v.optString("key").takeIf { it.isNotBlank() } ?: continue
            when (v.optString("type")) {
                "Trailer" -> {
                    if (v.optBoolean("official") && officialTrailer == null) officialTrailer = key
                    if (trailer == null) trailer = key
                }
                "Teaser" -> if (teaser == null) teaser = key
            }
        }
        return officialTrailer ?: trailer ?: teaser
    }

    /**
     * Top-billed cast with their profile photo paths. `credits` is already part of the details request,
     * so the photo path costs nothing extra — it was previously parsed and dropped.
     */
    private fun parseCast(details: JSONObject): List<CastMember> {
        val arr = details.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        val out = ArrayList<CastMember>(minOf(arr.length(), CAST_LIMIT))
        for (i in 0 until minOf(arr.length(), CAST_LIMIT)) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
            val path = c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
            out += CastMember(name, path)
        }
        return out
    }

    private data class LogoCandidate(val path: String, val languageRank: Int, val width: Int)

    /**
     * Best title logo. [preferredLang] (base ISO 639-1 of the user's chosen metadata language, blank when
     * unset) ranks first so a localized logo wins; English and textless art stay as the fallbacks.
     */
    private fun parseLogoPath(details: JSONObject, preferredLang: String): String? {
        val arr = details.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val candidates = ArrayList<LogoCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val logo = arr.optJSONObject(i) ?: continue
            val path = logo.optString("file_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (path.endsWith(".svg", ignoreCase = true)) continue
            val languageRank = when (logo.optString("iso_639_1").takeIf { it.isNotBlank() && it != "null" }) {
                preferredLang.takeIf { it.isNotBlank() && it != "en" } -> 0
                "en" -> 1
                null -> 2
                else -> 3
            }
            candidates += LogoCandidate(path = path, languageRank = languageRank, width = logo.optInt("width", 0))
        }
        return candidates.minWithOrNull(compareBy<LogoCandidate> { it.languageRank }.thenByDescending { it.width })?.path
    }

    private fun parseResults(type: MetadataType, body: String, includeAdult: Boolean): List<MetadataSearchResult> {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<MetadataSearchResult>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (!includeAdult && o.optBoolean("adult", false)) continue
            val id = o.optInt("id", 0)
            if (id == 0) continue
            val name = if (type == MetadataType.TV) o.optString("name") else o.optString("title")
            val date = if (type == MetadataType.TV) o.optString("first_air_date") else o.optString("release_date")
            // Language-independent title: `name`/`title` above is translated when &language= is set, so
            // the matcher needs the original alongside it (see MetadataSearchResult.originalTitle).
            val original =
                if (type == MetadataType.TV) o.optString("original_name") else o.optString("original_title")
            out += MetadataSearchResult(
                tmdbId = id,
                type = type,
                title = name.ifBlank { "?" },
                originalTitle = original.takeIf { it.isNotBlank() && it != "null" },
                year = date.take(4).toIntOrNull(),
                overview = o.optString("overview").takeIf { it.isNotBlank() },
                posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                popularity = o.optDouble("popularity", 0.0),
            )
        }
        // TMDB already sorts by relevance; keep its order but drop obvious empties.
        return out
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "TmdbProvider"

        /** Own tag so a spend trace can be filtered on its own: `adb logcat -s OwnTVSpend`. */
        private const val SPEND_TAG = "OwnTVSpend"
        private const val CAST_LIMIT = 15

        /**
         * Seasons bundled into one `append_to_response`. TMDB's hard ceiling is 20 appended parts in
         * total and this endpoint spends none of them on anything else, so 16+ would fit — but 16
         * seasons of a long-running show measured 834 KB, which is a lot to pull before an episode grid
         * can draw. 10 keeps the worst case near half that while still covering almost every show in one
         * request; anything longer costs a second request only if the user actually browses that far.
         */
        const val MAX_BUNDLED_SEASONS = 10

        /** Direct TMDB API base (Tier 2, user's own key). */
        const val TMDB_DIRECT_BASE = "https://api.themoviedb.org"

        /** Tier 0 default caching Worker (plan §0.5) — maintainer's key lives in the Worker secret,
         *  never in the APK. The app never sends api_key on this tier; the Worker injects it.
         *
         *  Behind a Cloudflare edge rule that refuses anything without the right `x-owntv-key`, so only
         *  a build that carries the key can use it. */
        const val DEFAULT_WORKER_BASE = "https://tmdb.owntv.me"

        /** The original, unprotected address. Kept as the fallback for builds with no edge key — fork CI
         *  and fresh clones have no secret and would otherwise get a 403 for every lookup. */
        const val LEGACY_WORKER_BASE = "https://owntv-tmdb-meta.xiannero.workers.dev"

        /** Protected base when this build carries the edge key, the open legacy one when it does not. */
        fun defaultWorkerBase(): String =
            if (CoreBuildInfo.edgeKey.isBlank()) LEGACY_WORKER_BASE else DEFAULT_WORKER_BASE

        /** TMDB image CDN — poster/backdrop paths render straight from here, no key. */
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"

        internal fun buildTrendingUrl(
            baseUrl: String,
            apiKey: String?,
            language: String,
            type: MetadataType,
            page: Int,
        ): String {
            require(type == MetadataType.MOVIE || type == MetadataType.TV)
            require(page > 0)
            val mediaPath = if (type == MetadataType.TV) "tv" else "movie"
            return buildString {
                append(baseUrl.trimEnd('/')).append("/3/trending/").append(mediaPath).append("/day")
                append("?page=").append(page)
                if (language.isNotBlank()) append("&language=").append(URLEncoder.encode(language, "UTF-8"))
                apiKey?.takeIf { it.isNotBlank() }
                    ?.let { append("&api_key=").append(URLEncoder.encode(it, "UTF-8")) }
            }
        }
    }
}
