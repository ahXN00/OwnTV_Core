package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.parser.XtEpgEntry

class ScheduleEntryTest {

    private val stored = EpgProgrammeEntity(
        id = 42, sourceId = 1, epgChannelId = "news.example", startMs = 1_000, stopMs = 61_000,
        title = "News", description = "Headlines",
    )

    @Test
    fun `a stored programme's row carries its id`() {
        assertEquals(XtEpgEntry("News", "Headlines", 1_000, 61_000, programmeId = 42), stored.toXt())
    }

    @Test
    fun `an EPG offset moves the times but keeps the id`() {
        val row = EpgShift.apply(stored, 60).toXt()
        assertEquals(42L, row.programmeId)
        assertEquals(1_000 + 3_600_000L, row.startMs)
    }

    @Test
    fun `a provider-only row has no programme`() {
        assertEquals(0L, XtEpgEntry("News", null, 1_000, 61_000).programmeId)
    }
}
