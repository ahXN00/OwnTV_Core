package tv.own.owntv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The provider-quirk decisions behind the live 403/black-screen fix. Everything here is pure — the
 * store is session-only in-memory state, so each test starts from a clean slate.
 */
class LiveStreamQuirksTest {

    @Before fun reset() = LiveStreamQuirks.clearForTest()
    @After fun tearDown() = LiveStreamQuirks.clearForTest()

    // --- ".ts that is really HLS" ------------------------------------------------------------------

    @Test
    fun `a learned redirect applies to the whole panel, not just the channel that taught us`() {
        val taught = "http://panel.example:80/live/user/pass/136875.ts"
        val sibling = "http://panel.example:80/live/user/pass/999.ts"
        assertFalse(LiveStreamQuirks.isKnownHlsHost(sibling))
        LiveStreamQuirks.rememberHlsRedirect(taught)
        assertTrue(LiveStreamQuirks.isKnownHlsHost(sibling))
        assertTrue(LiveStreamQuirks.isHlsUrl(sibling))
    }

    /**
     * The guard behind "Prefer HLS off played nothing on ExoPlayer": a channel tuned as `.m3u8` because
     * the user asked for HLS must never be mistaken for a panel that redirects its `.ts` to a manifest.
     * Learning that lesson is host-wide, so one such channel used to force every plain `.ts` on the panel
     * into the HLS parser, which fails on the first bytes.
     */
    @Test
    fun `an explicitly requested m3u8 is not evidence that the panel redirects its ts`() {
        val hls = "http://panel.example:80/live/user/pass/136875.m3u8"
        assertTrue(LiveStreamQuirks.isExplicitHlsUrl(hls))
        assertTrue(LiveStreamQuirks.isExplicitHlsUrl("$hls?token=abc"))
        assertFalse(LiveStreamQuirks.isExplicitHlsUrl("http://panel.example:80/live/user/pass/136875.ts"))
    }

    @Test
    fun `a learned host does not make a ts URL count as explicitly HLS`() {
        val ts = "http://panel.example:80/live/user/pass/136875.ts"
        LiveStreamQuirks.rememberHlsRedirect(ts)
        assertTrue(LiveStreamQuirks.isHlsUrl(ts))
        assertFalse(LiveStreamQuirks.isExplicitHlsUrl(ts))
    }

    /**
     * Bug 8: a panel where a few channels redirect `.ts` to a manifest and the rest serve plain TS. Once
     * the host lesson sends a plain channel into the HLS parser and it fails, the lesson is wrong for this
     * panel — it must stop applying, and a later redirecting channel must not teach it back.
     */
    @Test
    fun `a host lesson disproved by a plain ts channel is dropped and not relearned`() {
        val redirecting = "http://panel.example:80/live/user/pass/6082.ts"
        val plain = "http://panel.example:80/live/user/pass/5331.ts"
        LiveStreamQuirks.rememberHlsRedirect(redirecting)
        assertTrue(LiveStreamQuirks.isKnownHlsHost(plain))

        LiveStreamQuirks.forgetHlsRedirect(plain)
        assertFalse(LiveStreamQuirks.isKnownHlsHost(plain))

        LiveStreamQuirks.rememberHlsRedirect(redirecting)
        assertFalse(LiveStreamQuirks.isKnownHlsHost(plain))
    }

    @Test
    fun `a different provider is unaffected by what one panel taught us`() {
        LiveStreamQuirks.rememberHlsRedirect("http://panel.example:80/live/u/p/1.ts")
        assertFalse(LiveStreamQuirks.isKnownHlsHost("http://other.example:80/live/u/p/1.ts"))
        assertFalse(LiveStreamQuirks.isHlsUrl("http://other.example:80/live/u/p/1.ts"))
    }

    @Test
    fun `an m3u8 URL is HLS without anyone having to learn it`() {
        assertTrue(LiveStreamQuirks.isHlsUrl("http://panel.example/live/u/p/1.m3u8?token=abc"))
        assertFalse(LiveStreamQuirks.isHlsUrl("http://panel.example/live/u/p/1.ts"))
    }

