package tv.own.owntv.core.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The v43 fields (`manifestType`, `directSource`) are folded into the content hash **only when the row
 * actually carries one**.
 *
 * This is the whole reason the folding in `computeContentHash()` is written as a ladder of null checks
 * rather than one flat `Objects.hash(...)`. Folding unconditionally would change every stored hash at
 * once, so the next resync of a 170k-item catalog would rewrite every row for every user — for a field
 * that is null on well over 99% of them. The KDoc on `ChannelEntity.computeContentHash` says so; these
 * tests are what stops a later edit from quietly undoing it.
 *
 * The other half matters too: a row that *does* declare something must still see a change propagate,
 * or a corrected playlist would never reach the database.
 */
class ContentHashV43Test {

    private fun channel(
        manifestType: String? = null,
        directSource: String? = null,
        catchupType: String? = null,
        httpHeaders: String? = null,
        drmConfig: String? = null,
    ) = ChannelEntity(
        sourceId = 1L,
        name = "Aaj Tak",
        streamUrl = "https://tv.example/live/mpd/173",
        manifestType = manifestType,
        directSource = directSource,
        catchupType = catchupType,
        httpHeaders = httpHeaders,
        drmConfig = drmConfig,
    )

    private fun movie(manifestType: String? = null, drmConfig: String? = null, httpHeaders: String? = null) =
        MovieEntity(
            sourceId = 1L,
            name = "A Film",
            streamUrl = "https://tv.example/movie/1",
            manifestType = manifestType,
            drmConfig = drmConfig,
            httpHeaders = httpHeaders,
        )

    // --- the no-rewrite guarantee ---

    /** The upgrade case: every existing row is NULL in both new columns, so every stored hash must be
     *  byte-identical to what a pre-v43 build computed — i.e. the plain base hash. */
    @Test
    fun `a channel with neither v43 field hashes exactly as the base`() {
        val plain = channel()
        val expectedBase = java.util.Objects.hash(
            plain.sourceId, plain.categoryId, plain.name, plain.logoUrl, plain.streamUrl,
            plain.epgChannelId, plain.number, plain.remoteId, plain.catchup, plain.catchupDays,
            plain.catchupSource,
        )
        assertEquals(expectedBase, plain.computeContentHash())
    }

    @Test
    fun `a movie with no manifest type hashes exactly as the base`() {
        val plain = movie()
        val expectedBase = java.util.Objects.hash(
            plain.sourceId, plain.categoryId, plain.name, plain.posterUrl, plain.backdropUrl,
            plain.year, plain.rating, plain.durationSecs, plain.plot, plain.streamUrl,
            plain.containerExt, plain.remoteId, plain.addedAt,
        )
        assertEquals(expectedBase, plain.computeContentHash())
    }

    /** A row that already carried a v33 licence and nothing new must keep its v33 hash, so adding the
     *  columns does not rewrite the protected channels either. */
    @Test
    fun `a channel with only the older optional fields is unchanged by v43`() {
        val withDrm = channel(drmConfig = """{"scheme":"widevine","license":"https://h/k"}""")
        val base = java.util.Objects.hash(
            withDrm.sourceId, withDrm.categoryId, withDrm.name, withDrm.logoUrl, withDrm.streamUrl,
            withDrm.epgChannelId, withDrm.number, withDrm.remoteId, withDrm.catchup,
            withDrm.catchupDays, withDrm.catchupSource,
        )
        val withV26 = java.util.Objects.hash(base, withDrm.catchupType, withDrm.httpHeaders)
        assertEquals(java.util.Objects.hash(withV26, withDrm.drmConfig), withDrm.computeContentHash())
    }

    // --- changes still propagate ---

    @Test
    fun `declaring a manifest type changes a channel hash`() {
        assertNotEquals(channel().computeContentHash(), channel(manifestType = "mpd").computeContentHash())
    }

    @Test
    fun `changing a declared manifest type changes a channel hash`() {
        assertNotEquals(
            channel(manifestType = "mpd").computeContentHash(),
            channel(manifestType = "hls").computeContentHash(),
        )
    }

    @Test
    fun `gaining a direct source changes a channel hash`() {
        assertNotEquals(
            channel().computeContentHash(),
            channel(directSource = "http://10.0.0.5:8080/live/1.ts").computeContentHash(),
        )
    }

    /** The two v43 fields are independent — one must not mask a change in the other. */
    @Test
    fun `the two v43 fields do not mask each other`() {
        assertNotEquals(
            channel(manifestType = "mpd", directSource = "http://a/1").computeContentHash(),
            channel(manifestType = "mpd", directSource = "http://b/1").computeContentHash(),
        )
        assertNotEquals(
            channel(manifestType = "mpd", directSource = "http://a/1").computeContentHash(),
            channel(manifestType = "hls", directSource = "http://a/1").computeContentHash(),
        )
    }

    @Test
    fun `declaring a manifest type changes a movie hash`() {
        assertNotEquals(movie().computeContentHash(), movie(manifestType = "mpd").computeContentHash())
    }

    /** A declaration must not be swallowed by a licence that is also present. */
    @Test
    fun `a movie manifest type still propagates alongside drm`() {
        val drm = """{"scheme":"widevine","license":"https://h/k"}"""
        assertNotEquals(
            movie(drmConfig = drm).computeContentHash(),
            movie(drmConfig = drm, manifestType = "mpd").computeContentHash(),
        )
    }
}
