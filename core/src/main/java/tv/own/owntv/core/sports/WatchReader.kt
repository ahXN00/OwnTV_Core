package tv.own.owntv.core.sports

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeLite
import tv.own.owntv.core.live.epgKeyOf
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.model.Match

/**
 * The reads "Watch live" needs, behind an interface so [WatchReader] is unit-tested on a fake.
 * Every channel returned is one the active profile may see (hidden channels and categories removed).
 */
interface WatchData {
    /** Changes whenever a recompute is due: a guide write, a playlist sync, a manual guide match, a hide. */
    suspend fun fingerprint(): String

    suspend fun programmes(from: Long, to: Long, minStop: Long): List<EpgProgrammeLite>

    suspend fun descriptions(ids: List<Long>): Map<Long, String>

    /** The visible channels reading their guide from each of [epgKeys] (normalised ids). */
    suspend fun channelsForGuideKeys(epgKeys: Set<String>): Map<String, List<ChannelEntity>>

    /** Visible channels whose name has any of [words] as a word prefix (channel full-text search). */
    suspend fun channelsNamingAny(words: List<String>): List<ChannelEntity>

    /** Visible channels whose name has all of [words]. */
    suspend fun channelsNamingAll(words: List<String>): List<ChannelEntity>
}

/**
 * Ranks the user's channels for matches, and caches the answer. It runs
 * only when a screen asks — the match page's Find channels — never for a whole board.
 *
 * Matches are grouped by kick-off: one guide read per slot, however many matches start then. The
 * cache is keyed by match and by [WatchData.fingerprint], so a score refresh — a new [Match] object
 * for the same match — never triggers a run; a guide sync, a playlist sync or a new match does. The
 * remembered pick is applied on every read, outside the cache, so "Always use for…" shows at once.
 */
class WatchReader(private val data: WatchData) {

    private class Raw(val guide: List<WatchOption>, val event: List<ChannelEntity>, val likely: List<ChannelEntity>)

    private val lock = Mutex()
    private var cacheFingerprint: String? = null
    private val cache = HashMap<String, Raw>()
    private val likelyByLeague = HashMap<String, List<ChannelEntity>>()

    /** Forget every ranked match, so the next [watchFor] matches again from the guide ("Refresh now"). */
    suspend fun clear() = lock.withLock {
        cache.clear(); likelyByLeague.clear(); cacheFingerprint = null
    }

    /** How many match runs have happened (tests prove a score refresh adds none). */
    var runs: Int = 0
        private set

    /** Every match's channels, best first. [rememberedFor] gives the remembered channel per league key. Runs on Dispatchers.Default whoever calls it: a slot can read thousands of guide titles. */
    suspend fun watchFor(
        matches: List<Match>,
        rememberedFor: (Match) -> RememberedChannel? = { null },
    ): Map<String, List<WatchOption>> = withContext(Dispatchers.Default) { lock.withLock {
        val fingerprint = data.fingerprint()
        if (fingerprint != cacheFingerprint) {
            cache.clear(); likelyByLeague.clear(); cacheFingerprint = fingerprint
        }
        val todo = matches.filter { it.key !in cache }.distinctBy { it.key }
        for ((start, slot) in todo.groupBy { it.startMs }) runSlot(start, slot)
        matches.associate { m -> m.key to ranked(cache[m.key] ?: Raw(emptyList(), emptyList(), emptyList()), rememberedFor(m)) }
    } }

