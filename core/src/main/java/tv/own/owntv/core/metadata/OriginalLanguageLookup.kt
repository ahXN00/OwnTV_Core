package tv.own.owntv.core.metadata

import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.player.TrackLanguages
import tv.own.owntv.core.player.mediaTypeOfPinKey
import tv.own.owntv.core.player.sourceIdOfPinKey

/**
 * The original language of what is playing, for the "Original language" audio choice (N14).
 *
 * Takes the player's track key — `<sourceId>:MOVIE:<remoteId>` for a film, `<sourceId>:SERIES:<remoteId>`
 * for every episode of a show — and answers with the 639-2 code tracks carry ("kor"), or null when the
 * title is unknown to TMDB, enrichment is off, or the key is not a film or show (live, a URL key).
 */
class OriginalLanguageLookup(
    private val movies: MovieDao,
    private val series: SeriesDao,
    private val metadata: MetadataRepository,
) {
    suspend fun of(trackKey: String): String? {
        val sourceId = sourceIdOfPinKey(trackKey).takeIf { it >= 0 } ?: return null
        val type = mediaTypeOfPinKey(trackKey) ?: return null
        val remoteId = trackKey.substringAfter(':').substringAfter(':').takeIf { it.isNotEmpty() } ?: return null
        val iso6391 = when (type) {
            "MOVIE" -> movies.findByRemote(sourceId, remoteId)?.let { metadata.originalLanguageOfMovie(it) }
            "SERIES" -> series.findSeriesByRemote(sourceId, remoteId)?.let { metadata.originalLanguageOfSeries(it) }
            else -> null
        }
        return TrackLanguages.fromIso6391(iso6391)
    }
}
