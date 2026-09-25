package tv.own.owntv.core.metadata

/**
 * TMDB metadata enrichment (see extras/future-plan/tmdb-metadata-plan.md).
 *
 * OwnTV enriches VOD movies / series / episodes on demand from TMDB — never in bulk (libraries run to
 * ~170k movies / ~50k series). A single [MetadataProvider] serves all three access tiers; only its base
 * URL / auth differ (see [MetadataConfig] and [TmdbProvider]). Live TV is out of scope (no canonical id).
 */

/** Kind of TMDB object a lookup targets. */
enum class MetadataType { MOVIE, TV, EPISODE }

/**
 * A slim TMDB search hit — enough to pick a match and show a poster. Full details (cast, rating, genres,
 * imdb_id, backdrop) are fetched lazily on the detail screen in a later phase.
 */
data class MetadataSearchResult(
    val tmdbId: Int,
    val type: MetadataType,
    val title: String,
    /**
     * TMDB's `original_title` / `original_name` — the title in the production's own language, which TMDB
     * returns unchanged whatever `&language=` is set to. Matching scores against this AS WELL AS [title]:
     * with a non-English metadata language [title] comes back translated (e.g. "Οπενχάιμερ"), which shares
     * no tokens with the provider's Latin catalog name and used to sink every match. Null when TMDB omits it.
     */
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    /** TMDB relative poster path (e.g. "/abc.jpg"); build the image.tmdb.org URL at render time. */
    val posterPath: String?,
    /** TMDB popularity — used as a tiebreak when several titles match. */
    val popularity: Double,
)

/**
 * One ranked result from TMDB's current daily Trending feed. This deliberately carries only the fields
 * needed for local provider matching and the Home showcase; full details/trailers are fetched later for
 * at most the ten final provider-playable matches.
 */
data class TrendingCandidate(
    val tmdbId: Int,
    val type: MetadataType,
    val localizedTitle: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val popularity: Double,
    /** One-based position in TMDB's media-specific Trending response before local filtering. */
    val trendingRank: Int,
)

/**
 * One page of a provider's daily Trending feed (20 rows on TMDB).
 *
 * Deliberately page-aware rather than pre-merged: matching walks candidates in rank order, so once it has
 * selected its full quota from page 1 no later page could change the outcome. Callers fetch page 1, run
 * the match, and only pay for page 2 when slots are still open — which on a large catalog is almost never.
 */
data class TrendingFeedPage(
    val page: Int,
    val totalPages: Int,
    val candidates: List<TrendingCandidate>,
) {
    companion object {
        /** How many candidates a full merge keeps — more than the ten finalists, to survive local misses. */
        const val TRENDING_CANDIDATE_LIMIT = 25

        /** Keeps provider rank order, removes duplicate ids across pages, and caps at [limit]. */
        fun merge(
            pages: List<TrendingFeedPage>,
            limit: Int = TRENDING_CANDIDATE_LIMIT,
        ): List<TrendingCandidate> {
            require(limit > 0)
            val seen = HashSet<Pair<MetadataType, Int>>()
            return pages
                .asSequence()
                .flatMap { it.candidates.asSequence() }
                .sortedBy { it.trendingRank }
                .filter { seen.add(it.type to it.tmdbId) }
                .take(limit)
                .toList()
        }
    }
}

/**
 * Metadata source mode (plan §4.1). Replaces the old on/off master toggle and also selects the render-time
 * field precedence for the merge (§7.1).
 */
enum class MetadataMode {
    /** Only provider data; TMDB fully off (no lookups). */
    PROVIDER,
    /** Provider wins; TMDB fills gaps & adds extras. `providerField ?: tmdbField`. */
    PROVIDER_PLUS_TMDB,
    /** TMDB wins; provider only fills what TMDB lacks. `tmdbField ?: providerField`. */
    TMDB_ONLY;

    /** True when TMDB enrichment should run (both non-Provider modes). */
    val enrich: Boolean get() = this != PROVIDER

    /** True when TMDB fields take precedence over provider fields. */
    val tmdbWins: Boolean get() = this == TMDB_ONLY
}

/**
 * Resolved access configuration for the metadata provider. Precedence (highest first), per plan §4:
 *  1. [customServerUrl] set  → Tier 3 self-host (TMDB-shaped proxy/mirror; no key sent).
 *  2. [tmdbApiKey] set       → Tier 2 advanced (calls api.themoviedb.org directly with the user's key).
 *  3. neither                → Tier 0 default caching Cloudflare Worker (key injected server-side).
 *
 * [mode] is the source mode; [enabled] is derived from it (enrichment runs unless mode is Provider).
 */
