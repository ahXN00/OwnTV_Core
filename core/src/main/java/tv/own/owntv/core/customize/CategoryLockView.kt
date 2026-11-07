package tv.own.owntv.core.customize

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Applies category locks to what browsing reads: [CustomizationStore.observeVisible] and
 * [CustomizationStore.sealsCategory] go through here. A lock needs the profile's Customize PIN and is
 * lifted for the rest of the session by [CategoryUnlockSession]; see [withLocks] for the rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryLockView(
    private val settings: SettingsRepository,
    private val session: CategoryUnlockSession,
    private val categoryDao: CategoryDao,
) {
    fun visible(profileId: Long, type: MediaType, raw: Flow<SectionCustomizations>): Flow<SectionCustomizations> =
        combine(raw, hasPin(profileId), session.unlocked(profileId, type)) { c, pin, open -> c.withLocks(pin, open) }
            .flatMapLatest { c -> withSealedIds(type, c) }
            .distinctUntilChanged()

    /**
     * True when [item]'s category is locked and, unless [anySession], not opened this session. An
     * unresolved item is sealed while the profile has such a lock in any section (see [isSealed]).
     */
    suspend fun seals(
        profileId: Long,
        item: ItemCategory,
        anySession: Boolean,
        raw: suspend (MediaType) -> SectionCustomizations,
    ): Boolean = item.isSealed({ anySealed(profileId, anySession, raw) }) { categoryId ->
        sealsCategory(profileId, categoryId, anySession, raw)
    }

    private suspend fun sealsCategory(
        profileId: Long,
        categoryId: Long,
        anySession: Boolean,
        raw: suspend (MediaType) -> SectionCustomizations,
    ): Boolean {
        // A category row a re-sync removed after the item was read is as unknown as a gone item.
        val category = categoryDao.getById(categoryId) ?: return anySealed(profileId, anySession, raw)
        val locked = raw(category.mediaType).lockedCategories
        if (locked.isEmpty() || !hasPin(profileId).first()) return false
        val key = CustomizeKeys.category(category)
        return key in locked && (anySession || !session.isUnlocked(profileId, category.mediaType, key))
    }

    /** Whether the profile has a lock, in any section, that unless [anySession] this session has not opened. */
    private suspend fun anySealed(
        profileId: Long,
        anySession: Boolean,
        raw: suspend (MediaType) -> SectionCustomizations,
    ): Boolean {
        val sealed = LOCKABLE_SECTIONS.any { type ->
            raw(type).lockedCategories.any { key -> anySession || !session.isUnlocked(profileId, type, key) }
        }
        return sealed && hasPin(profileId).first()
    }

    /** Whether a lock can seal anything on [profileId]: it has the PIN and a locked category in some section. */
    fun hasLocks(profileId: Long, raw: (MediaType) -> Flow<SectionCustomizations>): Flow<Boolean> =
        combine(LOCKABLE_SECTIONS.map { type -> raw(type).map { it.lockedCategories.isNotEmpty() } }) { it.any { locked -> locked } }
            .combine(hasPin(profileId)) { locked, pin -> locked && pin }
            .distinctUntilChanged()

    private fun hasPin(profileId: Long): Flow<Boolean> = settings.customizePin(profileId).map { it != null }

    /** Resolves the sealed provider folders to DB ids; custom ids have no row and stay key-only. */
    private fun withSealedIds(type: MediaType, c: SectionCustomizations): Flow<SectionCustomizations> {
        val sourceIds = c.sealedCategories.mapNotNull { it.substringBefore(':').toLongOrNull() }.distinct()
        if (sourceIds.isEmpty()) return flowOf(c)
        return categoryDao.observe(sourceIds, type).map { cats ->
            c.copy(sealedCategoryIds = cats.filter { CustomizeKeys.category(it) in c.sealedCategories }.mapTo(HashSet()) { it.id })
        }
    }

    private companion object {
        val LOCKABLE_SECTIONS = listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)
    }
}
