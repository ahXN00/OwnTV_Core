package tv.own.owntv.core.epg

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Which finished programmes survive a sync.
 *
 * The retention split deletes rows, and rows it deletes wrongly cannot be got back — a provider's
 * archive is not re-downloadable. So the rules it must follow are pinned here rather than left to the
 * prune query's shape:
 *
 *  - a channel that can replay its past keeps the full archive window;
 *  - a channel that cannot keeps only the recent past;
 *  - a **hand-matched** catch-up channel is protected by the guide id its match points at, not by its
 *    own column — the case that would otherwise delete exactly what the user fixed by hand.
 */
@RunWith(AndroidJUnit4::class)
class EpgRetentionTest {

    private lateinit var db: OwnTVDatabase

    private val now = System.currentTimeMillis()
    private val hour = 60L * 60 * 1000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            OwnTVDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun catchupChannelsAreListedByTheirOwnGuideId() = runBlocking {
        db.channelDao().insertAll(
            listOf(
                channel(remoteId = "1", tvgId = "bbc1", catchup = true),
                channel(remoteId = "2", tvgId = "itv1", catchup = false),
            ),
        )

        val catchup = db.channelDao().allCatchupChannels()

        assertEquals(listOf("bbc1"), catchup.map { it.epgChannelId })
    }

    @Test
    fun pruningKeepsTheArchiveOnlyForChannelsThatCanReplayIt() = runBlocking {
        // Two channels, one replayable. Both have a programme that finished a day ago.
        db.epgDao().upsertProgrammes(
            listOf(
                programme("bbc1", startMs = now - 26 * hour, stopMs = now - 25 * hour),
                programme("itv1", startMs = now - 26 * hour, stopMs = now - 25 * hour),
                // Inside the recent window: both keep this one.
                programme("bbc1", startMs = now - 2 * hour, stopMs = now - hour),
                programme("itv1", startMs = now - 2 * hour, stopMs = now - hour),
            ),
        )

        // What EpgRepository does for the non-catch-up set: drop finished rows older than the recent
        // cutoff, except for the guide ids that can replay them.
        val w = db.openHelper.writableDatabase
        w.execSQL("CREATE TEMP TABLE IF NOT EXISTS `epg_keep_history` (`epgChannelId` TEXT PRIMARY KEY NOT NULL)")
        w.execSQL("INSERT OR IGNORE INTO `epg_keep_history` VALUES ('bbc1')")
        w.execSQL(
            "DELETE FROM `epg_programmes` WHERE `stopMs` < ? " +
                "AND `epgChannelId` NOT IN (SELECT `epgChannelId` FROM `epg_keep_history`)",
            arrayOf<Any?>(now - 6 * hour),
        )
        w.execSQL("DROP TABLE IF EXISTS `epg_keep_history`")

        // The replayable channel keeps both; the other keeps only the recent one.
        assertEquals(2, countFor("bbc1"))
        assertEquals(1, countFor("itv1"))
    }

    @Test
    fun aHandMatchedCatchupChannelIsProtectedByTheIdItsMatchPointsAt() = runBlocking {
        // The channel's own column says "provider-id-9", but the user matched it to "bbc1", which is
        // where its guide — and therefore its archive — actually lives. Protecting the column instead
        // of the match would delete the archive of the very channel the user repaired.
        val channel = channel(remoteId = "9", tvgId = "provider-id-9", catchup = true)
        val cust = tv.own.owntv.core.customize.SectionCustomizations(
            epgMatches = mapOf(tv.own.owntv.core.customize.CustomizeKeys.channel(channel) to "bbc1"),
        )

        val protectedId = cust.epgMatchResolver.epgIdFor(channel)

        assertEquals("bbc1", protectedId)
    }

    private fun countFor(epgChannelId: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM epg_programmes WHERE epgChannelId = ?",
            arrayOf<Any?>(epgChannelId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun channel(remoteId: String, tvgId: String?, catchup: Boolean) = ChannelEntity(
        sourceId = 1,
        remoteId = remoteId,
        name = "Channel $remoteId",
        streamUrl = "http://example.invalid/$remoteId",
        epgChannelId = tvgId,
        catchup = catchup,
    )

    private fun programme(epgChannelId: String, startMs: Long, stopMs: Long) = EpgProgrammeEntity(
        sourceId = 1,
        epgChannelId = epgChannelId,
        startMs = startMs,
        stopMs = stopMs,
        title = "Programme",
    )
}
