package tv.own.owntv.core.skip

import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * "Check TheIntroDB" in the film, series and episode long-press menus: does TheIntroDB have skip times
 * for this title, and which parts. Asked fresh every time — never from the playback cache — so a time
 * submitted a minute ago shows up, and the answer says exactly what a viewer reporting "no skip button"
 * needs to hear: matched or not, listed or not, and which parts exist.
 */
class TheIntroDbCheck(
    private val client: TheIntroDbClient,
    private val metadata: MetadataRepository,
    private val settings: SettingsRepository,
) {
    sealed interface Result {
        /** The name the check was made for, as the library shows it. */
        val title: String

        /** The title is not matched with TMDB (or details are off), so there is nothing to ask with. */
        data class NoTmdb(override val title: String) : Result

        /** TheIntroDB answered: [kinds] are the parts it has times for (empty = none yet). */
        data class Answered(
            override val title: String,
            val tmdbId: Int,
            val season: Int?,
            val episode: Int?,
            val listed: Boolean,
            val kinds: List<SkipKind>,
        ) : Result

        data class Failed(override val title: String) : Result
    }

    suspend fun movie(movie: MovieEntity): Result {
        val tmdbId = runCatching { metadata.resolveMovie(movie)?.tmdbId }.getOrNull()?.takeIf { it > 0 }
            ?: return Result.NoTmdb(movie.name)
        return ask(movie.name, tmdbId, null, null)
    }

    /** A show is checked by [episode], or by its first episode (S1 E1) from the series menu. */
    suspend fun series(show: SeriesEntity, episode: EpisodeEntity?): Result {
        val tmdbId = runCatching { metadata.resolveSeries(show)?.tmdbId }.getOrNull()?.takeIf { it > 0 }
            ?: return Result.NoTmdb(show.name)
        return ask(show.name, tmdbId, episode?.seasonNumber ?: 1, episode?.episodeNumber ?: 1)
    }

    private suspend fun ask(title: String, tmdbId: Int, season: Int?, episode: Int?): Result {
        val key = settings.currentTheIntroDbApiKey().ifBlank { null }
        val (lookup, _) = client.media(tmdbId, season, episode, null, key)
        return when (lookup) {
            is TheIntroDbClient.Lookup.Found -> Result.Answered(
                title, tmdbId, season, episode, listed = true,
                // A zero-length entry is how the service records "this one has none".
                kinds = lookup.segments.filter { it.startMs == null || it.endMs == null || it.endMs > it.startMs }
                    .map { it.kind }.distinct().sortedBy { it.ordinal },
            )
            TheIntroDbClient.Lookup.NotFound -> Result.Answered(title, tmdbId, season, episode, listed = true, kinds = emptyList())
            TheIntroDbClient.Lookup.Unlisted -> Result.Answered(title, tmdbId, season, episode, listed = false, kinds = emptyList())
            is TheIntroDbClient.Lookup.Limited, TheIntroDbClient.Lookup.Failed -> Result.Failed(title)
        }
    }
}
