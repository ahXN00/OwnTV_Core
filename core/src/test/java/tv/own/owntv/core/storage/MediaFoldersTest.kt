package tv.own.owntv.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one thing this phase must not do is move anybody's files: every path here has to come out
 * byte-identical to what the two apps were building by hand before.
 */
class MediaFoldersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the folder names are the ones already on disk`() {
        assertEquals("TV", MediaFolders.TV)
        assertEquals("Movies", MediaFolders.MOVIES)
        assertEquals("Series", MediaFolders.SERIES)
    }

    @Test
    fun `a season path matches what both apps built by hand`() {
        assertEquals("Series/The Wire/Season 3", MediaFolders.seasonDir("The Wire", 3))
    }

    @Test
    fun `a show name that cannot be a directory is sanitised, not rejected`() {
        val dir = MediaFolders.seasonDir("Marvel's What If…?", 1)
        assertTrue(dir.startsWith("Series/"))
        assertTrue(dir.endsWith("/Season 1"))
        // Whatever sanitise does to it, the result must still be one path segment for the show.
        assertEquals(3, dir.split("/").size)
    }

    @Test
    fun `a crumb starts at the library folder, not at the volume`() {
        assertEquals(
            "Series › The Wire › Season 3",
            MediaFolders.crumb(
                "/storage/emulated/0/Android/data/tv.own.owntv/files/OwnTV/Series/The Wire/Season 3/ep1.mkv",
                " › ",
            ),
        )
        // A film sits directly in Movies, so its trail is that one step.
        assertEquals("Movies", MediaFolders.crumb("/sdcard/OwnTV/Movies/Interstellar.mp4", " › "))
    }

    @Test
    fun `a file under none of the three still says something useful`() {
        assertEquals("b › c › d", MediaFolders.crumb("/a/b/c/d/file.mp4", " › "))
    }

    @Test
    fun `no path yet means no crumb`() {
        assertEquals(null, MediaFolders.crumb(null, " › "))
        assertEquals(null, MediaFolders.crumb("", " › "))
    }

    @Test
    fun `ensureIn creates all three and is safe to call twice`() {
        val root = temp.newFolder("OwnTV")
        MediaFolders.ensureIn(root)
        MediaFolders.ensureIn(root)
        listOf(MediaFolders.TV, MediaFolders.MOVIES, MediaFolders.SERIES).forEach { name ->
            assertTrue("$name should exist", java.io.File(root, name).isDirectory)
        }
    }
}
