package tv.own.owntv.core.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What happens to a picture between the user choosing it and a profile showing it: it is copied in
 * (never referenced where it lay), cropped square, and scaled down.
 *
 * Instrumentation rather than a unit test because every part of it is `android.graphics`, which does
 * not exist on the JVM.
 */
class ProfileAvatarStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ProfileAvatarStore(context)

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun sizeOf(path: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return opts.outWidth to opts.outHeight
    }

    @Test
    fun save_cropsToASquareAndScalesDown() = runBlocking {
        val path = store.save(1L, ByteArrayInputStream(jpeg(1600, 900)))
        assertNotNull(path)
        val (w, h) = sizeOf(path!!)
        assertEquals("a picture must be square, not squashed", w, h)
        assertEquals(ProfileAvatarStore.SIZE_PX, w)
        store.clear(1L)
    }

    /** A picture already smaller than the cap is not blown up — that would only lose sharpness. */
    @Test
    fun save_leavesASmallPictureAtItsOwnSize() = runBlocking {
        val path = store.save(2L, ByteArrayInputStream(jpeg(120, 200)))
        assertNotNull(path)
        val (w, h) = sizeOf(path!!)
        assertEquals(120, w)
        assertEquals(120, h)
        store.clear(2L)
    }

    @Test
    fun save_copiesTheFileSoTheOriginalCanGoAway() = runBlocking {
        val source = File(context.cacheDir, "avatar-source.jpg").apply { writeBytes(jpeg(400, 400)) }
        val path = store.save(3L, source)
        assertNotNull(path)
        assertTrue("the picture must not live where the user left it", source.delete())
        assertTrue("and must still be there afterwards", File(path!!).exists())
        store.clear(3L)
    }

    @Test
    fun save_refusesSomethingThatIsNotAnImage() = runBlocking {
        assertNull(store.save(4L, ByteArrayInputStream("this is not a picture".toByteArray())))
        assertFalse(store.fileFor(4L).exists())
    }

    @Test
    fun save_replacesTheProfilesPreviousPicture() = runBlocking {
        val first = store.save(5L, ByteArrayInputStream(jpeg(800, 800)))
        val second = store.save(5L, ByteArrayInputStream(jpeg(300, 300)))
        assertEquals("one profile keeps one picture, not a pile of them", first, second)
        assertEquals(300, sizeOf(second!!).first)
        store.clear(5L)
    }

    @Test
    fun clear_removesThePicture() = runBlocking {
        store.save(6L, ByteArrayInputStream(jpeg(400, 400)))
        store.clear(6L)
        assertFalse(store.fileFor(6L).exists())
        // Clearing a profile that never had one is not an error.
        store.clear(6L)
    }
}