    /**
     * [match]'s channels if a screen already found them and the guide and playlists have not changed
     * since; null when they were never searched. Never searches itself (the hub's Remind me, the player).
     */
    suspend fun found(match: Match, rememberedFor: (Match) -> RememberedChannel? = { null }): List<WatchOption>? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (data.fingerprint() != cacheFingerprint) return@withLock null
                cache[match.key]?.let { ranked(it, rememberedFor(match)) }
            }
        }

    private suspend fun ranked(raw: Raw, remembered: RememberedChannel?): List<WatchOption> {
        val rememberedCandidates = remembered?.let { data.channelsNamingAll(ftsWords(it.name)) }.orEmpty()
        return WatchMatcher.rank(raw.guide, raw.event, raw.likely, rememberedCandidates, remembered)
    }

    /** One kick-off slot: one guide read, one synopsis read for near-misses, then the name searches. */
    private suspend fun runSlot(start: Long, slot: List<Match>) {
        runs++
        val keys = slot.map { WatchMatcher.Keys(it) }
        val programmes = data.programmes(start - SLOT_BEFORE_MS, start + SLOT_AFTER_MS, minStop = start)
        val tokens = programmes.associate { it.id to TeamNames.tokens(it.title).toSet() }
        val words = programmes.associate { it.id to CompetitionNames.words(it.title) }
        val confirmed = HashMap<String, MutableList<EpgProgrammeLite>>()
        val partial = HashMap<Long, MutableList<WatchMatcher.Keys>>()
        for (p in programmes) for (k in keys) {
            when (WatchMatcher.hit(k, tokens.getValue(p.id), words.getValue(p.id))) {
                WatchMatcher.Hit.CONFIRMED -> confirmed.getOrPut(k.match.key) { mutableListOf() } += p
                WatchMatcher.Hit.PARTIAL -> partial.getOrPut(p.id) { mutableListOf() } += k
                WatchMatcher.Hit.NONE -> Unit
            }
        }
        if (partial.isNotEmpty()) {
            val texts = partial.keys.take(MAX_DESCRIPTIONS).chunked(ID_CHUNK).fold(HashMap<Long, String>()) { acc, ids ->
                acc.apply { putAll(data.descriptions(ids)) }
            }
            val byId = programmes.associateBy { it.id }
            for ((id, text) in texts) {
                val all = tokens.getValue(id) + TeamNames.tokens(text)
                // The empty word keeps a name from running across the title's end into the synopsis.
                val allWords = words.getValue(id) + "" + CompetitionNames.words(text)
                partial[id]?.forEach { k ->
                    if (WatchMatcher.hit(k, all, allWords) == WatchMatcher.Hit.CONFIRMED) {
                        confirmed.getOrPut(k.match.key) { mutableListOf() } += byId.getValue(id)
                    }
                }
            }
        }
        val channelsByKey = data.channelsForGuideKeys(confirmed.values.flatten().map { it.epgChannelId }.toSet())
        for (k in keys) {
            val m = k.match
            val guide = confirmed[m.key].orEmpty().flatMap { p ->
                channelsByKey[p.epgChannelId].orEmpty().map { WatchOption(it, WatchTier.GUIDE, p.title) }
            }
            val event = if (k.searchWords.isEmpty()) emptyList()
            else data.channelsNamingAny(k.searchWords).filter { WatchMatcher.isEventChannel(k, it.name) }
            // Every name it goes by, the catalogue's brand name first: "Sky Sport F1" never says "Formula".
            val likely = likelyByLeague.getOrPut(m.leagueSlug) {
                k.competitionNames.flatMap { data.channelsNamingAll(ftsWords(it)) }.distinctBy { it.id }
            }.plus(k.broadcasterChannels()).filter { WatchMatcher.isLikely(k, it.name) }
            cache[m.key] = Raw(guide, event, likely)
        }
        Log.d(TAG, "slot start=$start matches=${slot.size} programmes=${programmes.size} near=${partial.size}")
    }

    private suspend fun WatchMatcher.Keys.broadcasterChannels(): List<ChannelEntity> =
        match.broadcasts.flatMap { b -> likelyByLeague.getOrPut("b:${b.name}") { data.channelsNamingAll(ftsWords(b.name)) } }

    companion object {
        internal const val TAG = "OwnTV-Sports"

        /** A slot reads programmes starting two hours before kick-off (pre-match shows) to 30 min after. */
        const val SLOT_BEFORE_MS = 2 * 60 * 60_000L
        const val SLOT_AFTER_MS = 30 * 60_000L
        private const val MAX_DESCRIPTIONS = 600
        private const val ID_CHUNK = 400

        /** The words of [name] for a channel search, accents kept (the full-text index does not fold them). */
        internal fun ftsWords(name: String): List<String> =
            name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.map { it.lowercase() }
    }
}

