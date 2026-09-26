package tv.own.owntv.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The download folder falls back only while the chosen one is really not there. */
class StorageFallbackTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a chosen folder that exists is used`() {
        assertTrue(StorageAccess.isUsableDir(temp.newFolder("usb")))
    }

    @Test
    fun `a chosen folder that is missing but can be made is used`() {
        assertTrue(StorageAccess.isUsableDir(File(temp.root, "OwnTV/Downloads")))
    }

    @Test
    fun `a folder whose volume is gone is not used`() {
        // A removed stick: the path's parent is not a directory any more, so nothing can be made there.
        val notADirectory = temp.newFile("unmounted")
        assertFalse(StorageAccess.isUsableDir(File(notADirectory, "OwnTV")))
    }
}
