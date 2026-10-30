package tv.own.owntv.core.customize

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

private val Context.customizeStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_customizations")

/**
 * Stable identity for a category/channel that survives re-sync (content rows are clear-then-insert,
 * so DB ids change every refresh). Xtream rows key on the provider's remote id; M3U rows (no stable
 * id) fall back to the name — so a provider-side rename can detach an M3U customization.
 */
object CustomizeKeys {
    /** Prefix of custom combined-category ids — they share the key namespace, so
     *  hide/rename/reorder need no new code. */
    const val CUSTOM_PREFIX = "custom:"

    fun isCustom(key: String): Boolean = key.startsWith(CUSTOM_PREFIX)
    fun category(c: CategoryEntity): String = "${c.sourceId}:${c.remoteId ?: c.name}"
    fun channel(ch: ChannelEntity): String = "${ch.sourceId}:${ch.remoteId ?: ch.name}"
    fun movie(m: MovieEntity): String = "${m.sourceId}:${m.remoteId ?: m.name}"
    fun series(s: SeriesEntity): String = "${s.sourceId}:${s.remoteId ?: s.name}"

    /** The part of an item key after the source id: the provider's own id, or the name as a fallback. */
    fun tailOf(key: String): String = key.substringAfter(':', "")
}

/**
 * Finds a channel's manual EPG match even after its key has changed underneath it.
 *
 * Item keys are `"<sourceId>:<remoteId>"`, and `sourceId` is a local row id, not anything the
 * provider knows. Delete a playlist and add it back — after a URL change, after a failed import was
 * cleaned up, after any re-import — and every channel returns with a *new* source id. The matches
 * are all still stored, pointing at keys nothing resolves any more, so every hand-made and
 * auto-applied match silently stops working. It reads exactly like "EPG matching broke".
 *
 * Three tiers, tried in order, and the first is always preferred:
 *
 *  1. the exact key — today's behaviour, unchanged;
 *  2. the same provider id under **any** source, which is what survives a re-added playlist;
 *  3. the same normalised name, for channels whose provider gives them no id at all.
 *
 * Tiers 2 and 3 apply **only when unambiguous**. If two stored matches share a tail or a name but
 * point at different guide channels, neither is used — a wrong guide on a channel is worse than no
 * guide, and the user can always match it again by hand.
 *
 * Deliberately not a schema change. Moving `epgMatches` into a Room table was considered and
 * rejected: the DataStore map is already exported and restored correctly by the backup, and this
 * reaches the same result with none of the migration risk.
 */
class EpgMatchResolver(private val matches: Map<String, String>) {

    /** Provider id → guide id, for ids that are unambiguous across every source. */
    private val byTail: Map<String, String> by lazy { unambiguous { CustomizeKeys.tailOf(it) } }

    /** Normalised tail → guide id. Answers for channels keyed by name because they have no id. */
    private val byName: Map<String, String> by lazy {
        unambiguous { EpgMatcher.normalizeForEpg(CustomizeKeys.tailOf(it)) }
    }

    /** The guide id this channel's stored match points at, or null if it has none. */
    fun epgIdFor(channel: ChannelEntity): String? {
        if (matches.isEmpty()) return null
        val key = CustomizeKeys.channel(channel)
        matches[key]?.let { return it }
        byTail[CustomizeKeys.tailOf(key)]?.let { return it }
        return byName[EpgMatcher.normalizeForEpg(channel.matchName)]
    }

    /** Groups the stored matches by [keyOf], keeping only groups that agree on one guide id. */
    private inline fun unambiguous(keyOf: (String) -> String): Map<String, String> {
        val grouped = HashMap<String, String?>(matches.size)
        for ((key, epgId) in matches) {
            val group = keyOf(key)
            if (group.isEmpty()) continue
            if (group in grouped) {
                // Seen before: agreeing entries are harmless, disagreeing ones poison the group.
                if (grouped[group] != epgId) grouped[group] = null
            } else {
                grouped[group] = epgId
            }
        }
        val result = HashMap<String, String>(grouped.size)
        for ((group, epgId) in grouped) if (epgId != null) result[group] = epgId
        return result
    }
}

