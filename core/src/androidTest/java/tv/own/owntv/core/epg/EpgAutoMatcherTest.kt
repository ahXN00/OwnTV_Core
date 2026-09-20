package tv.own.owntv.core.epg

import tv.own.owntv.core.database.ownTVTestDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Auto-match, held to the two promises it used to break: it does not skip a channel whose guide is
 * empty, and it does not claim to have fixed one when the guide it picked is empty too.
 */
@RunWith(AndroidJUnit4::class)
class EpgAutoMatcherTest {

    private lateinit var db: OwnTVDatabase
    private lateinit var matcher: EpgAutoMatcher

    private val playlist = 1L

    @Before
    fun setUp() {
        db = ownTVTestDatabase()
        matcher = EpgAutoMatcher(db.channelDao(), GuideCandidates(db.epgDao()))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aChannelWhoseTvgIdHasNoProgrammesIsStillOffered() = runBlocking {
        // The defect: the tvg-id exists as an empty <channel> entry, so the channel was treated as
        // already having a guide and skipped on every run, for ever, with a permanently blank row.
        db.epgDao().upsertChannels(
            listOf(
                EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1.empty", displayName = "BBC One"),
                EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1.real", displayName = "BBC One"),
            ),
        )
        db.epgDao().upsertProgrammes(listOf(programme("bbc1.real")))
        insertChannel(name = "BBC One", remoteId = "1", tvgId = "bbc1.empty")

        val outcome = matcher.run(SectionCustomizations(), listOf(playlist))

        assertTrue(outcome.hadCandidates)
        // It was not skipped, and the guide channel that actually has programmes is the one applied.
        assertEquals(listOf("1:1" to "bbc1.real"), outcome.applied)
    }

    @Test
    fun aConfidentWinnerWithNoProgrammesIsNeverAppliedSilently() = runBlocking {
        // A 0.95 name hit onto a guide channel with nothing scheduled used to be counted as a
        // success, leaving the user with the same empty row and a message saying it was fixed.
        db.epgDao().upsertChannels(
            listOf(EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1", displayName = "BBC One")),
        )
        insertChannel(name = "BBC One", remoteId = "1", tvgId = null)

        val outcome = matcher.run(SectionCustomizations(), listOf(playlist))

        assertTrue(outcome.applied.isEmpty())
        assertEquals(1, outcome.review.size)
        assertEquals(1, outcome.withheldForNoProgrammes)
        assertEquals("bbc1", outcome.review.single().epgChannelId)
        assertFalse(outcome.review.single().hasProgrammes)
        assertTrue("held back, so still a confident score", outcome.review.single().score >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun aChannelThatAlreadyHasAWorkingGuideIsLeftAlone() = runBlocking {
        db.epgDao().upsertChannels(
            listOf(EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1", displayName = "BBC One")),
        )
        db.epgDao().upsertProgrammes(listOf(programme("bbc1")))
        insertChannel(name = "BBC One", remoteId = "1", tvgId = "bbc1")

        val outcome = matcher.run(SectionCustomizations(), listOf(playlist))

        assertTrue(outcome.applied.isEmpty())
        assertTrue(outcome.review.isEmpty())
    }

    @Test
    fun aMatchThatSurvivedAReAddedPlaylistIsNotMatchedAgain() = runBlocking {
        // Phase 3's resolver answers for this channel, so auto-match must not treat it as unmatched
        // and overwrite the user's own choice.
        db.epgDao().upsertChannels(
            listOf(
                EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1", displayName = "BBC One"),
                EpgChannelEntity(sourceId = 9, epgChannelId = "bbc.chosen", displayName = "BBC One"),
            ),
        )
        db.epgDao().upsertProgrammes(listOf(programme("bbc1"), programme("bbc.chosen")))
        insertChannel(name = "BBC One", remoteId = "1", tvgId = null)

        // The match was stored under a source id the playlist no longer has.
        val cust = SectionCustomizations(epgMatches = mapOf("77:1" to "bbc.chosen"))
        val outcome = matcher.run(cust, listOf(playlist))

        assertTrue(outcome.applied.isEmpty())
        assertTrue(outcome.review.isEmpty())
    }

    @Test
    fun aHiddenChannelIsNeverMatched() = runBlocking {
        db.epgDao().upsertChannels(
            listOf(EpgChannelEntity(sourceId = 9, epgChannelId = "bbc1", displayName = "BBC One")),
        )
        db.epgDao().upsertProgrammes(listOf(programme("bbc1")))
        val channel = insertChannel(name = "BBC One", remoteId = "1", tvgId = null)

        val cust = SectionCustomizations(hiddenItems = mapOf(CustomizeKeys.channel(channel) to "BBC One"))
        val outcome = matcher.run(cust, listOf(playlist))

        assertTrue(outcome.applied.isEmpty())
        assertTrue(outcome.review.isEmpty())
    }

    @Test
    fun noGuideDataAtAllIsReportedDistinctly() = runBlocking {
        insertChannel(name = "BBC One", remoteId = "1", tvgId = null)

        val outcome = matcher.run(SectionCustomizations(), listOf(playlist))

        assertFalse("no candidates is not the same as nothing matched", outcome.hadCandidates)
    }

    private suspend fun insertChannel(name: String, remoteId: String?, tvgId: String?): ChannelEntity {
        val channel = ChannelEntity(
            sourceId = playlist,
            remoteId = remoteId,
            name = name,
            streamUrl = "http://example.invalid/$remoteId",
            epgChannelId = tvgId,
        )
        db.channelDao().insertAll(listOf(channel))
        return channel
    }

    private fun programme(epgChannelId: String) = EpgProgrammeEntity(
        sourceId = 9,
        epgChannelId = epgChannelId,
        startMs = 1_000,
        stopMs = 2_000,
        title = "Programme",
    )
}
