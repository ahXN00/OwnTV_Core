package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-playlist reader, against the shapes providers actually serve.
 *
 * The fixtures are deliberately not tidy: signed query strings, absolute and relative URIs, tags this
 * parser has never heard of, and a window that has scrolled — because those are what a recording runs
 * into at two in the morning, not a textbook example.
 */
class HlsMediaPlaylistTest {

    private val live = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:2680
        #EXT-X-PROGRAM-DATE-TIME:2026-09-12T21:00:00Z
        #EXTINF:6.006,
        seg-2680.ts?token=abc123
        #EXTINF:6.006,
        seg-2681.ts?token=def456
        #EXTINF:5.994,
        seg-2682.ts?token=ghi789
    """.trimIndent()

    @Test
    fun `a live window is read as segments numbered from the media sequence`() {
        val playlist = HlsMediaPlaylist.parse(live)
        assertEquals(3, playlist.segments.size)
        assertEquals(2680L, playlist.mediaSequence)
        assertEquals(listOf(2680L, 2681L, 2682L), playlist.segments.map { it.sequence })
        assertEquals("seg-2680.ts?token=abc123", playlist.segments.first().uri)
        assertEquals(6.0, playlist.targetDurationSecs, 0.001)
        assertFalse(playlist.endList)
        assertFalse(playlist.isEncrypted)
    }

    @Test
    fun `segments are identified by sequence, so a re-signed URL is not a new segment`() {
        // The same window, one poll later, with every token rotated — which is exactly what a
        // provider that signs each segment does, and what broke Live TV once already.
        val second = HlsMediaPlaylist.parse(live.replace("token=", "token=rotated"))
        val first = HlsMediaPlaylist.parse(live)
        assertEquals(first.segments.map { it.sequence }, second.segments.map { it.sequence })
        assertTrue("the URLs must differ, or the fixture proves nothing",
            first.segments.map { it.uri } != second.segments.map { it.uri })
    }

    @Test
    fun `a finished playlist says so`() {
        val playlist = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            a.ts
            #EXTINF:4.0,
            b.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertTrue(playlist.endList)
        assertEquals(2, playlist.segments.size)
    }

    @Test
    fun `an encrypted playlist is recognised and a clear one is not`() {
        val encrypted = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.invalid/key",IV=0x00000000000000000000000000000001
            #EXTINF:6.0,
            a.ts
            """.trimIndent(),
        )
        assertTrue(encrypted.isEncrypted)
        assertEquals("AES-128", encrypted.encryptionMethod)

        // METHOD=NONE is a playlist that explicitly turned encryption off. Refusing it would be wrong.
        val cleared = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            a.ts
            """.trimIndent(),
        )
        assertFalse(cleared.isEncrypted)
    }

    @Test
    fun `a comma inside a quoted attribute does not split it`() {
        val playlist = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-KEY:URI="https://example.invalid/k?a=1,b=2",METHOD=SAMPLE-AES
            #EXTINF:6.0,
            a.ts
            """.trimIndent(),
        )
        assertEquals("SAMPLE-AES", playlist.encryptionMethod)
        assertTrue(playlist.isEncrypted)
    }

    @Test
    fun `tags this parser has never seen are ignored, not refused`() {
        // HLS gains tags all the time; a recorder that stopped at an unknown one would be broken by
        // its own strictness.
        val playlist = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-SOMETHING-FROM-2029:whatever
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-DISCONTINUITY
            #EXTINF:4.0,
            https://cdn.example.invalid/abs/7.ts
            """.trimIndent(),
        )
        assertEquals(1, playlist.segments.size)
        assertEquals(7L, playlist.segments.single().sequence)
        assertNull(playlist.encryptionMethod)
    }

    @Test
    fun `a playlist that says nothing about timing still polls sensibly`() {
        val playlist = HlsMediaPlaylist.parse("#EXTM3U\n#EXTINF:6.0,\na.ts")
        assertTrue(playlist.pollIntervalMs >= 1_000)
        assertTrue(playlist.pollIntervalMs <= 10_000)
    }

    @Test
    fun `a nonsense target duration cannot turn the poll into a spin loop`() {
        val zero = HlsMediaPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:0\n#EXTINF:0,\na.ts")
        assertEquals(1_000L, zero.pollIntervalMs)
        val huge = HlsMediaPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:86400\n#EXTINF:1,\na.ts")
        assertEquals(10_000L, huge.pollIntervalMs)
    }

    @Test
    fun `an empty playlist is empty, not a crash`() {
        val playlist = HlsMediaPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:99")
        assertTrue(playlist.segments.isEmpty())
        assertEquals(99L, playlist.mediaSequence)
    }

    // --- Telling a playlist from a stream of video ---

    @Test
    fun `the body is what decides, not the content type`() {
        // Providers label m3u8 as everything from text/plain to video/mp2t, and the extension
        // disappears behind a redirect.
        assertTrue(HlsMediaPlaylist.looksLikePlaylist("text/plain", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertTrue(HlsMediaPlaylist.looksLikePlaylist(null, "  \n#EXTM3U\n"))
        assertTrue(HlsMediaPlaylist.looksLikePlaylist("application/vnd.apple.mpegurl", "garbled"))
        assertTrue(HlsMediaPlaylist.looksLikePlaylist("application/x-mpegURL", ""))
    }

    @Test
    fun `MPEG-TS video is not mistaken for a playlist`() {
        // A ts packet starts with 0x47, not with a hash.
        assertFalse(HlsMediaPlaylist.looksLikePlaylist("video/mp2t", "G@"))
        assertFalse(HlsMediaPlaylist.looksLikePlaylist(null, "binary rubbish"))
    }
}
