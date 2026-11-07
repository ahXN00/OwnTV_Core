package tv.own.owntv.core.customize

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType

/**
 * The category rail, exactly as a browse screen shows it: the kids filter, the profile's custom
 * combined categories, hides, renames and the manual order (or A–Z when [alphabetical]), in one place.
 *
 * Both apps' Live / Movies / Series rails read this, and so does [CategoryMove] — which is the
 * point. A move that reorders a list built any other way reorders a list that is not the one on
 * screen, and the two implementations only have to drift once to be wrong.
 */
fun List<CategoryEntity>.railCategories(
    c: SectionCustomizations,
    kids: Boolean,
    alphabetical: Boolean = false,
): List<CustomizedCategory> {
    val cats = if (kids) filterNot { AdultCategoryClassifier.isAdult(it.name) } else this
    val customs =
        if (kids) c.customCategories.filterNot { AdultCategoryClassifier.isAdult(it.name) }
        else c.customCategories
    return cats.applyCustomizationsWithCustoms(c.forRail(), customs, alphabetical)
}

/**
 * One category being moved through the rail, before the new order is committed.
 *
 * [rows] is the rail's own list from [railCategories]; [activeIndex] is where the moved category
 * currently sits in it. The move is live on screen and only reaches the store on commit, so leaving
 * it costs nothing — the TV drives it from a D-pad overlay, the phone from a menu.
 */
data class CategoryMove(
    val rows: List<CustomizedCategory>,
    val activeIndex: Int,
    val targetKey: String,
    /** The categories carried in this move; only these (and ones moved before) keep a position. */
    val carried: Set<String> = emptySet(),
) {
    /** Displayed names, in the order the move has them now. */
    val items: List<String> get() = rows.map { it.displayName }

    /** Stable keys, in the order the move has them now — what [CustomizationStore.saveCategoryMove] takes. */
    val keys: List<String> get() = rows.map { it.key }

    /** The same move one step [kind]-ward, or null when it would run off the end. */
    fun moved(kind: MoveKind): CategoryMove? {
        val next = moveBlock(rows, activeIndex, activeIndex, kind) ?: return null
        return copy(rows = next, activeIndex = next.indexOfFirst { it.key == targetKey }, carried = carried + targetKey)
    }

    companion object {
        /** Starts a move of [targetKey] within [rows], or null when that category is not on the rail. */
        fun begin(rows: List<CustomizedCategory>, targetKey: String): CategoryMove? {
            val index = rows.indexOfFirst { it.key == targetKey }
            if (index < 0) return null
            return CategoryMove(rows, index, targetKey)
        }
    }
}

/**
 * Hiding and reordering a category from the browse screen that shows it, rather than from
 * Settings → Customize.
 *
 * Both apps offer this on a long press and both write the same [CustomizationStore] keys, so a
 * change made here shows up in Customize, and one made there shows up on the rail. The television
 * takes a move step by step through a D-pad overlay ([beginMove]); the phone commits one step at a
 * time from a menu ([move]).
 *
 * A move walks the rail as it is shown, in the current sort; the moved category keeps that position in
 * every sort.
 */
class CategoryRailEditor(
    private val customize: CustomizationStore,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
) {
    /** The customization key behind a rail selection, or null for All / Favorites / History / Catch-up. */
    suspend fun customizationKey(key: LiveKey): String? = when (key) {
        is LiveKey.Folder -> categoryDao.getById(key.id)?.let { CustomizeKeys.category(it) }
        is LiveKey.Custom -> key.id
        else -> null
    }

    /** The rail's categories, exactly as the screen has them. */
    private suspend fun rows(
        profileId: Long,
        sourceIds: List<Long>,
        type: MediaType,
        alphabetical: Boolean,
    ): List<CustomizedCategory> = categoryDao.observe(sourceIds, type).first().railCategories(
        customize.observe(profileId, type).first(),
        kids = profileDao.getById(profileId)?.isKids == true,
        alphabetical = alphabetical,
    )

    suspend fun hide(profileId: Long, type: MediaType, key: LiveKey) {
        customize.setCategoryHidden(profileId, type, customizationKey(key) ?: return, true)
    }

    /** Starts a move the caller then steps and commits itself — the television's overlay. */
    suspend fun beginMove(
        profileId: Long,
        sourceIds: List<Long>,
        type: MediaType,
        alphabetical: Boolean,
        key: LiveKey,
    ): CategoryMove? = CategoryMove.begin(
        rows(profileId, sourceIds, type, alphabetical),
        customizationKey(key) ?: return null,
    )

    /** Moves a category one step and stores the result — the phone's menu. */
    suspend fun move(
        profileId: Long,
        sourceIds: List<Long>,
        type: MediaType,
        alphabetical: Boolean,
        key: LiveKey,
        kind: MoveKind,
    ) {
        val moved = beginMove(profileId, sourceIds, type, alphabetical, key)?.moved(kind) ?: return
        customize.saveCategoryMove(profileId, type, moved.keys, moved.carried)
    }
}
