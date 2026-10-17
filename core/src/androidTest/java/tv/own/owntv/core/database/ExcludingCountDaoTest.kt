package tv.own.owntv.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

/**
 * The lock-aware counts against a real database: an item in an excluded (locked) category drops out,
 * an item with no category stays, a favorite or member whose item no longer exists counts in neither
 * form, and an empty exclusion list counts exactly what the plain query does.
 */
@RunWith(AndroidJUnit4::class)
class ExcludingCountDaoTest {

    private lateinit var db: OwnTVDatabase
    private var profile = 0L
    private var source = 0L
    private var locked = 0L
    private var open = 0L

    @Before
    fun setUp() = runBlocking {
        db = ownTVTestDatabase()
        profile = db.profileDao().insert(ProfileEntity(name = "Test", avatarColor = 0))
        source = db.sourceDao().insert(SourceEntity(name = "Test", type = SourceType.M3U, url = "http://test"))
        val (a, b) = db.categoryDao().insertAll(
            listOf(
                CategoryEntity(sourceId = source, mediaType = MediaType.LIVE, name = "Locked"),
                CategoryEntity(sourceId = source, mediaType = MediaType.LIVE, name = "Open"),
            ),
        )
        locked = a
        open = b
        db.channelDao().insertAll(
            listOf(
                channel(1, locked, catchup = true),
                channel(2, locked),
                channel(3, open, catchup = true),
                channel(4, null, catchup = true),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Long, categoryId: Long?, catchup: Boolean = false) = ChannelEntity(
        id = id, sourceId = source, categoryId = categoryId, name = "Ch $id",
        streamUrl = "http://test/$id.ts", remoteId = "$id", catchup = catchup,
    )

    @Test
    fun channelCountsDropOnlyTheExcludedCategory() = runBlocking {
        val channels = db.channelDao()
        val sources = listOf(source)
        assertEquals(2, channels.countAllExcluding(sources, listOf(locked)).first())
        assertEquals(2, channels.observeCatchupCountExcluding(sources, listOf(locked)).first())
        assertEquals(channels.countAll(sources).first(), channels.countAllExcluding(sources, emptyList()).first())
        assertEquals(
            channels.observeCatchupCount(sources).first(),
            channels.observeCatchupCountExcluding(sources, emptyList()).first(),
        )
    }

    @Test
    fun favoritesAndHistoryKeepUncategorisedItemsAndSkipOrphans() = runBlocking {
        listOf(1L, 3L, 4L, ORPHAN).forEach {
            db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = it))
            db.historyDao().record(WatchHistoryEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = it))
        }
        val channels = db.channelDao()
        val sources = listOf(source)
        assertEquals(2, channels.countFavoritesExcluding(profile, sources, listOf(locked)).first())
        assertEquals(2, channels.countHistoryExcluding(profile, sources, listOf(locked)).first())
        assertEquals(
            channels.countFavorites(profile, sources).first(),
            channels.countFavoritesExcluding(profile, sources, emptyList()).first(),
        )
        assertEquals(
            channels.countHistory(profile, sources).first(),
            channels.countHistoryExcluding(profile, sources, emptyList()).first(),
        )
    }

    @Test
    fun customCategoryCountsKeepUncategorisedItemsAndSkipOrphans() = runBlocking {
        val custom = db.customCategoryDao()
        custom.insertAll(
            listOf(1L, 3L, 4L, ORPHAN).mapIndexed { i, id -> member(FIRST, id, i) } + member(SECOND, 2L, 0),
        )
        val sources = listOf(source)
        assertEquals(2, custom.countMembersExcluding(profile, MediaType.LIVE, FIRST, sources, listOf(locked)).first())
        assertEquals(
            custom.countMembers(profile, MediaType.LIVE, FIRST, sources).first(),
            custom.countMembersExcluding(profile, MediaType.LIVE, FIRST, sources, emptyList()).first(),
        )
        val contexts = listOf(FIRST, SECOND)
        assertEquals(
            mapOf(FIRST to 2),
            custom.observeCountsByContextsExcluding(profile, MediaType.LIVE, contexts, sources, listOf(locked))
                .first().associate { it.contextKey to it.count },
        )
        assertEquals(
            custom.observeCountsByContexts(profile, MediaType.LIVE, contexts, sources).first().toSet(),
            custom.observeCountsByContextsExcluding(profile, MediaType.LIVE, contexts, sources, emptyList()).first().toSet(),
        )
    }

    private fun member(context: String, itemId: Long, position: Int) = CustomCategoryMemberEntity(
        profileId = profile, mediaType = MediaType.LIVE, contextKey = context, itemId = itemId, position = position,
    )

    private companion object {
        const val ORPHAN = 999L
        const val FIRST = "custom:first"
        const val SECOND = "custom:second"
    }
}
