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
    fun `an AES-128 playlist is recordable and a cleared one is clear`() {
        val encrypted = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.invalid/key",IV=0x00000000000000000000000000000001
            #EXTINF:6.0,
            a.ts
            """.trimIndent(),
        )
        // AES-128 is plain HLS encryption with a public key: recorded and decrypted (#243), not refused.
        assertFalse(encrypted.isEncrypted)
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

    // --- Master playlists (#243): a list of qualities, not of segments ---

    /** The shape the Australian 7/9/10 channels serve: qualities, each its own media playlist. */
    private val master = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=960x540,CODECS="avc1.4d401f,mp4a.40.2"
        540/index.m3u8?hdnts=exp=1~hmac=aa
        #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        1080/index.m3u8?hdnts=exp=1~hmac=bb
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        720/index.m3u8?hdnts=exp=1~hmac=cc
    """.trimIndent()

    @Test
    fun `a master playlist is recognised as one`() {
        assertTrue(HlsMediaPlaylist.isMaster(master))
        assertFalse(HlsMediaPlaylist.isMaster(live))
    }

    @Test
    fun `the best quality of a master playlist is the one recorded`() {
        assertEquals("1080/index.m3u8?hdnts=exp=1~hmac=bb", HlsMediaPlaylist.bestVariant(master))
    }

    @Test
    fun `a master playlist without bandwidths still gives a quality, the first listed`() {
        val bare = "#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1\nlow.m3u8\n#EXT-X-STREAM-INF:PROGRAM-ID=1\nhigh.m3u8"
        assertEquals("low.m3u8", HlsMediaPlaylist.bestVariant(bare))
    }

    @Test
    fun `a media playlist has no quality to choose`() {
        assertNull(HlsMediaPlaylist.bestVariant(live))
    }

    @Test
    fun `a segment body that is a playlist is never video`() {
        // What #243 wrote into its recordings: the quality playlists, read as if they were segments.
        assertTrue(HlsMediaPlaylist.isPlaylistBody("#EXTM3U\n#EXTINF:6,\nseg.ts".toByteArray()))
        assertTrue(HlsMediaPlaylist.isPlaylistBody("\uFEFF  #EXTM3U".toByteArray()))
        assertFalse(HlsMediaPlaylist.isPlaylistBody(byteArrayOf(0x47, 0x40, 0x11, 0x10)))
        assertFalse(HlsMediaPlaylist.isPlaylistBody(ByteArray(0)))
    }

    // --- AES-128 (#243): the key, the IV, and the bytes ---

    private val aes = """
        #EXTM3U
        #EXT-X-TARGETDURATION:5
        #EXT-X-MEDIA-SEQUENCE:40
        #EXT-X-KEY:METHOD=AES-128,URI="k1.key?hdnts=a",IV=0x387A266BFD8447B98DDD55B1420DCD60
        #EXTINF:5.0,
        a.ts
        #EXT-X-KEY:METHOD=AES-128,URI="k2.key"
        #EXTINF:5.0,
        b.ts
        #EXT-X-KEY:METHOD=NONE
        #EXTINF:5.0,
        c.ts
    """.trimIndent()

    @Test
    fun `each segment carries the key in force where it is listed`() {
        val segments = HlsMediaPlaylist.parse(aes).segments
        assertEquals("k1.key?hdnts=a", segments[0].key?.uri)
        assertEquals("k2.key", segments[1].key?.uri)
        // A key rotated mid-window applies from that line on; METHOD=NONE ends encryption.
        assertNull(segments[2].key)
    }

    @Test
    fun `a stated IV is used as written`() {
        val segment = HlsMediaPlaylist.parse(aes).segments[0]
        assertEquals("387a266bfd8447b98ddd55b1420dcd60", HlsAes.ivFor(segment).toHex())
    }

    @Test
    fun `with no IV the segment's sequence number is the IV`() {
        // RFC 8216 5.2: the media sequence number as a 128-bit big-endian integer. b.ts is 41.
        val segment = HlsMediaPlaylist.parse(aes).segments[1]
        assertEquals("00000000000000000000000000000029", HlsAes.ivFor(segment).toHex())
    }

    @Test
    fun `a segment decrypts back to the transport stream it was`() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val ts = ByteArray(188 * 3) { if (it % 188 == 0) 0x47 else (it % 251).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val encrypted = cipher.doFinal(ts)
        assertTrue(ts.contentEquals(HlsAes.decrypt(encrypted, key, iv)))
    }

    @Test
    fun `sample-aes is still refused`() {
        val playlist = HlsMediaPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="k"
            #EXTINF:5,
            a.ts
            """.trimIndent(),
        )
        assertTrue(playlist.isEncrypted)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
