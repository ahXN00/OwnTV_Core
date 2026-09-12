package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.epg.CatchupUrl
import java.util.TimeZone

/**
 * Recording something that has already been on.
 *
 * Two halves: the URL the archive is fetched from, which is `CatchupUrl`'s work and is checked here
 * per provider convention, and the rule that tells the recorder an archive stream **ends** while a
 * live one does not.
 */
class RecordingCatchUpTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val programmeStart = 1_757_710_800_000L
    private val programmeStop = programmeStart + hour
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    // --- Telling a catch-up recording from a live one ---

    private fun row(programmeStart: Long, programmeStop: Long, start: Long, stop: Long) = RecordingEntity(
        profileId = 1,
        sourceId = 1,
        channelId = 1,
        channelName = "BBC One",
        streamUrl = "http://example.invalid/live",
        title = "The News",
        programmeStartMs = programmeStart,
        programmeStopMs = programmeStop,
        startMs = start,
        stopMs = stop,
    )

    @Test
    fun `a recording whose window opens after the programme ended is a catch-up`() {
        val now = programmeStop + hour
        val window = RecordingSchedule.catchUpWindowFor(programmeStart, programmeStop, now)
        assertTrue(RecordingSchedule.isCatchUp(row(programmeStart, programmeStop, window.first, window.last)))
    }

    @Test
    fun `a scheduled live recording is never mistaken for a catch-up`() {
        // Its window opens *before* the programme does — that is what a pre-roll is.
        val window = RecordingSchedule.windowFor(programmeStart, programmeStop, 1, 3)
        assertFalse(RecordingSchedule.isCatchUp(row(programmeStart, programmeStop, window.first, window.last)))
    }

    @Test
    fun `a recording started at the exact moment a programme ends counts as catch-up`() {
        // The boundary is the whole point: from here on the source is finite, and end-of-body means
        // "the programme is complete" rather than "the provider dropped us".
        assertTrue(RecordingSchedule.isCatchUp(row(programmeStart, programmeStop, programmeStop, programmeStop + hour)))
    }

    // --- The archive window ---

    @Test
    fun `a catch-up recording starts now and runs at least as long as the programme did`() {
        val now = programmeStop + 3 * hour
        val window = RecordingSchedule.catchUpWindowFor(programmeStart, programmeStop, now)
        assertEquals(now, window.first)
        assertTrue(window.last - window.first >= programmeStop - programmeStart)
    }

    @Test
    fun `a catch-up window has no pre-roll — padding would record the previous programme`() {
        val now = programmeStop + hour
        assertEquals(now, RecordingSchedule.catchUpWindowFor(programmeStart, programmeStop, now).first)
    }

    @Test
    fun `a zero-length programme still gets a window worth fetching`() {
        val now = programmeStop + hour
        val window = RecordingSchedule.catchUpWindowFor(programmeStart, programmeStart, now)
        assertTrue(window.last > window.first)
    }

    // --- What the archive still holds ---

    @Test
    fun `a programme inside the archive can be fetched`() {
        val now = programmeStop + 2 * 24 * hour
        assertTrue(RecordingSchedule.isWithinArchive(programmeStop, catchupDays = 7, now = now))
    }

    @Test
    fun `a programme older than the archive cannot`() {
        val now = programmeStop + 30L * 24 * hour
        assertFalse(RecordingSchedule.isWithinArchive(programmeStop, catchupDays = 7, now = now))
    }

    @Test
    fun `a programme still on the air is not in the archive yet`() {
        assertFalse(RecordingSchedule.isWithinArchive(programmeStop, catchupDays = 7, now = programmeStop - minute))
    }

    @Test
    fun `a playlist that claims catch-up without saying how deep gets a sane default`() {
        val now = programmeStop + 2 * 24 * hour
        assertTrue(RecordingSchedule.isWithinArchive(programmeStop, catchupDays = 0, now = now))
        assertFalse(RecordingSchedule.isWithinArchive(programmeStop, catchupDays = 0, now = programmeStop + 30L * 24 * hour))
    }

    @Test
    fun `a nonsense catchup-days from a playlist is capped rather than believed`() {
        // A playlist claiming ten years of archive is a playlist that is wrong.
        val ancient = programmeStop
        val now = ancient + 400L * 24 * hour
        assertFalse(RecordingSchedule.isWithinArchive(ancient, catchupDays = 99_999, now = now))
    }

    // --- The archive URL, per provider convention ---

    @Test
    fun `the M3U append convention joins the template onto the live URL`() {
        val url = CatchupUrl.forM3u(
            liveUrl = "http://example.invalid/live/ch1.ts",
            catchupType = "append",
            catchupSource = "?utc={utc}&lutc={lutc}",
            startMs = programmeStart,
            endMs = programmeStop,
            tz = utc,
        )
        assertNotNull(url)
        assertTrue(url!!.startsWith("http://example.invalid/live/ch1.ts?"))
        assertTrue(url.contains("utc="))
    }

    @Test
    fun `a live URL that already has a query does not gain a second question mark`() {
        // A CDN token on the live URL is ordinary, and a second `?` makes the request invalid.
        val url = CatchupUrl.forM3u(
            liveUrl = "http://example.invalid/live/ch1.ts?token=abc",
            catchupType = "append",
            catchupSource = "?utc={utc}&lutc={lutc}",
            startMs = programmeStart,
            endMs = programmeStop,
            tz = utc,
        )
        assertNotNull(url)
        assertEquals(1, url!!.count { it == '?' })
        assertTrue(url.contains("token=abc"))
    }

    @Test
    fun `the shift convention needs no template of its own`() {
        val url = CatchupUrl.forM3u(
            liveUrl = "http://example.invalid/live/ch1.ts",
            catchupType = "shift",
            catchupSource = null,
            startMs = programmeStart,
            endMs = programmeStop,
            tz = utc,
        )
        assertNotNull(url)
        assertTrue(url!!.contains("utc="))
    }

    @Test
    fun `the flussonic convention rewrites the path instead of adding a query`() {
        val url = CatchupUrl.forM3u(
            liveUrl = "http://example.invalid/ch1/index.m3u8",
            catchupType = "flussonic",
            catchupSource = null,
            startMs = programmeStart,
            endMs = programmeStop,
            tz = utc,
        )
        assertNotNull(url)
        assertFalse("flussonic archives live at a different path, not behind a query", url!!.contains("utc="))
    }

    @Test
    fun `a channel that claims catch-up but gives nothing to build from yields no URL`() {
        // Better no recording than a recording of an error page.
        assertNull(
            CatchupUrl.forM3u(
                liveUrl = "http://example.invalid/live/ch1.ts",
                catchupType = "default",
                catchupSource = null,
                startMs = programmeStart,
                endMs = programmeStop,
                tz = utc,
            ),
        )
    }

    @Test
    fun `an Xtream timeshift path can be rewritten as the PHP form some panels require`() {
        val php = CatchupUrl.timeshiftPhpAlternate(
            "http://example.invalid/timeshift/user/pass/60/2026-09-12:21-00/1234.ts",
        )
        assertNotNull(php)
        assertTrue(php!!.contains("timeshift.php"))
        assertTrue(php.contains("stream=1234"))
        assertTrue(php.contains("duration=60"))
    }
}
