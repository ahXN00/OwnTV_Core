package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Format row's vocabulary.
 *
 * The point of this file is the *agreement* tests at the bottom: the same film must read the same
 * word whether mpv or ExoPlayer is decoding it. Before this, mpv reported FFmpeg's raw demuxer name
 * (`MOV,MP4,M4A,3GP,3G2,MJ2`) and ExoPlayer VOD reported nothing at all.
 */
class StreamFormatLabelsTest {

    // ---- mpv's side: FFmpeg demuxer names, as `file-format` reports them ----

    @Test
    fun `mpv demuxer names map onto the shared labels`() {
        assertEquals("MP4", StreamFormatLabels.ofContainerName("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("MKV", StreamFormatLabels.ofContainerName("matroska,webm"))
        assertEquals("HLS", StreamFormatLabels.ofContainerName("hls"))
        assertEquals("MPEG-TS", StreamFormatLabels.ofContainerName("mpegts"))
        assertEquals("DASH", StreamFormatLabels.ofContainerName("dash"))
    }

    /** mpv keeps its raw name for anything we do not know — true, if ugly, beats a missing row. */
    @Test
    fun `an unknown container is not claimed`() {
        assertNull(StreamFormatLabels.ofContainerName("avi"))
        assertNull(StreamFormatLabels.ofContainerName("flac"))
        assertNull(StreamFormatLabels.ofContainerName(null))
        assertNull(StreamFormatLabels.ofContainerName("  "))
    }

    // ---- ExoPlayer's side: Media3 MIME types ----

    @Test
    fun `media3 mime types map onto the shared labels`() {
        assertEquals("DASH", StreamFormatLabels.ofContainerName("application/dash+xml"))
        assertEquals("HLS", StreamFormatLabels.ofContainerName("application/x-mpegURL"))
        assertEquals("MPEG-TS", StreamFormatLabels.ofContainerName("video/mp2t"))
        assertEquals("MP4", StreamFormatLabels.ofContainerName("video/mp4"))
        assertEquals("MKV", StreamFormatLabels.ofContainerName("video/x-matroska"))
        assertEquals("MKV", StreamFormatLabels.ofContainerName("video/webm"))
    }

    /**
     * Ordering guard. `application/x-mpegurl` contains `mpeg`, and an HLS arm placed after the
     * MPEG-TS one would answer `MPEG-TS` for every HLS playlist — the exact two-value confusion the
     * live Format row used to have.
     */
    @Test
    fun `an HLS mime type is never mistaken for MPEG-TS`() {
        assertEquals("HLS", StreamFormatLabels.ofContainerName("application/x-mpegurl"))
        assertEquals("HLS", StreamFormatLabels.ofContainerName("audio/mpegurl"))
    }

    // ---- extensions, the last resort ----

    @Test
    fun `file extensions map exactly`() {
        assertEquals("HLS", StreamFormatLabels.ofFileExtension("m3u8"))
        assertEquals("DASH", StreamFormatLabels.ofFileExtension("mpd"))
        assertEquals("MPEG-TS", StreamFormatLabels.ofFileExtension("ts"))
        assertEquals("MKV", StreamFormatLabels.ofFileExtension("mkv"))
        assertEquals("MP4", StreamFormatLabels.ofFileExtension("MP4"))
        assertNull(StreamFormatLabels.ofFileExtension("avi"))
        assertNull(StreamFormatLabels.ofFileExtension(""))
        assertNull(StreamFormatLabels.ofFileExtension(null))
    }

    // ---- the resolution chain ----

    /** The resolved container wins over what we asked for: we may have requested nothing, or the
     *  server may have served something else entirely. */
    @Test
    fun `the resolved container beats the requested one`() {
        assertEquals(
            "MKV",
            StreamFormatLabels.resolve(
                containerMimeType = "video/x-matroska",
                requestedMimeType = "video/mp4",
                path = "/movies/film.mp4",
            ),
        )
    }

    @Test
    fun `the requested mime type is used when nothing was resolved`() {
        assertEquals(
            "DASH",
            StreamFormatLabels.resolve(containerMimeType = null, requestedMimeType = "application/dash+xml", path = null),
        )
    }

    @Test
    fun `the path extension is the last resort`() {
        assertEquals(
            "MP4",
            StreamFormatLabels.resolve(containerMimeType = null, requestedMimeType = null, path = "/vod/1234/Film.mp4"),
        )
    }

    /** A dot in a directory name must not be read as the file's extension. */
    @Test
    fun `only the last path segment supplies the extension`() {
        assertNull(
            StreamFormatLabels.resolve(containerMimeType = null, requestedMimeType = null, path = "/tv.show/episode"),
        )
    }

    /** Nothing identifies the container — the caller omits the row rather than inventing one. */
    @Test
    fun `resolve gives up rather than guessing`() {
        assertNull(StreamFormatLabels.resolve(null, null, null))
        assertNull(StreamFormatLabels.resolve("video/x-msvideo", null, "/vod/film.avi"))
    }

    // ---- the agreement this whole file exists for ----

    /**
     * The same MP4 film, as each engine sees it. mpv reads FFmpeg's `file-format`; ExoPlayer reads
     * the container Media3 resolved. If these two ever disagree, a user's bug report says one thing
     * and the other engine's overlay says another.
     */
    @Test
    fun `mpv and ExoPlayer agree on an MP4 film`() {
        val mpv = StreamFormatLabels.ofContainerName("mov,mp4,m4a,3gp,3g2,mj2")
        val exo = StreamFormatLabels.resolve("video/mp4", null, "/vod/film.mp4")
        assertEquals("MP4", mpv)
        assertEquals(mpv, exo)
    }

    @Test
    fun `mpv and ExoPlayer agree on an MKV film`() {
        val mpv = StreamFormatLabels.ofContainerName("matroska,webm")
        val exo = StreamFormatLabels.resolve("video/x-matroska", null, "/vod/film.mkv")
        assertEquals("MKV", mpv)
        assertEquals(mpv, exo)
    }

    /** And the live routes, which had their own labels first, must use these same strings. */
    @Test
    fun `the live routes draw their labels from here`() {
        assertEquals(StreamFormatLabels.HLS, StreamRoute.HLS.formatLabel)
        assertEquals(StreamFormatLabels.DASH, StreamRoute.DASH.formatLabel)
        assertEquals(StreamFormatLabels.MPEG_TS, StreamRoute.PROGRESSIVE.formatLabel)
    }
}
