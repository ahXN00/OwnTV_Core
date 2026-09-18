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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

private val Context.customizeStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_customizations")

/**
 * Stable identity for a category/channel that survives re-sync (content rows are clear-then-insert,
 * so DB ids change every refresh). Xtream rows key on the provider's remote id; M3U rows (no stable
 * id) fall back to the name — so a provider-side rename can detach an M3U customization.
 */
object CustomizeKeys {
    /** Prefix of custom combined-category ids (issue #87) — they share the key namespace, so
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
        return byName[EpgMatcher.normalizeForEpg(channel.name)]
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
 * A user-created category (issue #87) combining items from any provider category. Lives in the
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
    /** Explicit category order (keys, first = top). Categories not listed follow in natural order. */
    val categoryOrder: List<String> = emptyList(),
    /** Manual EPG match: item key → the EPG channel id to use (overrides the channel's own epg id). */
    val epgMatches: Map<String, String> = emptyMap(),
    /** Per-channel EPG time shift: item key → minutes to move the guide by (as a string, so it
     *  shares the JSON map helpers). Overrides the global offset; needed when East/West feeds of the
     *  same network share one guide and only one of them is on the guide's clock. */
    val epgShifts: Map<String, String> = emptyMap(),
    /** User-created combined categories (issue #87); membership lives in Room. */
    val customCategories: List<CustomCategory> = emptyList(),
    /** Items moved OUT of a provider category into a custom category (issue #87): item key → the
     *  provider category key it was moved from. The item stays in All/search/recent but leaves that
     *  folder — that's what makes a "sidebar of only my folders" possible. Stable keys, so a re-sync
     *  that re-lists the item does not undo the move. */
    val movedFromOrigin: Map<String, String> = emptyMap(),
) {
    /**
     * The match lookup for this snapshot, built once and reused.
     *
     * Lazy and outside the constructor on purpose: it is derived state, so it stays out of `equals`,
     * `hashCode` and `copy`, and the guide's per-channel reads must not each rebuild it — a lineup of
     * five thousand channels asks this question five thousand times per load.
     */
    val epgMatchResolver: EpgMatchResolver by lazy { EpgMatchResolver(epgMatches) }

    val isEmpty: Boolean
        get() = hiddenCategories.isEmpty() && hiddenItems.isEmpty() && categoryNames.isEmpty() &&
            itemNames.isEmpty() && categoryOrder.isEmpty() && epgMatches.isEmpty() &&
            epgShifts.isEmpty() && customCategories.isEmpty() && movedFromOrigin.isEmpty()
}

/**
 * Per-profile content customizations (TiviMate-style hide / rename / reorder), stored as JSON in
 * DataStore — deliberately NOT a Room table, so no schema change (the DB uses destructive
 * migrations) and edits survive every re-sync via [CustomizeKeys].
 */
class CustomizationStore(private val context: Context) {

    private fun key(profileId: Long, type: MediaType) =
        stringPreferencesKey("cust_${profileId}_${type.name}")

    fun observe(profileId: Long, type: MediaType): Flow<SectionCustomizations> =
        context.customizeStore.data
            .map { prefs -> parse(prefs[key(profileId, type)]) }
            .distinctUntilChanged()

    suspend fun update(profileId: Long, type: MediaType, transform: (SectionCustomizations) -> SectionCustomizations) {
        context.customizeStore.edit { prefs ->
            val k = key(profileId, type)
            val next = transform(parse(prefs[k]))
            if (next.isEmpty) prefs.remove(k) else prefs[k] = serialize(next)
        }
    }

    // --- convenience mutations ---