/**
 * A user-created category combining items from any provider category. Lives in the
 * same customization namespace as everything else via [CustomCategory.id] ("custom:<uuid>"), so
 * hide / rename / reorder work with no new code. Membership rows live in the Room table
 * `custom_category_members` (per profile), keyed by [CustomCategory.id] as contextKey.
 */
data class CustomCategory(
    val id: String,
    val name: String,
    /** Reserved for a future icon picker; always null for now. */
    val icon: String? = null,
)

/** One browse section's customizations (categories + items) for a profile. */
data class SectionCustomizations(
    val hiddenCategories: Set<String> = emptySet(),
    /** Hidden channels/items: stable key → display label (so the unhide list can show a name). */
    val hiddenItems: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    val itemNames: Map<String, String> = emptyMap(),
    /** Categories moved by hand: key → the position they were put at. They keep it in every sort;
     *  the rest follow the sort chip around them (see [placePinned]). */
    val categoryPositions: Map<String, Int> = emptyMap(),
    /** Manual EPG match: item key → the EPG channel id to use (overrides the channel's own epg id). */
    val epgMatches: Map<String, String> = emptyMap(),
    /** Per-channel EPG time shift: item key → minutes to move the guide by (as a string, so it
     *  shares the JSON map helpers). Overrides the global offset; needed when East/West feeds of the
     *  same network share one guide and only one of them is on the guide's clock. */
    val epgShifts: Map<String, String> = emptyMap(),
    /** User-created combined categories; membership lives in Room. */
    val customCategories: List<CustomCategory> = emptyList(),
    /** Items moved OUT of a provider category into a custom category: item key → the
     *  provider category key it was moved from. The item stays in All/search/recent but leaves that
     *  folder — that's what makes a "sidebar of only my folders" possible. Stable keys, so a re-sync
     *  that re-lists the item does not undo the move. */
    val movedFromOrigin: Map<String, String> = emptyMap(),
    /** Categories behind the profile's Customize PIN (stable keys, custom ids included). They stay
     *  on the rail with a lock; see [CategoryLockView] for what they hide until the PIN opens them.
     *  The lock follows the key, so a renamed or re-added provider group comes back unlocked. */
    val lockedCategories: Set<String> = emptySet(),
    /** Derived, never stored: the locked categories the PIN has not opened this session, hidden or
     *  not. [CategoryLockView] folds them into [hiddenCategories]. */
    val sealedCategories: Set<String> = emptySet(),
    /** Derived, never stored: the part of [sealedCategories] the fold added, i.e. not hidden by the user. */
    val sealHidden: Set<String> = emptySet(),
    /** Derived, never stored: DB ids of the provider folders among [sealedCategories]. */
    val sealedCategoryIds: Set<Long> = emptySet(),
) {
    /**
     * The match lookup for this snapshot, built once and reused.
     *
     * Lazy and outside the constructor on purpose: it is derived state, so it stays out of `equals`,
     * `hashCode` and `copy`, and the guide's per-channel reads must not each rebuild it — a lineup of
     * five thousand channels asks this question five thousand times per load.
     */
    val epgMatchResolver: EpgMatchResolver by lazy { EpgMatchResolver(epgMatches) }

    /** The name the user gave this item in Customize, else the provider's. */
    fun displayName(ch: ChannelEntity): String = itemNames[CustomizeKeys.channel(ch)] ?: ch.name
    fun displayName(m: MovieEntity): String = itemNames[CustomizeKeys.movie(m)] ?: m.name
    fun displayName(s: SeriesEntity): String = itemNames[CustomizeKeys.series(s)] ?: s.name

    val isEmpty: Boolean
        get() = hiddenCategories.isEmpty() && hiddenItems.isEmpty() && categoryNames.isEmpty() &&
            itemNames.isEmpty() && categoryPositions.isEmpty() && epgMatches.isEmpty() &&
            epgShifts.isEmpty() && customCategories.isEmpty() && movedFromOrigin.isEmpty() &&
            lockedCategories.isEmpty()
}

/**
 * Per-profile content customizations (TiviMate-style hide / rename / reorder), stored as JSON in
 * DataStore — deliberately NOT a Room table, so no schema change (the DB uses destructive
 * migrations) and edits survive every re-sync via [CustomizeKeys].
 */
class CustomizationStore(private val context: Context, private val locks: CategoryLockView) {

