package tv.own.owntv.core.customize

import tv.own.owntv.core.live.LiveKey

/**
 * The browse view of a section's customizations once category locks are applied.
 *
 * A lock only counts while the profile has a Customize PIN: without one there is nothing to check an
 * entry against, so a lock left behind (a partial restore, say) must not wall a category off for good.
 * The locked categories [unlocked] does not hold become [SectionCustomizations.sealedCategories],
 * hidden or not: a plain hide does not reach Favorites, History or custom categories, a seal does.
 * They are also folded into the hidden categories, so every list that already drops hidden
 * categories drops them too. The rail undoes the fold, and only the fold, with [forRail].
 */
fun SectionCustomizations.withLocks(hasPin: Boolean, unlocked: Set<String>): SectionCustomizations {
    if (lockedCategories.isEmpty()) return this
    if (!hasPin) return copy(lockedCategories = emptySet())
    val sealed = lockedCategories - unlocked
    if (sealed.isEmpty()) return this
    return copy(
        hiddenCategories = hiddenCategories + sealed,
        sealedCategories = sealed,
        sealHidden = sealed - hiddenCategories,
    )
}

/** For the category rail: a sealed category stays listed (with its lock); the user's own hides still apply. */
fun SectionCustomizations.forRail(): SectionCustomizations =
    if (sealHidden.isEmpty()) this else copy(hiddenCategories = hiddenCategories - sealHidden)

/** Whether the list behind the rail entry [key] is a sealed category. */
fun SectionCustomizations.seals(key: LiveKey?): Boolean = when (key) {
    is LiveKey.Folder -> key.id in sealedCategoryIds
    is LiveKey.Custom -> key.id in sealedCategories
    else -> false
}

/**
 * Whether an item filed under provider category [categoryId] is behind a sealed lock. Unlike a plain
 * hide, this holds in Favorites, History and custom categories too: a lock keeps the items private.
 */
fun SectionCustomizations.sealsItemIn(categoryId: Long?): Boolean =
    categoryId != null && categoryId in sealedCategoryIds
