package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CatchupUrlTest {

    // 2021-01-01 00:00:00 UTC = 1609459200; +1h = 1609462800.
    private val start = 1609459200_000L
    private val end = 1609462800_000L

    @Test
    fun template_fillsUnixTokens() {
        assertEquals(
            "http://x/stream?start=1609459200&end=1609462800&dur=3600",
            CatchupUrl.fromTemplate("http://x/stream?start=\${start}&end=\${end}&dur=\${duration}", start, end),
        )
    }

    @Test
    fun template_fillsUtcBraceAndDateParts() {
        assertEquals(
            "http://x/2021-01-01/00-00/1609459200.ts",
            CatchupUrl.fromTemplate("http://x/{Y}-{m}-{d}/{H}-{M}/{utc}.ts", start, end),
        )
    }

    @Test
    fun template_leavesUnknownTokens() {
        assertEquals("http://x/\${weird}", CatchupUrl.fromTemplate("http://x/\${weird}", start, end))
    }

    @Test
    fun forM3u_appendJoinsOntoLiveUrl() {
        val out = CatchupUrl.forM3u("http://x/live.ts", "append", "?utc=\${start}", start, end)
        assertEquals("http://x/live.ts?utc=1609459200", out)
    }

    @Test
    fun forM3u_nullWhenNothingToBuildFrom() {
        assertNull(CatchupUrl.forM3u("http://x/live.ts", null, null, start, end))
        assertNull(CatchupUrl.forM3u("http://x/live.ts", "vod", null, start, end))
    }

    /** Kodi's rule: `catchup="default"` with no `catchup-source` appends the shift query. */
    @Test
    fun forM3u_defaultWithoutSourceAppendsTheShiftQuery() {
        val out = CatchupUrl.forM3u("http://x/live.ts", "default", null, start, end)
        assertNotNull(out)
        assertTrue(out!!.startsWith("http://x/live.ts?utc=1609459200&lutc="))
    }

    /** `${timestamp}` is now (Kodi: "same as {lutc}"), not the programme start. */
    @Test
    fun template_timestampIsNow() {
        val now = 1609466400_000L
        assertEquals("http://x/s?t=1609466400", CatchupUrl.fromTemplate("http://x/s?t=\${timestamp}", start, end, now))
        assertEquals("http://x/s?t=1609466400", CatchupUrl.fromTemplate("http://x/s?t={timestamp}", start, end, now))
    }

    /** `{duration:X}` / `{offset:X}` divide by X seconds; a non-number argument is left alone. */
    @Test
    fun template_durationAndOffsetWithADivisor() {
        val now = start + 7_200_000L // two hours after the start
        assertEquals(
            "http://x/s?d=60&o=120&d2=60&o2=7200",
            CatchupUrl.fromTemplate("http://x/s?d={duration:60}&o={offset:60}&d2=\${duration:60}&o2=\${offset:1}", start, end, now),
        )
        assertEquals("http://x/{duration:abc}", CatchupUrl.fromTemplate("http://x/{duration:abc}", start, end, now))
        assertEquals("http://x/{duration:0}", CatchupUrl.fromTemplate("http://x/{duration:0}", start, end, now))
    }

    /** `{utc:FMT}` and friends format their own instant; only Y m d H M S are fields. */
    @Test
    fun template_customFormats() {
        val s = 1609502461_000L // 2021-01-01 12:01:01 UTC
        val e = 1609506062_000L // 2021-01-01 13:01:02 UTC
        val now = 1609595103_000L // 2021-01-02 13:45:03 UTC
        assertEquals("http://x/20210101120101", CatchupUrl.fromTemplate("http://x/{utc:YmdHMS}", s, e, now))
        assertEquals("http://x/20210101-12-01", CatchupUrl.fromTemplate("http://x/\${start:Ymd-H-M}", s, e, now))
        assertEquals("http://x/20210101-13-01", CatchupUrl.fromTemplate("http://x/{utcend:Ymd-H-M}", s, e, now))
        assertEquals("http://x/2021.01.01_13", CatchupUrl.fromTemplate("http://x/\${end:Y.m.d_H}", s, e, now))
        assertEquals("http://x/20210102134503", CatchupUrl.fromTemplate("http://x/{lutc:YmdHMS}", s, e, now))
        assertEquals("http://x/2021-01-02", CatchupUrl.fromTemplate("http://x/\${now:Y-m-d}", s, e, now))
        assertEquals("http://x/13:45", CatchupUrl.fromTemplate("http://x/\${timestamp:H:M}", s, e, now))
        // The bare date parts are unchanged.
        assertEquals("http://x/2021/01/01/12/01/01", CatchupUrl.fromTemplate("http://x/{Y}/{m}/{d}/{H}/{M}/{S}", s, e, now))
        // An unknown name with an argument is left as it is.
        assertEquals("http://x/{catchup-id:x}", CatchupUrl.fromTemplate("http://x/{catchup-id:x}", s, e, now))
    }

    /** `catchup-correction` moves the programme's times before the URL is built, for every style. */
    @Test
    fun forM3u_correctionShiftsTheTimes() {
        assertEquals(
            "http://x/s?start=1609455600&end=1609459200",
            CatchupUrl.forM3u("http://x/live.ts", null, "http://x/s?start={utc}&end={utcend}", start, end, correctionMin = -60),
        )
        assertEquals(
            "http://x/ch1/timeshift_abs-1609461000.m3u8",
            CatchupUrl.forM3u("http://x/ch1/index.m3u8", "flussonic", null, start, end, correctionMin = 30),
        )
    }

    /** `{lutc}` used to survive into the request verbatim. */
    @Test
    fun template_fillsNowTokens() {
        val now = 1609466400_000L
        assertEquals(
            "http://x/s?utc=1609459200&lutc=1609466400&now=1609466400",
            CatchupUrl.fromTemplate("http://x/s?utc={utc}&lutc={lutc}&now={now}", start, end, now),
        )
    }

    /** A channel URL that already carries a query must not get a second `?`. */
    @Test
    fun forM3u_appendFixesTheQuerySeparator() {
        val out = CatchupUrl.forM3u("http://x/live.ts?token=abc", "append", "?utc=\${start}", start, end)
        assertEquals("http://x/live.ts?token=abc&utc=1609459200", out)
    }

    @Test
    fun forM3u_shiftAppendsTheStandardQuery() {
        val out = CatchupUrl.forM3u("http://x/live.ts", "shift", null, start, end)
        assertNotNull(out)
        assertTrue(out!!.startsWith("http://x/live.ts?utc=1609459200&lutc="))
    }

    @Test
    fun forM3u_flussonicRewritesThePath() {
        assertEquals(
            "http://x/ch1/timeshift_abs-1609459200.m3u8",
            CatchupUrl.forM3u("http://x/ch1/index.m3u8", "flussonic", null, start, end),
        )
        assertEquals(
            "http://x/ch1/archive-1609459200-3600.ts",
            CatchupUrl.forM3u("http://x/ch1/mpegts", "flussonic-ts", null, start, end),
        )
        // Not a Flussonic-shaped URL → no guess.
        assertNull(CatchupUrl.forM3u("http://x", "flussonic", null, start, end))
    }

    @Test
    fun forM3u_xcRebuildsTheTimeshiftEndpoint() {
        assertEquals(
            "http://x:8080/streaming/timeshift.php?username=u&password=p&stream=1234&start=2021-01-01:00-00&duration=60",
            CatchupUrl.forM3u("http://x:8080/live/u/p/1234.ts", "xc", null, start, end, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun timeshiftPhpAlternate_convertsPathToPhpForm() {
        val path = "http://gaming8k.top/timeshift/user1/pass1/15/2026-06-16:09-15/544435.ts"
        assertEquals(
            "http://gaming8k.top/streaming/timeshift.php?username=user1&password=pass1&stream=544435&start=2026-06-16:09-15&duration=15",
            CatchupUrl.timeshiftPhpAlternate(path),
        )
    }

    @Test
    fun timeshiftPhpAlternate_nullForNonTimeshiftUrls() {
        assertNull(CatchupUrl.timeshiftPhpAlternate("http://x/live/user/pass/1.ts"))
        assertNull(CatchupUrl.timeshiftPhpAlternate(null))
    }

    // 2026-10-17 14:30:00Z .. 15:00:00Z.
    private val acStart = 1792247400_000L
    private val acEnd = 1792249200_000L
    private val playseek = "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}"

    @Test
    fun forM3u_legacyPlayseekUtc() {
        assertEquals(
            "http://h/cctv1.m3u8?playseek=20261017143000-20261017150000",
            CatchupUrl.forM3u("http://h/cctv1.m3u8", "append", playseek, acStart, acEnd, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun forM3u_legacyPlayseekUsesTzUnlessUtcSuffix() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        assertEquals(
            "http://h/cctv1.m3u8?playseek=20261017223000-20261017230000",
            CatchupUrl.forM3u("http://h/cctv1.m3u8", "append", playseek, acStart, acEnd, shanghai),
        )
        assertEquals(
            "http://h/cctv1.m3u8?playseek=20261017143000-20261017150000",
            CatchupUrl.forM3u(
                "http://h/cctv1.m3u8",
                "append",
                "?playseek=\${(b)yyyyMMddHHmmss:utc}-\${(e)yyyyMMddHHmmss:utc}",
                acStart,
                acEnd,
                shanghai,
            ),
        )
    }

    @Test
    fun template_legacyTimestampAndKodiLetters() {
        assertEquals(
            "1792247400-1792249200",
            CatchupUrl.fromTemplate("\${(b)timestamp}-\${(e)timestamp}", acStart, acEnd),
        )
        assertEquals(
            "20261017143000",
            CatchupUrl.fromTemplate("\${(b)YmdHMS}", acStart, acEnd),
        )
        assertEquals(
            "20261017143000",
            CatchupUrl.fromTemplate("\${(b)yyyyMMddHHmmss}", acStart, acEnd),
        )
        assertEquals("\${(b)foo}", CatchupUrl.fromTemplate("\${(b)foo}", acStart, acEnd))
        assertEquals("\${(b)yyyy'}", CatchupUrl.fromTemplate("\${(b)yyyy'}", acStart, acEnd))
    }

    /** `${(b)HH:mm}` is a Java pattern; colons are part of FMT and the zone is the caller's. */
    @Test
    fun template_legacyColonPatternUsesTz() {
        assertEquals(
            "22:30",
            CatchupUrl.fromTemplate("\${(b)HH:mm}", acStart, acEnd, tz = TimeZone.getTimeZone("Asia/Shanghai")),
        )
    }

    /** `${(b)HH:mm:utc}` forces UTC even when the caller passed another zone. */
    @Test
    fun template_legacyColonPatternUtcSuffix() {
        assertEquals(
            "14:30",
            CatchupUrl.fromTemplate(
                "\${(b)HH:mm:utc}",
                acStart,
                acEnd,
                tz = TimeZone.getTimeZone("Asia/Shanghai"),
            ),
        )
    }

    /** Old `{utc}` / `{start}` / date-part tokens stay UTC; only `${(b)…}` follows [tz]. */
    @Test
    fun template_oldTokensStayUtcWhenTzIsShanghai() {
        assertEquals(
            "20261017143000-20261017223000-1792247400-1792247400-30-20261017143000",
            CatchupUrl.fromTemplate(
                "{utc:YmdHMS}-\${(b)yyyyMMddHHmmss}-{utc}-\${start}-{duration:60}-{Y}{m}{d}{H}{M}{S}",
                acStart,
                acEnd,
                tz = TimeZone.getTimeZone("Asia/Shanghai"),
            ),
        )
    }
}