    @Test
    fun `the host key ignores credentials, path and query but keeps the port`() {
        assertEquals("panel.example:2086", LiveStreamQuirks.hostKey("http://panel.example:2086/live/a/b/1.ts?x=1"))
        assertEquals("panel.example:2086", LiveStreamQuirks.hostKey("http://u:p@PANEL.example:2086/other.m3u8"))
        // A different port is a different origin — the traced panel's CDN and origin differ exactly here.
        assertTrue(
            LiveStreamQuirks.hostKey("http://panel.example:80/a.ts") !=
                LiveStreamQuirks.hostKey("http://panel.example:2086/a.ts"),
        )
    }

    @Test
    fun `rewriting to HLS keeps the signed query intact and leaves other URLs alone`() {
        assertEquals(
            "http://panel.example/live/u/p/1.m3u8?token=abc&expires=1",
            LiveStreamQuirks.toHlsUrl("http://panel.example/live/u/p/1.ts?token=abc&expires=1"),
        )
        assertEquals("http://panel.example/live/u/p/1.m3u8", LiveStreamQuirks.toHlsUrl("http://panel.example/live/u/p/1.ts"))
        val extensionless = "http://panel.example/live/u/p/1"
        assertEquals(extensionless, LiveStreamQuirks.toHlsUrl(extensionless))
    }

    // --- "the provider refuses its own signed segment URLs" ----------------------------------------

    @Test
    fun `only a refusal status counts as the provider refusing us — a server fault must not`() {
        assertTrue(LiveStreamQuirks.isEdgeRefusal(403))
        assertTrue(LiveStreamQuirks.isEdgeRefusal(404))
        assertTrue(LiveStreamQuirks.isEdgeRefusal(410))
        assertFalse(LiveStreamQuirks.isEdgeRefusal(500))
        assertFalse(LiveStreamQuirks.isEdgeRefusal(502))
        assertFalse(LiveStreamQuirks.isEdgeRefusal(200))
    }

    @Test
    fun `one refusal is not enough to give up on ExoPlayer`() {
        // A single 403 can be a segment that rolled out of the window mid-flight; the load-error policy
        // absorbs that with one quick retry. Only a second, *different* refused segment is the signature.
        assertTrue(LiveStreamQuirks.REFUSALS_BEFORE_HANDOFF > 1)
    }

    @Test
    fun `a panel caught refusing segments is remembered for every one of its channels`() {
        val taught = "http://off20.example:2086/live/u/p/136875.m3u8"
        val sibling = "http://off20.example:2086/live/u/p/999.m3u8"
        assertFalse(LiveStreamQuirks.refusesSegments(sibling))
        LiveStreamQuirks.rememberSegmentRefusal(taught)
        assertTrue(LiveStreamQuirks.refusesSegments(sibling))
    }

    @Test
    fun `a refusing panel does not condemn a different provider`() {
        LiveStreamQuirks.rememberSegmentRefusal("http://off20.example:2086/live/u/p/1.m3u8")
        assertFalse(LiveStreamQuirks.refusesSegments("http://good.example:2086/live/u/p/1.m3u8"))
        // Nor does it make that panel's URLs look like HLS — the two quirks are independent.
        assertFalse(LiveStreamQuirks.isKnownHlsHost("http://off20.example:2086/live/u/p/1.ts"))
    }

    // --- the HLS lesson never leaves the channel it was learned on ---------------------------------

    @Test
    fun `one channel without an HLS variant says nothing about the panel`() {
        // "Prefer HLS" is a per-playlist guess, and a single channel can genuinely be TS-only on a panel
        // that otherwise serves HLS fine. Condemning the whole panel on one miss would cost every other
        // channel its faster HLS start.
        val taught = "http://panel.example:80/live/u/p/1.ts"
        val sibling = "http://panel.example:80/live/u/p/2.ts"
        LiveStreamQuirks.rememberNoHlsVariant(taught)
        assertTrue(LiveStreamQuirks.lacksHlsVariant(taught))
        assertFalse(LiveStreamQuirks.lacksHlsVariant(sibling))
    }