    private fun key(profileId: Long, type: MediaType) =
        stringPreferencesKey("cust_${profileId}_${type.name}")

    fun observe(profileId: Long, type: MediaType): Flow<SectionCustomizations> =
        context.customizeStore.data
            .map { prefs -> CustomizationJson.parse(prefs[key(profileId, type)]) }
            .distinctUntilChanged()

    /** What browsing shows: [observe] with the locked categories the PIN has not opened this session
     *  counted as hidden. Customize and the store's own edits keep using [observe]. */
    fun observeVisible(profileId: Long, type: MediaType): Flow<SectionCustomizations> =
        locks.visible(profileId, type, observe(profileId, type))

    /** Whether [profileId] has a category lock that can seal anything: a locked category and the PIN. */
    fun observeHasLocks(profileId: Long): Flow<Boolean> = locks.hasLocks(profileId) { observe(profileId, it) }

    /** True when [categoryId]'s category is locked and, unless [anySession], not opened this session. */
    suspend fun sealsCategory(profileId: Long, categoryId: Long?, anySession: Boolean = false): Boolean =
        sealsItem(profileId, ItemCategory.Found(categoryId), anySession)

    /** [sealsCategory] for a row that names its item by local id, which a re-sync can leave unresolved. */
    suspend fun sealsItem(profileId: Long, item: ItemCategory, anySession: Boolean = false): Boolean =
        locks.seals(profileId, item, anySession) { type -> observe(profileId, type).first() }

    suspend fun update(profileId: Long, type: MediaType, transform: (SectionCustomizations) -> SectionCustomizations) {
        context.customizeStore.edit { prefs ->
            val k = key(profileId, type)
            val next = transform(CustomizationJson.parse(prefs[k]))
            if (next.isEmpty) prefs.remove(k) else prefs[k] = CustomizationJson.serialize(next)
        }
    }

    // --- convenience mutations ---

    suspend fun setCategoryHidden(profileId: Long, type: MediaType, catKey: String, hidden: Boolean) =
        update(profileId, type) {
            it.copy(hiddenCategories = if (hidden) it.hiddenCategories + catKey else it.hiddenCategories - catKey)
        }

    /** Put a category behind the Customize PIN, or take it out. The caller owns the PIN check. */
    suspend fun setCategoryLocked(profileId: Long, type: MediaType, catKey: String, locked: Boolean) =
        update(profileId, type) {
            it.copy(lockedCategories = if (locked) it.lockedCategories + catKey else it.lockedCategories - catKey)
        }

    /** Drops every category lock of the profile, in every section — removing the PIN does this. */
    suspend fun clearCategoryLocks(profileId: Long) {
        MediaType.entries.forEach { type -> update(profileId, type) { it.copy(lockedCategories = emptySet()) } }
    }

    /** Hide/show a whole span of categories in one atomic edit (range select in Customize). */
    suspend fun setCategoriesHidden(profileId: Long, type: MediaType, catKeys: Collection<String>, hidden: Boolean) =
        update(profileId, type) {
            it.copy(hiddenCategories = if (hidden) it.hiddenCategories + catKeys else it.hiddenCategories - catKeys)
        }

    suspend fun setItemHidden(profileId: Long, type: MediaType, itemKey: String, label: String, hidden: Boolean) =
        update(profileId, type) {
            it.copy(hiddenItems = if (hidden) it.hiddenItems + (itemKey to label) else it.hiddenItems - itemKey)
        }

    /** Hide/show a whole span of items in one atomic edit (range select in the Customize items
     *  list). [items] maps stable key → label; the label is only meaningful when hiding. */
    suspend fun setItemsHidden(profileId: Long, type: MediaType, items: Map<String, String>, hidden: Boolean) =
        update(profileId, type) {
            it.copy(hiddenItems = if (hidden) it.hiddenItems + items else it.hiddenItems - items.keys)
        }

    suspend fun renameCategory(profileId: Long, type: MediaType, catKey: String, name: String?) =
        update(profileId, type) {
            it.copy(categoryNames = if (name.isNullOrBlank()) it.categoryNames - catKey else it.categoryNames + (catKey to name.trim()))
        }

