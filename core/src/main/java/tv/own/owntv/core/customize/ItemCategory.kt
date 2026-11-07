package tv.own.owntv.core.customize

/**
 * What a stored row that names its item by local id (a download, a recording) found out about the
 * item's category. Local ids go stale: a re-sync gives a renamed item a new id, so the film, episode,
 * channel or category the row points to can be gone.
 */
sealed interface ItemCategory {
    /** The item is there; [id] is its provider category, null when it is filed under none. */
    data class Found(val id: Long?) : ItemCategory

    /** The item or its category row is gone, so which category held it is unknown. */
    data object Unresolved : ItemCategory
}

/**
 * Whether an item is behind a seal. A found item is sealed when [sealedIn] says its category is. An
 * unresolved one fails closed: it is sealed while [anySealed], i.e. the profile has a lock this session
 * has not opened, and shows as before once every lock is open or there is none.
 */
inline fun ItemCategory.isSealed(anySealed: () -> Boolean, sealedIn: (categoryId: Long) -> Boolean): Boolean =
    when (this) {
        is ItemCategory.Found -> id != null && sealedIn(id)
        ItemCategory.Unresolved -> anySealed()
    }

/** [isSealed] against this section's sealed provider folders; [anySealed] is the profile-wide state. */
fun SectionCustomizations.sealsItem(item: ItemCategory, anySealed: Boolean): Boolean =
    item.isSealed({ anySealed }) { sealsItemIn(it) }