data class MetadataConfig(
    val mode: MetadataMode = MetadataMode.PROVIDER_PLUS_TMDB,
    val tmdbApiKey: String = "",
    val customServerUrl: String = "",
    val language: String = "",
) {
    /** Whether TMDB lookups should run at all. */
    val enabled: Boolean get() = mode.enrich

    /**
     * The TMDB `language` parameter value for a call, or blank to send none (TMDB then defaults to
     * en-US — the app's behaviour before this setting existed).
     *
     * [LANGUAGE_AUTO] resolves to the device locale as `<lang>-<REGION>` (e.g. `el-GR`), falling back to
     * the bare language tag when the locale carries no country. TMDB degrades gracefully: an unknown
     * region falls back to the base language, and a field with no translation comes back in English.
     */
    val resolvedLanguage: String
        get() = when {
            language.isBlank() -> ""
            language != LANGUAGE_AUTO -> language
            else -> java.util.Locale.getDefault().let { l ->
                val lang = l.language.takeIf { it.isNotBlank() } ?: return@let ""
                if (l.country.isNotBlank()) "$lang-${l.country}" else lang
            }
        }

    /** Which tier this config resolves to (for the Settings label). */
    val tier: Tier
        get() = when {
            customServerUrl.isNotBlank() -> Tier.SELF_HOST
            tmdbApiKey.isNotBlank() -> Tier.OWN_KEY
            else -> Tier.DEFAULT_WORKER
        }

    enum class Tier {
        DEFAULT_WORKER,
        OWN_KEY,
        SELF_HOST,
    }

    companion object {
        /** Sentinel [language] value meaning "follow the device locale". */
        const val LANGUAGE_AUTO = "auto"
    }
}

/**
 * Full movie details (TMDB `/movie/{id}?append_to_response=credits,external_ids`). Everything IPTV rarely
 * carries — imdb_id (Trakt), backdrop, genres, cast, rating — plus the fields already in the search hit.
 */
data class MovieDetails(
    val tmdbId: Int,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double?,
    val genres: List<String>,
    val cast: List<CastMember>,
    /** Best YouTube trailer video key from `videos` (official Trailer > Trailer > Teaser); null if none. */
    val trailerKey: String?,
    /** Best title/logo image path from TMDB images; null when no usable logo exists. */
    val logoPath: String?,
    /** TMDB `original_language` (ISO 639-1, e.g. "ko"): what "Original language" audio plays (N14). */
    val originalLanguage: String? = null,
)

/** Per-episode TMDB details (`/tv/{id}/season/{n}/episode/{m}`). Its own still, plot, air date, rating. */
data class EpisodeDetails(
    val name: String?,
    val overview: String?,
    val stillPath: String?,   // 16:9 episode thumbnail (relative path)
    val airDate: String?,     // "2019-04-14"
    val rating: Double?,
)

/**
 * One episode from a bundled season fetch, tagged with the numbers needed to cache it. The per-episode
 * endpoint knows which episode it asked for; a bundle does not, so the numbers travel with the payload.
 */
data class SeasonEpisode(val seasonNumber: Int, val episodeNumber: Int, val details: EpisodeDetails)

/** Enrichment source abstraction. Only [TmdbProvider] exists today; fanart.tv could be added later. */
interface MetadataProvider {

    /**
     * One page of the current daily Trending feed for [type] (MOVIE or TV), or null when
     * transport/auth/parsing fails. Pages are one-based; merge them with [TrendingFeedPage.merge].
     */
    suspend fun trendingPage(type: MetadataType, page: Int): TrendingFeedPage?

    /**
     * Search movies by cleaned [title] (+ optional [year]). Best matches first.
     * **Empty list = TMDB answered "no results"** (callers may negative-cache);
     * **null = transport failure** (network down, rate-limited, proxy error) — callers must NOT
     * negative-cache, so the lookup retries on the next open instead of being wrong for 7 days.
     */
    suspend fun searchMovie(title: String, year: Int? = null, includeAdult: Boolean = false): List<MetadataSearchResult>?

    /** Search TV shows by cleaned [title] (+ optional first-air [year]). Same null-vs-empty contract as [searchMovie]. */
    suspend fun searchTv(title: String, year: Int? = null, includeAdult: Boolean = false): List<MetadataSearchResult>?

    /** Full details for a resolved movie id; null on network/parse failure. */
    suspend fun movieDetails(tmdbId: Int): MovieDetails?

    /** Full details for a resolved TV show id (reuses [MovieDetails]: title=name, year=first air); null on failure. */
    suspend fun tvDetails(tmdbId: Int): MovieDetails?

    /** Per-episode details for a resolved show; null on failure or if that episode isn't on TMDB. */
    /**
     * Every episode of [seasons] in ONE request, or null on transport/parse failure.
     *
     * Replaces N per-episode calls with one: browsing a whole show used to cost one request per episode
     * focused (~120 for a five-season show), which no grid of episode stills could ever afford. Seasons
     * that do not exist are simply absent from the result — verified against the Worker, TMDB omits them
     * rather than failing — so the caller may over-request without knowing the real season count.
     */
    suspend fun tvSeasonBundle(tvId: Int, seasons: List<Int>): List<SeasonEpisode>?
}
