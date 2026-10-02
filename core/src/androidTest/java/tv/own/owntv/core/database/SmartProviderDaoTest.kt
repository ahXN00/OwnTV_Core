package tv.own.owntv.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.ChannelProviderCandidateEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * `SmartProviderDao` — the cross-provider failover memory (v47, Smart Provider phase 2).
 *
 * Opened through [ownTVTestDatabase], so these run on the bundled engine the app actually ships.
 *
 * The two properties worth stating up front, because they are what the phase is *for*:
 *  - a row is one **memory** per (channel, provider), so re-discovering a candidate updates it rather
 *    than accumulating attempts;
 *  - the success and failure stamps are independent. "Worked in March, failed this morning" is the
 *    state a failover decision needs, and a success that cleared the failure would erase it.
 *
 * Nothing here ranks candidates. Ordering is a read convenience, not a policy — deciding which
 * candidate to try is phase 3's job.
 */
@RunWith(AndroidJUnit4::class)
class SmartProviderDaoTest {
    private lateinit var db: OwnTVDatabase
    private val dao get() = db.smartProviderDao()

    @Before
    fun setUp() {
        db = ownTVTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertOnTheSameAnchorAndSourceReplacesRatherThanDuplicating() = runBlocking {
        val candidateSource = source("Provider B")

        dao.upsert(candidate(candidateSource, name = "BBC One"))
        dao.upsert(candidate(candidateSource, name = "BBC One HD"))

        val rows = dao.forAnchor(ANCHOR)
        assertEquals("one memory per (channel, provider), not one per discovery", 1, rows.size)
        assertEquals("the later sweep is the truthful one", "BBC One HD", rows.single().candidateName)
    }

    @Test
    fun theSameAnchorChannelKeepsOneRowPerProvider() = runBlocking {
        val providerB = source("Provider B")
        val providerC = source("Provider C")

        dao.upsert(candidate(providerB, name = "BBC One"))
        dao.upsert(candidate(providerC, name = "BBC1"))

        assertEquals(setOf(providerB, providerC), dao.forAnchor(ANCHOR).map { it.sourceId }.toSet())
    }

    @Test
    fun aCandidateThatMatchedWithoutARemoteIdStillRoundTrips() = runBlocking {
        val candidateSource = source("Provider B")

        dao.upsert(candidate(candidateSource, name = "Hand-made entry", remoteId = null))

        val stored = dao.find(ANCHOR, candidateSource)
        assertNotNull(stored)
        assertNull("a hand-made M3U row has no provider id, and is still a candidate", stored!!.candidateRemoteId)
        assertEquals("Hand-made entry", stored.candidateName)
    }

    @Test
    fun markSuccessStampsTheSuccessAndLeavesTheEarlierFailureAlone() = runBlocking {
        val candidateSource = source("Provider B")
        dao.upsert(candidate(candidateSource, name = "BBC One"))

        dao.markFailure(ANCHOR, candidateSource, FAILED_AT, HTTP_509)
        dao.markSuccess(ANCHOR, candidateSource, WORKED_AT)

        val stored = dao.find(ANCHOR, candidateSource)
        assertNotNull(stored)
        assertEquals("worked in March, failed this morning — both facts survive", WORKED_AT, stored!!.lastSuccessAt)
        assertEquals(FAILED_AT, stored.lastFailureAt)
        assertEquals(HTTP_509, stored.lastFailureReason)
    }

    @Test
    fun markFailureRecordsTheProviderRelevantReasonWithoutInventingASuccess() = runBlocking {
        val candidateSource = source("Provider B")
        dao.upsert(candidate(candidateSource, name = "BBC One"))

        dao.markFailure(ANCHOR, candidateSource, FAILED_AT, ONE_SESSION)

        val stored = dao.find(ANCHOR, candidateSource)
        assertNotNull(stored)
        assertEquals(FAILED_AT, stored!!.lastFailureAt)
        assertEquals(ONE_SESSION, stored.lastFailureReason)
        assertEquals("never worked here", 0L, stored.lastSuccessAt)
    }

    @Test
    fun markFailureOnAPairThisTableHasNeverHeardOfInventsNothing() = runBlocking {
        val candidateSource = source("Provider B")

        dao.markFailure(ANCHOR, candidateSource, FAILED_AT, HTTP_509)

        // A failure for a pairing nobody ever matched is not evidence of a relationship, so the
        // UPDATE should simply match no rows rather than conjure a candidate out of thin air.
        assertNull(dao.find(ANCHOR, candidateSource))
        assertEquals(0, dao.forAnchor(ANCHOR).size)
    }

    @Test
    fun forAnchorPutsTheProviderThatLastWorkedFirst_andIsDeterministicForTheRest() = runBlocking {
        val onlyFailed = source("Only failed")
        val worked = source("Worked")
        val untried = source("Untried")

        dao.upsert(candidate(worked, name = "Worked"))
        dao.markSuccess(ANCHOR, worked, WORKED_AT)
        dao.upsert(candidate(onlyFailed, name = "Only failed"))
        dao.markFailure(ANCHOR, onlyFailed, FAILED_AT, HTTP_509)
        dao.upsert(candidate(untried, name = "Untried"))

        // Proven first; the two that never worked tie at 0 and are broken by sourceId so the order
        // cannot vary between runs. This is a read convenience, not a ranking policy.
        assertEquals(
            listOf(worked, onlyFailed, untried),
            dao.forAnchor(ANCHOR).map { it.sourceId },
        )
    }

    @Test
    fun forgettingOneCandidateLeavesTheAnchorsOtherCandidates() = runBlocking {
        val providerB = source("Provider B")
        val providerC = source("Provider C")
        dao.upsert(candidate(providerB, name = "BBC One"))
        dao.upsert(candidate(providerC, name = "BBC1"))

        dao.forget(ANCHOR, providerB)

        assertEquals(listOf(providerC), dao.forAnchor(ANCHOR).map { it.sourceId })
        assertNull(dao.find(ANCHOR, providerB))
    }

    /**
     * The one path by which this table is expected to lose rows, and the reason `sourceId` carries a
     * foreign key at all: removing a playlist must remove the memories that named it. Room enables
     * foreign keys on its own connections, so the CASCADE is live here without any PRAGMA.
     */
    @Test
    fun deletingThePlaylistForgetsEveryCandidateThatNamedIt() = runBlocking {
        val providerB = source("Provider B")
        val providerC = source("Provider C")
        dao.upsert(candidate(providerB, name = "BBC One"))
        dao.upsert(candidate(providerC, name = "BBC1"))
        assertEquals(2, dao.forAnchor(ANCHOR).size)

        val doomed = db.sourceDao().getById(providerB)
        assertNotNull(doomed)
        db.sourceDao().delete(doomed!!)

        assertEquals(listOf(providerC), dao.forAnchor(ANCHOR).map { it.sourceId })
    }

    private suspend fun source(name: String): Long =
        db.sourceDao().insert(SourceEntity(name = name, type = SourceType.XTREAM, url = "https://example.test"))

    /**
     * [ANCHOR_SOURCE] is deliberately **not** a row in `sources`: `anchorSourceId` carries no foreign
     * key, because it is provenance for the relationship rather than a reference to live content. A
     * channel whose anchor playlist was deleted is still a channel another playlist may serve.
     */
    private fun candidate(
        sourceId: Long,
        name: String,
        remoteId: String? = "remote-$sourceId",
    ) = ChannelProviderCandidateEntity(
        anchorKey = ANCHOR,
        anchorSourceId = ANCHOR_SOURCE,
        sourceId = sourceId,
        candidateRemoteId = remoteId,
        candidateName = name,
    )

    private companion object {
        /** The shape `enginePinKey` produces: `"$sourceId:$mediaType:$remoteId"`. */
        private const val ANCHOR = "10:LIVE:bbc-one"
        private const val ANCHOR_SOURCE = 10L
        private const val WORKED_AT = 1_700_000_000_000L
        private const val FAILED_AT = 1_700_000_600_000L

        /**
         * `PlayerFailureReason.name` values, as text. Strings rather than the enum because `core`
         * does not depend on `player-core` — that is exactly why the column is text. Only
         * provider-relevant reasons belong in this column; the decoder/audio categories
         * (`DECODER_BUSY`, `UNSUPPORTED_VIDEO`, `STEREO_FALLBACK`) describe the device, not the
         * provider, and writing them here would condemn a good provider for one poor phone.
         */
        private const val HTTP_509 = "HTTP_509"
        private const val ONE_SESSION = "ONE_SESSION_PROVIDER"
    }
}
