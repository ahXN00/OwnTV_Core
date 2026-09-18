package tv.own.owntv.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.GuideCandidates

/**
 * The two candidate queries, against a real database.
 *
 * What is being proved is a *negative*: that neither query filters by source. The unit tests cover
 * the merge; only a database can show that a row belonging to a source no profile references still
 * comes back, which is the condition behind the "no guide channels match" report.
 */
@RunWith(AndroidJUnit4::class)
class GuideCandidateDaoTest {

    private lateinit var db: OwnTVDatabase
    private lateinit var candidates: GuideCandidates

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OwnTVDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        candidates = GuideCandidates(db.epgDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun rowsUnderAnUnreferencedSourceAreStillCandidates() = runBlocking {
        // Source 4242 is in no profile and no source table — exactly what a deleted-and-re-added
        // playlist, or a standalone EPG feed, leaves behind. The old query filtered these out while
        // the guide kept drawing their programmes.
        db.epgDao().upsertChannels(
            listOf(EpgChannelEntity(sourceId = 4242, epgChannelId = "bbc1", displayName = "BBC One")),
        )
        db.epgDao().upsertProgrammes(listOf(programme(sourceId = 4242, epgChannelId = "bbc1")))

        val all = candidates.all()

        assertEquals(1, all.size)
        assertEquals("bbc1", all.single().epgChannelId)
        assertEquals("BBC One", all.single().displayName)
        assertTrue(all.single().hasProgrammes)
    }

    @Test
    fun aFeedWithProgrammesButNoChannelEntriesIsStillPickable() = runBlocking {
        // <programme> without <channel>. epg_channels stays empty, so the old picker showed nothing
        // at all while the guide grid was full.
        db.epgDao().upsertProgrammes(listOf(programme(sourceId = 7, epgChannelId = "itv1")))

        val all = candidates.all()

        assertEquals(listOf("itv1"), all.map { it.epgChannelId })
        assertNull(all.single().displayName)
        assertTrue(all.single().hasProgrammes)
    }

    @Test
    fun aNamedChannelWithNoProgrammesComesBackFlagged() = runBlocking {
        db.epgDao().upsertChannels(
            listOf(EpgChannelEntity(sourceId = 7, epgChannelId = "sky1", displayName = "Sky One")),
        )

        val all = candidates.all()

        assertEquals(listOf("sky1"), all.map { it.epgChannelId })
        assertFalse(all.single().hasProgrammes)
    }

    @Test
    fun twoFeedsCarryingOneChannelProduceOneCandidate() = runBlocking {
        db.epgDao().upsertChannels(
            listOf(
                EpgChannelEntity(sourceId = 1, epgChannelId = "bbc1", displayName = "BBC1"),
                EpgChannelEntity(sourceId = 2, epgChannelId = "bbc1", displayName = "BBC One HD"),
            ),
        )
        db.epgDao().upsertProgrammes(
            listOf(
                programme(sourceId = 1, epgChannelId = "bbc1"),
                programme(sourceId = 2, epgChannelId = "bbc1", startMs = 2_000),
            ),
        )

        val all = candidates.all()

        assertEquals(1, all.size)
        assertEquals("BBC One HD", all.single().displayName)
    }

    @Test
    fun theSearchFindsAChannelByNameAndById() = runBlocking {
        db.epgDao().upsertChannels(
            listOf(
                EpgChannelEntity(sourceId = 1, epgChannelId = "bbc.one.uk", displayName = "BBC One"),
                EpgChannelEntity(sourceId = 1, epgChannelId = "itv.one.uk", displayName = "ITV1"),
            ),
        )

        assertEquals(listOf("bbc.one.uk"), candidates.forPicker("BBC One", "bbc one").map { it.epgChannelId })
        assertEquals(listOf("itv.one.uk"), candidates.forPicker("ITV", "itv.one").map { it.epgChannelId })
        assertTrue(candidates.forPicker("BBC One", "nothing-matches-this").isEmpty())
    }

    private fun programme(
        sourceId: Long,
        epgChannelId: String,
        startMs: Long = 1_000,
    ) = EpgProgrammeEntity(
        sourceId = sourceId,
        epgChannelId = epgChannelId,
        startMs = startMs,
        stopMs = startMs + 1_000,
        title = "Programme",
    )
}
