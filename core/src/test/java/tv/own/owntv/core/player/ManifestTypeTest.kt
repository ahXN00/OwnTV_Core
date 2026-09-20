package tv.own.owntv.core.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.parser.M3uEntry
import tv.own.owntv.core.parser.M3uParser

/**
 * The container a playlist entry declares for itself.
 *
 * The bug this closes: a JioTV-Go style proxy publishes a DASH channel at `https://host/live/mpd/173`
 * — no extension — and declares `manifest_type=mpd` on its own `#KODIPROP` line. That line was parsed
 * and thrown away, so Media3 inferred "progressive" from the extensionless URL, was handed an XML
 * manifest after the redirect, and failed with `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` on every
 * tune. The declaration is what makes the first attempt correct.
 */
class ManifestTypeTest {

    private fun parse(text: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse(text.byteInputStream()) { out += it }
        out
    }

    // --- decode / encode ---

    @Test
    fun `the three kodi tokens decode`() {
        assertEquals(ManifestType.MPD, ManifestType.decode("mpd"))
        assertEquals(ManifestType.HLS, ManifestType.decode("hls"))
        assertEquals(ManifestType.ISM, ManifestType.decode("ism"))
    }

    @Test
    fun `decoding is case and whitespace insensitive`() {
        assertEquals(ManifestType.MPD, ManifestType.decode("MPD"))
        assertEquals(ManifestType.MPD, ManifestType.decode("  mpd  "))
    }

    /** Total, like `DrmConfig.decode`: a value we cannot interpret reads as "no declaration". */
    @Test
    fun `absent blank and unknown values decode to null`() {
        assertNull(ManifestType.decode(null))
        assertNull(ManifestType.decode(""))
        assertNull(ManifestType.decode("   "))
        assertNull(ManifestType.decode("smoothstreaming"))
    }

    @Test
    fun `encode round-trips through the stored form`() {
        ManifestType.entries.forEach { assertEquals(it, ManifestType.decode(ManifestType.encode(it))) }
        assertNull(ManifestType.encode(null))
    }

    // --- the parser wiring ---

    /** The reported playlist shape: extensionless DASH URL, declaration on its own line. */
    @Test
    fun `a protected dash entry carries its declared manifest type`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="173" group-title="News", Aaj Tak
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://tv.example/live/key/173
            https://tv.example/live/mpd/173
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals(ManifestType.MPD, entries[0].manifestType)
        assertEquals("https://tv.example/live/mpd/173", entries[0].streamUrl)
    }

    /** Not a DRM concept: an unprotected entry declaring `mpd` gets the same routing. */
    @Test
    fun `an unprotected entry can declare a manifest type too`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Free DASH
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            https://tv.example/live/mpd/9
            """.trimIndent(),
        )
        assertEquals(ManifestType.MPD, entries[0].manifestType)
        assertNull(entries[0].drm)
    }

    /** The older bare `inputstream.` spelling, same as the licence properties accept. */
    @Test
    fun `the legacy property spelling works too`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Legacy
            #KODIPROP:inputstream.manifest_type=hls
            http://host/s
            """.trimIndent(),
        )
        assertEquals(ManifestType.HLS, entries[0].manifestType)
    }

    /** A declaration belongs to the entry that made it — never to the next channel. */
    @Test
    fun `the manifest type does not leak into the following entry`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Dash
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            http://host/1
            #EXTINF:-1,Plain
            http://host/2.ts
            """.trimIndent(),
        )
        assertEquals(ManifestType.MPD, entries[0].manifestType)
        assertNull(entries[1].manifestType)
    }

    /** The overwhelming majority of playlists: no declaration, so nothing is stored and nothing
     *  changes about how the entry is routed. */
    @Test
    fun `an entry with no declaration stores nothing`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Ordinary
            http://host/1.ts
            """.trimIndent(),
        )
        assertNull(entries[0].manifestType)
    }

    /** A manifest type we do not know is dropped rather than stored, so the entry keeps exactly the
     *  behaviour it has today instead of being routed somewhere that cannot open it. */
    @Test
    fun `an unknown declaration is dropped`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Odd
            #KODIPROP:inputstream.adaptive.manifest_type=rtmp
            http://host/1
            """.trimIndent(),
        )
        assertNull(entries[0].manifestType)
    }

    /** Licence and manifest properties are collected on the same entry without consuming each other. */
    @Test
    fun `drm and manifest properties coexist on one entry`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Both
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F1.0
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://host/k
            http://host/s
            """.trimIndent(),
        )
        assertEquals(ManifestType.MPD, entries[0].manifestType)
        assertEquals("https://host/k", entries[0].drm?.licenseUrl)
        assertEquals("Kodi/1.0", entries[0].headers["User-Agent"])
    }
}