    suspend fun renameItem(profileId: Long, type: MediaType, itemKey: String, name: String?) =
        update(profileId, type) {
            it.copy(itemNames = if (name.isNullOrBlank()) it.itemNames - itemKey else it.itemNames + (itemKey to name.trim()))
        }

    /** Saves a category move: [onScreen] is the list as the move left it, [carried] the categories the
     *  user moved. Positions of categories not on screen (another playlist's) are kept. */
    suspend fun saveCategoryMove(profileId: Long, type: MediaType, onScreen: List<String>, carried: Collection<String>) =
        update(profileId, type) {
            it.copy(categoryPositions = mergeCategoryPositions(it.categoryPositions, onScreen, carried.toSet()))
        }

    /** Manually map an item to an EPG channel id (null/blank clears the override → auto-match). */
    suspend fun setEpgMatch(profileId: Long, type: MediaType, itemKey: String, epgChannelId: String?) =
        update(profileId, type) {
            val key = epgChannelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            it.copy(epgMatches = if (key == null) it.epgMatches - itemKey else it.epgMatches + (itemKey to key))
        }

    /** Shift this channel's guide by [minutes]; null clears the override → the global EPG offset.
     *  An explicit 0 IS an override — it pins one channel to the feed's own times while a global
     *  offset moves the rest. */
    suspend fun setEpgShift(profileId: Long, type: MediaType, itemKey: String, minutes: Int?) =
        update(profileId, type) {
            it.copy(epgShifts = if (minutes == null) it.epgShifts - itemKey else it.epgShifts + (itemKey to minutes.toString()))
        }

    /**
     * Applies all accepted bulk-rename results in ONE atomic edit, so a 2000-row rename
     * lands as a single DataStore write. Values are trimmed; blank values are dropped (a bulk rule
     * can never clear a name — per-row blank restore stays [renameItem]'s job).
     */
    suspend fun applyBulkRenames(profileId: Long, type: MediaType, renames: Map<String, String>) {
        val clean = renames.mapValues { (_, v) -> v.trim() }.filterValues { it.isNotEmpty() }
        if (clean.isEmpty()) return
        update(profileId, type) { it.copy(itemNames = it.itemNames + clean) }
    }

    /** Removes every rename entry for the given category keys in one atomic edit — the "↺ Restore
     *  original names" bulk path for category names. */
    suspend fun clearCategoryNames(profileId: Long, type: MediaType, keys: Set<String>) =
        update(profileId, type) { it.copy(categoryNames = it.categoryNames - keys) }

    /** Removes every rename entry for the given item keys in one atomic edit — the "↺ Restore
     *  original names" bulk path for channel/movie/series names. */
    suspend fun clearItemNames(profileId: Long, type: MediaType, keys: Set<String>) =
        update(profileId, type) { it.copy(itemNames = it.itemNames - keys) }

    // --- custom categories ---

    /** Creates a custom category with a fresh stable id ("custom:<uuid>") and returns it. */
    suspend fun createCustomCategory(profileId: Long, type: MediaType, name: String): CustomCategory {
        val cat = CustomCategory(id = "${CustomizeKeys.CUSTOM_PREFIX}${UUID.randomUUID()}", name = name.trim())
        update(profileId, type) { it.copy(customCategories = it.customCategories + cat) }
        return cat
    }

    /**
     * Records or clears a "moved out of provider category [originKey]" mark for [itemKey].
     * The browse pager for [originKey]'s folder drops the item; All/search/recent keep it.
     */
    suspend fun setItemMovedFromOrigin(profileId: Long, type: MediaType, itemKey: String, originKey: String, moved: Boolean) =
        update(profileId, type) {
            it.copy(movedFromOrigin = if (moved) it.movedFromOrigin + (itemKey to originKey) else it.movedFromOrigin - itemKey)
        }

    /** Deletes the category definition, restores provider origins for its former members, and
     *  scrubs its category-level settings. Room member rows and
     *  content_order rows are the caller's job (CustomCategoryDao.clearContext / ContentOrderDao.clearContext). */
    suspend fun deleteCustomCategory(
        profileId: Long,
        type: MediaType,
        id: String,
        restoreOriginItemKeys: Set<String> = emptySet(),
    ) =
        update(profileId, type) {
            it.copy(
                customCategories = it.customCategories.filterNot { c -> c.id == id },
                hiddenCategories = it.hiddenCategories - id,
                lockedCategories = it.lockedCategories - id,
                categoryNames = it.categoryNames - id,
                categoryPositions = it.categoryPositions - id,
                movedFromOrigin = it.movedFromOrigin - restoreOriginItemKeys,
            )
        }