    @Test
    fun `no number of failing channels ever writes off the whole panel`() {
        // The panel-wide verdict was removed deliberately. It condemned a provider for the session on
        // three channels' evidence, and the failures that reached it were not necessarily about format:
        // an account-busy lockout (HTTP 458) walked three channels onto their `.ts` rung in under two
        // minutes and cost every remaining channel its HLS start until the app was restarted.
        val host = "http://nohls.example:8080/live/u/p/"
        repeat(10) { LiveStreamQuirks.rememberNoHlsVariant("$host$it.ts") }
        assertFalse(LiveStreamQuirks.lacksHlsVariant("${host}999.ts"))
        assertFalse(LiveStreamQuirks.lacksHlsVariantMpv("${host}999.ts"))
        // Each taught channel still keeps its own lesson.
        assertTrue(LiveStreamQuirks.lacksHlsVariant("${host}3.ts"))
    }

    @Test
    fun `an engine's HLS lesson stays with that engine`() {
        // Traced on a 4K channel whose `.m3u8` plays on mpv while ExoPlayer's audio renderer never
        // produces a sample from it: a shared verdict cost the one combination that worked.
        val url = "http://panel.example:80/live/u/p/7.ts"
        LiveStreamQuirks.rememberNoHlsVariant(url)
        assertTrue(LiveStreamQuirks.lacksHlsVariant(url))
        assertFalse(LiveStreamQuirks.lacksHlsVariantMpv(url))
    }

    // --- "the account only gets one session" -------------------------------------------------------

    @Test
    fun `the session-limit status is matched exactly, not as a class of refusals`() {
        // 458 means "the stream is fine, you are the second client" — the opposite conclusion to a 403,
        // which is why it must not be lumped in with the edge refusals that trigger the mpv handoff.
        assertTrue(LiveStreamQuirks.isSessionLimit(458))
        assertFalse(LiveStreamQuirks.isSessionLimit(403))
        assertFalse(LiveStreamQuirks.isSessionLimit(459))
        assertFalse(LiveStreamQuirks.isSessionLimit(200))
        assertFalse(LiveStreamQuirks.isEdgeRefusal(458))
    }

    @Test
    fun `a one-session panel is remembered for the whole account, not one channel`() {
        val taught = "http://cf.example:80/live/u/p/440438.m3u8"
        val sibling = "http://cf.example:80/live/u/p/1.ts"
        assertFalse(LiveStreamQuirks.isSingleSession(sibling))
        LiveStreamQuirks.rememberSessionLimit(taught)
        assertTrue(LiveStreamQuirks.isSingleSession(sibling))
        assertFalse(LiveStreamQuirks.isSingleSession("http://other.example:80/live/u/p/1.ts"))
        // Independent of the other two quirks.
        assertFalse(LiveStreamQuirks.refusesSegments(sibling))
        assertFalse(LiveStreamQuirks.isKnownHlsHost(sibling))
    }

    // --- "this feed's timestamps are broken" -------------------------------------------------------

    @Test
    fun `broken timestamps are learned per stream, never for the whole panel`() {
        // Free-running video timing is unsynced from audio by definition, so a healthy neighbour on the
        // same panel must not inherit it — that is what drifted the sound on a working raw MPEG-TS feed.
        val broken = "http://panel.example:80/live/u/p/4k.ts"
        val neighbour = "http://panel.example:80/live/u/p/sd.ts"
        LiveStreamQuirks.rememberBrokenTimestamps(broken)
        assertTrue(LiveStreamQuirks.hasBrokenTimestamps(broken))
        assertFalse(LiveStreamQuirks.hasBrokenTimestamps(neighbour))
    }

