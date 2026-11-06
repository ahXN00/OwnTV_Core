package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.ChannelFacets

/**
 * One version of a live channel: the same channel in another quality, category or playlist. [name] is
 * the name the user sees (a rename in Customize applied), [tags] the provider's quality marks.
 */
data class ChannelVersion(
    val channel: ChannelEntity,
    val name: String,
    val tags: List<String>,
    val qualityRank: Int,
    val categoryName: String?,
    val playlistName: String?,
)

/**
 * Which channels count as versions of one another: the player's "Sources" list in both apps, so a
 * channel that breaks up can be swapped for its twin without hunting for it.
 */
object ChannelVersionRules {
    /** Shorter keys match too much ("TV", "24"): such a channel is matched by its guide id alone. */
    private const val MIN_KEY_LENGTH = 3

    /**
     * The same channel when their names agree once the country prefix and quality marks are gone
     * ([ChannelFacets.portableKey]) and they name no two different countries — "ES| DAZN 1" and
     * "AR| DAZN 1" are different feeds — or when both read the same guide channel and one name's
     * words all appear in the other. That check is needed because providers file sister channels under one guide id
     * ("Sky Sports Main Events" under Sky Sports News's).
     */
    fun same(a: ChannelFacets, aGuide: String?, b: ChannelFacets, bGuide: String?): Boolean {
        if (!aGuide.isNullOrEmpty() && aGuide == bGuide && sharesWord(a, b)) return true
        val key = a.portableKey
        if (key.length < MIN_KEY_LENGTH || key != b.portableKey) return false
        return a.country == null || b.country == null || a.country == b.country
    }

    /** One key's words all in the other: "Sky Sports News" vs "Sky Sports News HQ" passes, vs "Sky Sports Main Events" fails. */
    private fun sharesWord(a: ChannelFacets, b: ChannelFacets): Boolean {
        val x = a.portableKey.split(' ').filter { it.length >= 2 }.toSet()
        val y = b.portableKey.split(' ').filter { it.length >= 2 }.toSet()
        return x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
    }

    /** The channel playing ([playingId]) first; then best picture, the playlists' order and the provider's own. */
    fun ordered(versions: List<ChannelVersion>, sourceOrder: List<Long>, playingId: Long = -1): List<ChannelVersion> =
        versions.sortedWith(
            compareByDescending<ChannelVersion> { it.channel.id == playingId }
                .thenByDescending { it.qualityRank }
                .thenBy { sourceOrder.indexOf(it.channel.sourceId) }
                .thenBy { it.channel.sortOrder },
        )

    /** The words a full-text search for [facets] needs: the name's own words its key kept. */
    fun searchWords(facets: ChannelFacets): List<String> {
        val keyWords = facets.portableKey.split(' ').toSet()
        return facets.name.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.lowercase() }
            .filter { it.length >= 2 && it in keyWords }
            .distinct()
    }
}

/**
 * The versions of a channel among everything the active profile can see — the same reach as the
 * channel list: hidden channels, hidden and locked categories, and a Kids profile's adult categories
 * are left out. Read only when the user asks (the Sources button), and kept per channel until the
 * playlists or the profile's customizations change, so asking again costs nothing.
 */
class ChannelVersions(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) {
    private class Cached(val fingerprint: String, val versions: List<ChannelVersion>)

    private val cache = object : LinkedHashMap<Long, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?) = size > CACHE_MAX
    }

    /** Every version of [channel], [channel] itself included, best first. */
    suspend fun of(channel: ChannelEntity): List<ChannelVersion> = withContext(Dispatchers.IO) {
        val sources = activeProfileSources(settings, sourceDao).first()
        val ids = sources.liveSourceIds
        // observeVisible: a locked category the PIN has not opened counts as hidden, as in every list.
        val cust = customize.observeVisible(sources.profileId, MediaType.LIVE).first()
        val fingerprint = listOf(
            sources.profileId,
            sources.sources.filter { it.syncLive }.joinToString(",") { "${it.id}@${it.lastSyncAt}" },
            cust.hiddenItems.keys.hashCode(), cust.hiddenCategories.hashCode(), cust.itemNames.hashCode(), cust.epgMatches.hashCode(),
        ).joinToString("|")
        synchronized(cache) { cache[channel.id]?.takeIf { it.fingerprint == fingerprint }?.let { return@withContext it.versions } }
        if (ids.isEmpty()) return@withContext emptyList()

        val isKids = profileDao.getById(sources.profileId)?.isKids == true
        val categories = categoryDao.observe(ids, MediaType.LIVE).first()
        val hiddenCats = AdultCategoryClassifier.hiddenCategoryIds(categories, cust.hiddenCategories, isKids)
        val categoryNames = categories.associate { it.id to it.name }
        val playlistNames = sources.sources.associate { it.id to it.name }
        fun visible(ch: ChannelEntity) =
            CustomizeKeys.channel(ch) !in cust.hiddenItems && (ch.categoryId == null || ch.categoryId !in hiddenCats)

        val facets = ChannelFacets.of(channel.matchName)
        val guide = epgKeyOf(channel, cust)
        val byGuide = guide?.let { channelDao.byEpgKeys(ids, listOf(it)) }.orEmpty()
        val words = ChannelVersionRules.searchWords(facets)
        val byName = if (words.isEmpty()) emptyList() else runCatching {
            channelDao.searchListDetailedFts(words.joinToString(" ") { "$it*" }, ids, MAX_CANDIDATES).map { it.channel }
        }.getOrElse { Log.w(TAG, "channel search failed: ${it.message}"); emptyList() }

        val versions = (listOf(channel) + byGuide + byName)
            .distinctBy { it.id }
            .filter { it.id == channel.id || (visible(it) && ChannelVersionRules.same(facets, guide, ChannelFacets.of(it.matchName), epgKeyOf(it, cust))) }
            .map { ch ->
                val shown = cust.itemNames[CustomizeKeys.channel(ch)] ?: ch.name
                val parsed = ProviderTags.parse(ch.name)
                ChannelVersion(
                    channel = ch,
                    name = ProviderTags.parse(shown).name,
                    tags = parsed.tags,
                    qualityRank = ChannelFacets.of(ch.matchName).qualityRank,
                    categoryName = ch.categoryId?.let { categoryNames[it] }?.let { ProviderTags.parse(it).name },
                    playlistName = playlistNames[ch.sourceId],
                )
            }
        val ordered = ChannelVersionRules.ordered(versions, ids, channel.id)
        synchronized(cache) { cache[channel.id] = Cached(fingerprint, ordered) }
        ordered
    }

    private companion object {
        const val TAG = "ChannelVersions"
        const val CACHE_MAX = 32
        const val MAX_CANDIDATES = 200
    }
}