/** [WatchData] on the database, for the active profile — the reads Search's "On TV" already does. */
class RoomWatchData(
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) : WatchData {

    private class Scope(val sourceIds: List<Long>, val visible: (ChannelEntity) -> Boolean, val cust: tv.own.owntv.core.customize.SectionCustomizations)

    private suspend fun scope(): Scope {
        val sources = activeProfileSources(settings, sourceDao).first()
        val cust = customize.observe(sources.profileId, MediaType.LIVE).first()
        val ids = sources.liveSourceIds
        val isKids = profileDao.getById(sources.profileId)?.isKids == true
        val hiddenCats = if (cust.hiddenCategories.isEmpty() && !isKids || ids.isEmpty()) emptySet()
        else AdultCategoryClassifier.hiddenCategoryIds(categoryDao.observe(ids, MediaType.LIVE).first(), cust.hiddenCategories, isKids)
        return Scope(ids, { ch -> CustomizeKeys.channel(ch) !in cust.hiddenItems && (ch.categoryId == null || ch.categoryId !in hiddenCats) }, cust)
    }

    override suspend fun fingerprint(): String = withContext(Dispatchers.IO) {
        val sources = activeProfileSources(settings, sourceDao).first()
        val cust = customize.observe(sources.profileId, MediaType.LIVE).first()
        listOf(
            sources.profileId,
            sources.sources.filter { it.syncLive }.joinToString(",") { "${it.id}@${it.lastSyncAt}" },
            epgDao.newestProgrammeId(),
            cust.epgMatches.hashCode(), cust.hiddenItems.keys.hashCode(), cust.hiddenCategories.hashCode(),
        ).joinToString("|")
    }

    override suspend fun programmes(from: Long, to: Long, minStop: Long): List<EpgProgrammeLite> =
        withContext(Dispatchers.IO) { epgDao.sportsProgrammes(from, to, minStop, MAX_PROGRAMMES) }

    override suspend fun descriptions(ids: List<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        epgDao.programmeDescriptions(ids).mapNotNull { r -> r.description?.let { r.id to it } }.toMap()
    }

    override suspend fun channelsForGuideKeys(epgKeys: Set<String>): Map<String, List<ChannelEntity>> = withContext(Dispatchers.IO) {
        if (epgKeys.isEmpty()) return@withContext emptyMap()
        val s = scope()
        if (s.sourceIds.isEmpty()) return@withContext emptyMap()
        // A channel reads its guide by its manual match first and its own id second, as the Guide does.
        val matchedTails = s.cust.epgMatches.filterValues { it.trim().lowercase() in epgKeys }
            .keys.map { CustomizeKeys.tailOf(it) }.filter { it.isNotEmpty() }.distinct()
        val keys = epgKeys.toList()
        (keys.chunked(KEY_CHUNK).flatMap { channelDao.byEpgKeys(s.sourceIds, it) } +
            matchedTails.chunked(KEY_CHUNK).flatMap { channelDao.byRemoteIdsOrNames(s.sourceIds, it) })
            .distinctBy { it.id }
            .filter(s.visible)
            .sortedWith(compareBy({ s.sourceIds.indexOf(it.sourceId) }, { it.sortOrder }))
            .mapNotNull { ch -> epgKeyOf(ch, s.cust)?.takeIf { it in epgKeys }?.let { it to ch } }
            .groupBy({ it.first }, { it.second })
    }

    override suspend fun channelsNamingAny(words: List<String>): List<ChannelEntity> =
        search(words.joinToString(" OR ") { "$it*" })

    override suspend fun channelsNamingAll(words: List<String>): List<ChannelEntity> =
        search(words.joinToString(" ") { "$it*" })

    private suspend fun search(fts: String): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (fts.isBlank()) return@withContext emptyList()
        val s = scope()
        if (s.sourceIds.isEmpty()) return@withContext emptyList()
        runCatching { channelDao.searchListDetailedFts(fts, s.sourceIds, MAX_CHANNELS) }
            .getOrElse { Log.w(WatchReader.TAG, "channel search failed: ${it.message}"); emptyList() }
            .map { it.channel }
            .filter(s.visible)
            .sortedWith(compareBy({ s.sourceIds.indexOf(it.sourceId) }, { it.sortOrder }))
    }

    private companion object {
        /** A very large guide's slot is ~20k rows; the cap only guards a malformed one. */
        const val MAX_PROGRAMMES = 30_000
        const val MAX_CHANNELS = 300
        const val KEY_CHUNK = 400
    }
}