    // --- backup & restore (profile/source ids are preserved by BackupManager, so keys stay valid) ---

    /** All raw customization entries (preference key → JSON) for embedding into a backup file. */
    suspend fun exportAll(): Map<String, String> =
        context.customizeStore.data.first().asMap()
            .mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }
            .toMap()

    /** Replaces all customizations with the backup's entries (empty map = clear everything). */
    suspend fun importAll(entries: Map<String, String>) {
        context.customizeStore.edit { prefs ->
            prefs.clear()
            entries.forEach { (k, v) -> if (k.startsWith("cust_")) prefs[stringPreferencesKey(k)] = v }
        }
    }

    /** Merge-restore (backup): overwrites only the provided keys, keeping every other profile's
     *  customizations untouched (restore must never wipe profiles that aren't in the file). */
    suspend fun mergeAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        context.customizeStore.edit { prefs ->
            entries.forEach { (k, v) -> if (k.startsWith("cust_")) prefs[stringPreferencesKey(k)] = v }
        }
    }
}

/**
 * The category positions after a move made on the rail as it is shown — one playlist's categories when the
 * rail shows one. The categories on screen take their positions from the move; every other category
 * (another playlist's) keeps the position it had.
 */
fun mergeCategoryPositions(stored: Map<String, Int>, onScreen: List<String>, carried: Set<String>): Map<String, Int> =
    stored.filterKeys { it !in onScreen } + pinnedAfterMove(onScreen, stored.keys, carried)

/** One rail entry after customizations are applied: a provider folder ([categoryId] set) or a user
 *  custom combined category ([customId] set). */
data class CustomizedCategory(
    val key: String,
    val displayName: String,
    val categoryId: Long? = null,
    val customId: String? = null,
    /** Behind the Customize PIN; [sealed] while this session has not opened it (see [withLocks]). */
    val locked: Boolean = false,
    val sealed: Boolean = false,
)

/**
 * [applyCustomizations] extended with the user's custom combined categories: provider
 * folders and custom categories are merged into ONE ordered list, so hide / rename / reorder apply
 * uniformly to both — their keys share the [CustomizeKeys] namespace, so a custom category drops
 * into `hiddenCategories`, `categoryNames` and `categoryPositions` with no extra code.
 *
 * Hiding a custom category only removes its rail entry — its items still legitimately exist in
 * their provider categories, so All/search/recent (which filter by resolved DB ids) are untouched.
 *
 * Categories never moved follow the sort: A–Z by displayed name when [alphabetical], else the natural
 * order. Moved ones keep the position they were put at, in either sort ([placePinned]).
 */
fun List<CategoryEntity>.applyCustomizationsWithCustoms(
    c: SectionCustomizations,
    customs: List<CustomCategory>,
    alphabetical: Boolean = false,
): List<CustomizedCategory> {
    val visibleCats =
        if (c.hiddenCategories.isEmpty()) this
        else filter { CustomizeKeys.category(it) !in c.hiddenCategories }
    // User folders lead the natural rail order; a moved category keeps its own place below.
    val entries = customs.filter { it.id !in c.hiddenCategories }.map { cc ->
        CustomizedCategory(
            key = cc.id,
            displayName = c.categoryNames[cc.id] ?: cc.name,
            customId = cc.id,
            locked = cc.id in c.lockedCategories,
            sealed = cc.id in c.sealedCategories,
        )
    } + visibleCats.map { cat ->
        val key = CustomizeKeys.category(cat)
        CustomizedCategory(
            key = key,
            displayName = c.categoryNames[key] ?: cat.name,
            categoryId = cat.id,
            locked = key in c.lockedCategories,
            sealed = key in c.sealedCategories,
        )
    }
    val (pinned, rest) = entries.partition { it.key in c.categoryPositions }
    val sorted = if (alphabetical) rest.sortedBy { it.displayName.lowercase() } else rest
    return placePinned(sorted, pinned.map { it to c.categoryPositions.getValue(it.key) })
}
