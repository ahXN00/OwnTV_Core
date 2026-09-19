package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import tv.own.owntv.core.model.MediaType

/**
 * One category's outstanding VOD pages on a Stalker portal (v42, plan N1b).
 *
 * Stalker has no bulk VOD endpoint and the portals that matter cap `max_page_items` at ~14, so a
 * full catalogue is thousands of round trips — measured at 4,681 pages for 65,523 movies plus 1,567
 * for 21,932 series on the reference portal, which is 4½ minutes the user spends watching a progress
 * bar at setup.
 *
 * Setup therefore stops after the page-1 sweep it already performs: that sweep is cheap (~11 s for
 * both types, concurrent), it learns every category's `total_items`, and it already yields the first
 * page of every category — so the top of every list is browsable immediately. The pages it did *not*
 * fetch are written here, and drained afterwards in the background (N1d) or on demand when the user
 * opens that category (N1c).
 *
 * One row per (source, media type, category) rather than one per page: the page *range* is what the
 * plan produces, ranges survive a portal that re-paginates between runs, and 139 rows is a far
 * cheaper thing to keep consistent than 4,593.
 *
 * [sortBase] is the load-bearing column. It is the category's first slot on the provider-order sort
 * axis, computed once during the page-1 sweep when the whole category layout is known. Re-deriving
 * it later would be wrong — a category's base depends on every *preceding* category's page count —
 * and `sortOrder` is deliberately not part of the content hash, so a scrambled backfill would be
 * frozen in place forever. Persisting it is what makes a lazily-loaded row and a backfilled row land
 * in the same order.
 */
@Entity(
    tableName = "catalog_backfill",
    primaryKeys = ["sourceId", "mediaType", "categoryRemoteId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        // The backfill worker's only query: the next unfinished category of one phase.
        Index(value = ["sourceId", "mediaType", "done"]),
    ],
)
data class CatalogBackfillEntity(
    val sourceId: Long,
    val mediaType: MediaType,
    /** The portal's own category id, as passed back to `get_ordered_list&category=`. */
    val categoryRemoteId: String,
    /** Local `categories.id`, or null when the category never resolved to a row. */
    val categoryDbId: Long?,
    /** The portal's advertised `total_items` for this category, from the page-1 sweep. */
    val totalItems: Int,
    /** The portal's `max_page_items` for this category — 14 on the reference portal. */
    val maxPageItems: Int,
    /** Next page to fetch. Starts at 2 (page 1 was stored at setup); `> lastPage` means drained. */
    val nextPage: Int,
    /** Last page worth fetching, from `ceil(totalItems / maxPageItems)`, capped like the eager walk. */
    val lastPage: Int,
    /** This category's first slot on the provider-order sort axis. See the class note. */
    val sortBase: Int,
    /** Set when every page has been fetched, or when the category turned out to have only one. */
    val done: Boolean = false,
)