    suspend fun setCategoryHidden(profileId: Long, type: MediaType, catKey: String, hidden: Boolean) =
        update(profileId, type) {
            it.copy(hiddenCategories = if (hidden) it.hiddenCategories + catKey else it.hiddenCategories - catKey)
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

    suspend fun setCategoryOrder(profileId: Long, type: MediaType, orderedKeys: List<String>) =
        update(profileId, type) { it.copy(categoryOrder = orderedKeys) }

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
     * Applies all accepted bulk-rename results (issue #86) in ONE atomic edit, so a 2000-row rename
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

    // --- custom categories (issue #87) ---

    /** Creates a custom category with a fresh stable id ("custom:<uuid>") and returns it. */
    suspend fun createCustomCategory(profileId: Long, type: MediaType, name: String): CustomCategory {
        val cat = CustomCategory(id = "${CustomizeKeys.CUSTOM_PREFIX}${UUID.randomUUID()}", name = name.trim())
        update(profileId, type) { it.copy(customCategories = it.customCategories + cat) }
        return cat
    }

    /**
     * Records or clears a "moved out of provider category [originKey]" mark for [itemKey] (issue
     * #87). The browse pager for [originKey]'s folder drops the item; All/search/recent keep it.
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
                categoryNames = it.categoryNames - id,
                categoryOrder = it.categoryOrder - id,
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

    // --- JSON (org.json, matching BackupManager's style) ---

    private fun parse(raw: String?): SectionCustomizations {
        if (raw.isNullOrBlank()) return SectionCustomizations()
        return runCatching {
            val o = JSONObject(raw)
            SectionCustomizations(
                hiddenCategories = o.optJSONArray("hiddenCats").toStringSet(),
                hiddenItems = o.optJSONObject("hiddenItems").toStringMap(),
                categoryNames = o.optJSONObject("catNames").toStringMap(),
                itemNames = o.optJSONObject("itemNames").toStringMap(),
                categoryOrder = o.optJSONArray("catOrder").toStringList(),
                epgMatches = o.optJSONObject("epgMatch").toStringMap(),
                epgShifts = o.optJSONObject("epgShift").toStringMap(),
                customCategories = o.optJSONArray("customCats").toCustomCategories(),
                movedFromOrigin = o.optJSONObject("movedFrom").toStringMap(),
            )
        }.getOrDefault(SectionCustomizations())
    }

    private fun serialize(c: SectionCustomizations): String = JSONObject().apply {
        put("hiddenCats", JSONArray(c.hiddenCategories.toList()))
        put("hiddenItems", JSONObject(c.hiddenItems as Map<*, *>))
        put("catNames", JSONObject(c.categoryNames as Map<*, *>))
        put("itemNames", JSONObject(c.itemNames as Map<*, *>))
        put("catOrder", JSONArray(c.categoryOrder))
        put("epgMatch", JSONObject(c.epgMatches as Map<*, *>))
        put("epgShift", JSONObject(c.epgShifts as Map<*, *>))
        put("movedFrom", JSONObject(c.movedFromOrigin as Map<*, *>))
        put(
            "customCats",
            JSONArray(c.customCategories.map {
                JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon)
            }),
        )
    }.toString()

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = HashMap<String, String>()
        keys().forEach { k -> out[k] = getString(k) }
        return out
    }

    private fun JSONArray?.toCustomCategories(): List<CustomCategory> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CustomCategory(
                id = id,
                name = o.optString("name"),
                icon = o.optString("icon").ifEmpty { null },
            )
        }
    }
}

/**
 * Applies category customizations: hidden categories drop out, custom names replace the originals,
 * and reordered keys come first (in their stored order). The rest keep natural (playlist) order, or
 * sort A–Z by displayed name when [alphaRest] is set — manual moves always stay pinned on top.
 */
fun List<CategoryEntity>.applyCustomizations(
    c: SectionCustomizations,
    alphaRest: Boolean = false,
): List<Pair<CategoryEntity, String>> {
    val visible =
        if (c.hiddenCategories.isEmpty()) this
        else filter { CustomizeKeys.category(it) !in c.hiddenCategories }
    val named = visible.map { cat -> cat to (c.categoryNames[CustomizeKeys.category(cat)] ?: cat.name) }
    val orderIndex = c.categoryOrder.withIndex().associate { (i, k) -> k to i }
    val (pinned, rest) = named.partition { (cat, _) -> CustomizeKeys.category(cat) in orderIndex }
    return pinned.sortedBy { (cat, _) -> orderIndex.getValue(CustomizeKeys.category(cat)) } +
        (if (alphaRest) rest.sortedBy { (_, name) -> name.lowercase() } else rest)
}

/** One rail entry after customizations are applied: a provider folder ([categoryId] set) or a user
 *  custom combined category ([customId] set, issue #87). */
data class CustomizedCategory(
    val key: String,
    val displayName: String,
    val categoryId: Long? = null,
    val customId: String? = null,
)

/**
 * [applyCustomizations] extended with the user's custom combined categories (issue #87): provider
 * folders and custom categories are merged into ONE ordered list, so hide / rename / reorder apply
 * uniformly to both — their keys share the [CustomizeKeys] namespace, so a custom category drops
 * into `hiddenCategories`, `categoryNames` and `categoryOrder` with no extra code.
 *
 * Hiding a custom category only removes its rail entry — its items still legitimately exist in
 * their provider categories, so All/search/recent (which filter by resolved DB ids) are untouched.
 */
fun List<CategoryEntity>.applyCustomizationsWithCustoms(
    c: SectionCustomizations,
    customs: List<CustomCategory>,
    alphaRest: Boolean = false,
): List<CustomizedCategory> {
    val visibleCats =
        if (c.hiddenCategories.isEmpty()) this
        else filter { CustomizeKeys.category(it) !in c.hiddenCategories }
    // User folders lead the natural rail order, matching the agreed mockup. Explicit manual order
    // still wins through categoryOrder below.
    val entries = customs.filter { it.id !in c.hiddenCategories }.map { cc ->
        CustomizedCategory(
            key = cc.id,
            displayName = c.categoryNames[cc.id] ?: cc.name,
            customId = cc.id,
        )
    } + visibleCats.map { cat ->
        CustomizedCategory(
            key = CustomizeKeys.category(cat),
            displayName = c.categoryNames[CustomizeKeys.category(cat)] ?: cat.name,
            categoryId = cat.id,
        )
    }
    val orderIndex = c.categoryOrder.withIndex().associate { (i, k) -> k to i }
    val (pinned, rest) = entries.partition { it.key in orderIndex }
    return pinned.sortedBy { orderIndex.getValue(it.key) } +
        (if (alphaRest) rest.sortedBy { it.displayName.lowercase() } else rest)
}
