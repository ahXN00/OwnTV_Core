package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Untagged M3U entries pointing at a film file are VOD, so the sync files them under Movies; any
 * `type` / `tvg-type` tag wins over the URL. See [M3uEntry.isVod] and [ProgressiveVideoUrl].
 */
class M3uVodByExtensionTest {

    private fun parse(vararg lines: String): List<M3uEntry> = runBlocking {
        val out = mutableListOf<M3uEntry>()
        M3uParser().parse((listOf("#EXTM3U") + lines).joinToString("\n").byteInputStream()) { out += it }
        out
    }

    private fun single(extinf: String, url: String): M3uEntry = parse(extinf, url).single()

    @Test
    fun untaggedFilmIsVod() {
        val e = single("""#EXTINF:-1 group-title="Films",Big Buck Bunny (2008)""", "http://example.test/movie/u/p/101.mkv")
        assertTrue(e.isVod)
        assertFalse(e.isSeries)
    }

    @Test
    fun untaggedFilmWithQueryAndUppercaseIsVod() {
        assertTrue(single("#EXTINF:-1,Sintel", "http://example.test/vod/Sintel.MP4?token=abc").isVod)
    }

    @Test
    fun untaggedLiveStreamsStayLive() {
        assertFalse(single("#EXTINF:-1,News One", "http://example.test/live/u/p/1.ts").isVod)
        assertFalse(single("#EXTINF:-1,News Two", "http://example.test/hls/two.m3u8").isVod)
        assertFalse(single("#EXTINF:-1,News Three", "http://example.test/live/u/p/3").isVod)
        assertFalse(single("#EXTINF:-1,News Four", "http://example.test/movies.mp4/live/4.ts").isVod)
    }

    @Test
    fun explicitLiveTagWinsOverTheExtension() {
        assertFalse(single("""#EXTINF:-1 tvg-type="live",Loop Channel""", "http://example.test/loop.mp4").isVod)
        assertFalse(single("""#EXTINF:-1 type="stream",Loop Channel""", "http://example.test/loop.mp4").isVod)
    }

    @Test
    fun explicitVodTagsKeepWorkingWhateverTheUrl() {
        assertTrue(single("""#EXTINF:-1 tvg-type="movie",Tagged Film""", "http://example.test/vod/1.m3u8").isVod)
        val series = single("""#EXTINF:-1 type="series",Show S01E02""", "http://example.test/series/2.mkv")
        assertTrue(series.isSeries)
        assertTrue(series.isVod)
    }

    /** A blank tag is no tag: the parser drops blank attributes, so the URL decides. */
    @Test
    fun blankTagFallsBackToTheUrl() {
        assertTrue(single("""#EXTINF:-1 tvg-type="",Untitled Film""", "http://example.test/vod/9.mp4").isVod)
    }

    /** Header suffixes after `|` belong to the request, not to the path. */
    @Test
    fun pipeHeaderSuffixDoesNotHideTheExtension() {
        assertTrue(single("#EXTINF:-1,Piped Film", "http://example.test/vod/7.mp4|User-Agent=Foo").isVod)
    }

    /** Untagged episode-looking files are films too: series grouping still needs a series tag. */
    @Test
    fun untaggedEpisodeNameIsAMovieNotASeries() {
        val e = single("#EXTINF:-1,Some Show S01E05", "http://example.test/vod/55.mkv")
        assertTrue(e.isVod)
        assertFalse(e.isSeries)
    }
}
