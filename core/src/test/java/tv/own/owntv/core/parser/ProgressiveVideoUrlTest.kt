package tv.own.owntv.core.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stream URLs read as a single film file. See [ProgressiveVideoUrl]. */
class ProgressiveVideoUrlTest {

    private fun film(url: String) = assertTrue(url, ProgressiveVideoUrl.matches(url))
    private fun notFilm(url: String) = assertFalse(url, ProgressiveVideoUrl.matches(url))

    @Test
    fun commonFilmContainersMatch() {
        listOf("mp4", "m4v", "mkv", "avi", "mov", "wmv", "webm", "mpg", "mpeg").forEach {
            film("http://example.test/movie/123.$it")
        }
    }

    @Test
    fun extensionIsCaseInsensitive() {
        film("http://example.test/films/Big%20Buck%20Bunny.MP4")
        film("HTTPS://example.test/films/sintel.MkV")
    }

    @Test
    fun queryAndFragmentAreIgnored() {
        film("http://example.test/movie/1.mp4?token=abc&exp=1")
        film("http://example.test/movie/1.mkv#t=30")
        notFilm("http://example.test/live/1.m3u8?file=movie.mp4")
        notFilm("http://example.test/live/1#movie.mp4")
    }

    @Test
    fun liveAndManifestFormatsNeverMatch() {
        notFilm("http://example.test/live/user/pass/1.ts")
        notFilm("http://example.test/hls/channel.m3u8")
        notFilm("http://example.test/dash/channel.mpd")
        notFilm("http://example.test/live/stream.flv")
        notFilm("http://example.test/live/user/pass/1")
    }

    @Test
    fun onlyTheLastPathSegmentCounts() {
        notFilm("http://example.test/movies.mp4/live/1.ts")
        notFilm("http://example.test/archive.mkv/")
        notFilm("http://film.mp4")
        notFilm("http://film.mp4/")
    }

    @Test
    fun streamingSchemesNeverMatch() {
        notFilm("rtmp://example.test/vod/film.mp4")
        notFilm("rtsp://example.test/film.mp4")
        notFilm("udp://@239.0.0.1:1234/film.mp4")
        notFilm("rtp://239.0.0.1:5000/film.mkv")
    }

    @Test
    fun localFilesMatch() {
        film("file:///storage/emulated/0/Movies/film.mkv")
        film("/storage/emulated/0/Movies/film.mp4")
    }

    @Test
    fun dotsWithoutAnExtensionDoNotMatch() {
        notFilm("http://example.test/movie/.mp4")
        notFilm("http://example.test/movie/film.")
        notFilm("http://example.test/movie/mp4")
        notFilm("")
    }
}