    @Test
    fun `one timestamp complaint is not enough to give up accurate timing`() {
        // A discontinuity at an ad break is normal live TV; the pathological feeds complain every frame.
        assertTrue(OwnTVPlayer.BROKEN_PTS_HITS > 1)
        assertTrue(OwnTVPlayer.BROKEN_PTS_RX.containsMatchIn("Invalid video timestamp: 5.000000 -> 4.000000"))
        assertTrue(OwnTVPlayer.BROKEN_PTS_RX.containsMatchIn("Audio/Video desynchronisation detected!"))
        assertFalse(OwnTVPlayer.BROKEN_PTS_RX.containsMatchIn("Using hardware decoding (mediacodec)."))
    }

    // --- "this panel's catch-up archive needs a software decoder" ----------------------------------

    @Test
    fun `an archive that renders no video teaches the whole panel, not just that programme`() {
        // Catch-up used to be pinned to software unconditionally, which cost every provider hardware
        // decoding. It is learned instead: mid-GOP archives are a property of the panel's timeshift
        // server, so one blank programme covers the rest of that panel's archive for this session…
        val archive = "http://panel.example:80/timeshift/u/p/60/2026-08-03:10-00/12.ts"
        val otherProgramme = "http://panel.example:80/timeshift/u/p/60/2026-08-03:22-00/9.ts"
        val otherPanel = "http://second.example:8080/timeshift/u/p/60/2026-08-03:10-00/12.ts"
        assertFalse(LiveStreamQuirks.archiveNeedsSoftware(archive))
        LiveStreamQuirks.rememberArchiveNeedsSoftware(archive)
        assertTrue(LiveStreamQuirks.archiveNeedsSoftware(otherProgramme))
        // …and nothing beyond it: a healthy provider keeps hardware decoding.
        assertFalse(LiveStreamQuirks.archiveNeedsSoftware(otherPanel))
    }

    @Test
    fun `a software archive panel does not inherit the other panel-wide quirks`() {
        val archive = "http://panel.example:80/timeshift/u/p/60/2026-08-03:10-00/12.ts"
        LiveStreamQuirks.rememberArchiveNeedsSoftware(archive)
        assertFalse(LiveStreamQuirks.isSingleSession(archive))
        assertFalse(LiveStreamQuirks.refusesSegments(archive))
        assertFalse(LiveStreamQuirks.isKnownHlsHost(archive))
    }

    @Test
    fun `a URL-keyed lesson set forgets its oldest entries instead of growing forever`() {
        // These five sets are keyed by stream URL, so they grew with every channel ever tuned and never
        // evicted anything. Host-keyed sets are naturally bounded and are deliberately left alone.
        val recent = "http://bound.example:80/live/u/p/99999.ts"
        LiveStreamQuirks.rememberNoHlsVariant(recent)
        assertTrue(LiveStreamQuirks.lacksHlsVariant(recent))

        // Well past the cap, all on the same host so nothing else about the panel is being tested.
        repeat(2_000) { LiveStreamQuirks.rememberNoHlsVariant("http://bound.example:80/live/u/p/$it.ts") }

        // The oldest entries are gone…
        assertFalse(LiveStreamQuirks.lacksHlsVariant(recent))
        assertFalse(LiveStreamQuirks.lacksHlsVariant("http://bound.example:80/live/u/p/0.ts"))
        // …and the newest are still answered correctly, which is the only property that matters: a
        // lesson recorded during the tune on screen is the newest entry there is.
        assertTrue(LiveStreamQuirks.lacksHlsVariant("http://bound.example:80/live/u/p/1999.ts"))
        assertTrue(LiveStreamQuirks.lacksHlsVariant("http://bound.example:80/live/u/p/1900.ts"))
    }

    @Test
    fun `the waits around a handoff are long enough to matter and short enough to feel instant`() {
        // The traced panel needed ~2 s after the other engine's socket closed before it let us back in.
        assertTrue(LivePreviewEngine.SESSION_RELEASE_MS in 1_000L..5_000L)
        // …but a wedged mpv core must never freeze the engine toggle for that long.
        assertTrue(OwnTVPlayer.MPV_RELEASE_TIMEOUT_MS < LivePreviewEngine.SESSION_RELEASE_MS)
    }
}
