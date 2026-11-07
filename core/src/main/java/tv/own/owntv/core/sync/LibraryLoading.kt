package tv.own.owntv.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.dao.CatalogBackfillDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * "Your library is still loading" — for Home, while a lazily-added playlist fills in after setup.
 *
 * A part-filled library with no explanation reads as broken: Now Trending missing, rows short. So
 * both apps show a note with the number of titles stored so far, and this is the one place that
 * decides it.
 *
 * A count, not a percentage: the portal's per-category totals count a title once per category it
 * is listed in, so a percentage against them stalls short of 100 on a whole catalogue.
 *
 * The count is read every [REFRESH_MS] rather than observed. Observing it would re-count the film
 * and series tables after every page window the drain writes; this way a playlist that is not
 * filling costs one indexed lookup on the plan table and nothing else.
 */
class LibraryLoading(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val catalogBackfillDao: CatalogBackfillDao,
) {
    /**
     * Titles stored so far on the playlists Home is showing that are still filling in; 0 when none is.
     * Narrowed to the playlist picker, like Home's rows: a full playlist's Home must not say it is
     * still loading because another one is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val titles: Flow<Int> = activeProfileSources(settings, sourceDao)
        .map { it.sourceIds }
        .distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else catalogBackfillDao.observeFilling(ids) }
        .distinctUntilChanged()
        .flatMapLatest { filling ->
            if (filling.isEmpty()) {
                flowOf(0)
            } else {
                flow {
                    while (true) {
                        emit(catalogBackfillDao.titlesStored(filling))
                        delay(REFRESH_MS)
                    }
                }
            }
        }
        .distinctUntilChanged()

    private companion object {
        const val REFRESH_MS = 5_000L
    }
}
