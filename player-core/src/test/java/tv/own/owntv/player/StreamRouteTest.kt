package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.player.ManifestType

/**
 * Which media source opens a live load — [LivePreviewEngine.routeFor].
 *
 * The bug this closes: routing used to be binary, HLS or progressive, with DASH having no way in at
 * all even though `media3-exoplayer-dash` was already on the classpath. A channel published at
 * `https://host/live/mpd/173` — extensionless, redirecting to an `.mpd` only once requested — was
 * therefore handed to the progressive extractor, which sniffed an XML manifest and failed with
 * `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` before the first frame. With DRM in play the engine is
 * pinned to ExoPlayer (mpv has no CDM), so there was no fallback rung left and the channel died.
 *
 * The precedence order is the substance here, not the individual answers: it decides which piece of
 * evidence wins when two disagree, and getting it wrong is invisible until a specific panel breaks.
 */
class StreamRouteTest {

    private fun route(
        declared: ManifestType? = null,
        forceHls: Boolean = false,
        forceDash: Boolean = false,
        knownHlsHost: Boolean = false,
        knownDashHost: Boolean = false,
        inferredHls: Boolean = false,
    ) = LivePreviewEngine.routeFor(declared, forceHls, forceDash, knownHlsHost, knownDashHost, inferredHls)

    // --- the default, which must not have moved ---

    /** The overwhelming majority: raw MPEG-TS Xtream live, nothing declared, nothing inferred. */
    @Test
    fun `no evidence at all stays progressive`() {
        assertEquals(StreamRoute.PROGRESSIVE, route())
    }

    @Test
    fun `an inferred m3u8 url still routes to hls`() {
        assertEquals(StreamRoute.HLS, route(inferredHls = true))
    }

    @Test
    fun `a panel already caught redirecting to a manifest still routes to hls`() {
        assertEquals(StreamRoute.HLS, route(knownHlsHost = true))
    }

    // --- the declaration ---

    /** The reported case. */
    @Test
    fun `a declared mpd routes to dash`() {
        assertEquals(StreamRoute.DASH, route(declared = ManifestType.MPD))
    }

    @Test
    fun `a declared hls routes to hls even with no extension to infer from`() {
        assertEquals(StreamRoute.HLS, route(declared = ManifestType.HLS))
    }

    /**
     * `ism` is stored but deliberately not routed — `media3-exoplayer-smoothstreaming` is not a
     * dependency, so sending it anywhere would swap one failure for another. It must keep exactly the
     * behaviour it had before the declaration was read at all.
     */
    @Test
    fun `a declared ism falls through to what would have happened anyway`() {
        assertEquals(StreamRoute.PROGRESSIVE, route(declared = ManifestType.ISM))
        assertEquals(StreamRoute.HLS, route(declared = ManifestType.ISM, inferredHls = true))
    }

    // --- precedence, which is the whole point ---

    /**
     * A forced retry is the only input backed by an observed failure of the alternative *on this very
     * load*, so it outranks even a declaration. Without this, a playlist that declares `mpd` wrongly
     * could never be rescued by the retry ladder — it would keep choosing DASH forever.
     */
    @Test
    fun `a forced hls retry outranks a declaration`() {
        assertEquals(StreamRoute.HLS, route(declared = ManifestType.MPD, forceHls = true))
    }

    /**
     * A declaration is about THIS channel; `knownHlsHost` is a lesson learned from some *other* channel
     * on the same panel. The specific evidence wins — a panel that mixes HLS and DASH channels (which
     * is exactly what the reporter's playlist does) would otherwise have its DASH channels dragged to
     * the HLS factory by the first HLS channel anyone happened to open.
     */
    @Test
    fun `a declaration outranks a panel-wide hls lesson`() {
        assertEquals(StreamRoute.DASH, route(declared = ManifestType.MPD, knownHlsHost = true))
    }

    /** Likewise against Media3's own guess from the URL. */
    @Test
    fun `a declaration outranks url inference`() {
        assertEquals(StreamRoute.DASH, route(declared = ManifestType.MPD, inferredHls = true))
    }

    // --- the response sniff, which is the only route for an undeclared stream ---

    /**
     * The Stalker / Xtream case. A portal hands back its own `cmd` and an Xtream live URL is one we
     * build ourselves as `.ts`, so neither can ever carry a `manifest_type`. Without this rung those
     * two source types could not reach DASH at all, no matter what the server served.
     */
    @Test
    fun `a forced dash retry routes to dash with nothing declared`() {
        assertEquals(StreamRoute.DASH, route(forceDash = true))
    }

    @Test
    fun `a panel caught serving manifests routes to dash`() {
        assertEquals(StreamRoute.DASH, route(knownDashHost = true))
    }

    /** Same reasoning as the HLS rung: an observed failure of the alternative beats a declaration. */
    @Test
    fun `a forced dash retry outranks a declaration`() {
        assertEquals(StreamRoute.DASH, route(declared = ManifestType.HLS, forceDash = true))
    }

    /** A declaration is per-channel; a host lesson came from a different channel. Specific wins. */
    @Test
    fun `a declaration outranks a panel-wide dash lesson`() {
        assertEquals(StreamRoute.HLS, route(declared = ManifestType.HLS, knownDashHost = true))
    }

    /**
     * The HLS host lesson is also set by any plain `.m3u8` URL on the same host, so it is the weaker
     * finding of the two; a host actually caught serving MPDs is the more specific one.
     */
    @Test
    fun `the dash host lesson outranks the hls one`() {
        assertEquals(StreamRoute.DASH, route(knownHlsHost = true, knownDashHost = true))
    }

    /**
     * The two forced rungs cannot both be live — each is one-shot and the alternate-format rung clears
     * both — but if they ever were, HLS must win rather than the answer depending on field order.
     */
    @Test
    fun `hls wins if both forced flags are somehow set`() {
        assertEquals(StreamRoute.HLS, route(forceHls = true, forceDash = true))
    }

    // --- what the stream-info overlay shows ---

    /**
     * A reported symptom in its own right: a DASH channel showed **MPEG-TS** in the Stream info
     * overlay's Format row. The row was `if (activeIsHls) "HLS" else "MPEG-TS"` — a two-value field
     * that could not express a third route, so DASH fell into the else and read as raw TS. It stayed
     * wrong even on a failed tune, which is what the user actually screenshotted.
     *
     * The labels are also what someone reads back to us in a bug report, so pin them verbatim.
     */
    @Test
    fun `each route reports its own format label`() {
        assertEquals("DASH", StreamRoute.DASH.formatLabel)
        assertEquals("HLS", StreamRoute.HLS.formatLabel)
        assertEquals("MPEG-TS", StreamRoute.PROGRESSIVE.formatLabel)
    }

    /** No two routes may share a label, or the overlay cannot distinguish them. */
    @Test
    fun `the format labels are distinct`() {
        assertEquals(StreamRoute.entries.size, StreamRoute.entries.map { it.formatLabel }.toSet().size)
    }

    /** Every route is reachable, so no branch is dead. */
    @Test
    fun `all three routes are reachable`() {
        assertEquals(
            setOf(StreamRoute.PROGRESSIVE, StreamRoute.HLS, StreamRoute.DASH),
            setOf(route(), route(inferredHls = true), route(declared = ManifestType.MPD)),
        )
    }
}
