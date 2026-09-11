package tv.own.owntv.core.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Where a profile's own picture lives once the user has chosen one.
 *
 * Both apps let a picture in from more than one direction — a file on the television's storage, a
 * photo sent from a phone over the local network, the phone's own photo picker — and every one of
 * them ends here, so the rules are written once: the image is **copied** into app-private storage
 * (a picture on a USB stick must not vanish when the stick does), cropped square about its centre,
 * scaled down to [SIZE_PX], and written as JPEG.
 *
 * Scaling is the point, not a nicety. A profile picture is drawn at about 40 dp in a settings row
 * and 96 dp on the profile gate; keeping a 12-megapixel phone photo to decode on every one of those
 * would cost far more memory than the whole rest of the screen.
 */
class ProfileAvatarStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR)

    /** Where [profileId]'s picture would live. Present on disk only if one has been saved. */
    fun fileFor(profileId: Long): File = File(dir, "profile_$profileId.jpg")

    /**
     * Copy [source] in as [profileId]'s picture and return its absolute path, or null if the bytes
     * were not a readable image. Any previous picture for that profile is replaced.
     */
    suspend fun save(profileId: Long, source: File): String? =
        source.inputStream().use { save(profileId, it) }

    /** As [save], for bytes that never were a file — a photo handed over by the phone, say. */
    suspend fun save(profileId: Long, source: InputStream): String? = withContext(Dispatchers.IO) {
        val decoded = runCatching { BitmapFactory.decodeStream(source) }.getOrNull()
            ?: run {
                Log.w(TAG, "profile $profileId: the chosen file is not a readable image")
                return@withContext null
            }
        val square = runCatching { squareThumbnail(decoded) }.getOrElse {
            Log.w(TAG, "profile $profileId: unable to scale the chosen image", it)
            decoded.recycle()
            return@withContext null
        }
        val dest = fileFor(profileId)
        val written = runCatching {
            dir.mkdirs()
            dest.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        }.isSuccess
        if (square !== decoded) square.recycle()
        decoded.recycle()
        if (!written) {
            Log.w(TAG, "profile $profileId: unable to write the picture")
            return@withContext null
        }
        dest.absolutePath
    }

    /** Forget [profileId]'s picture. Safe to call when there is none — the drawn tile returns. */
    suspend fun clear(profileId: Long) = withContext(Dispatchers.IO) {
        runCatching { fileFor(profileId).delete() }
        Unit
    }

    /**
     * The centre square of [source], at most [SIZE_PX] a side.
     *
     * Centre-cropped rather than squashed: a portrait photo stretched into a circle makes a face
     * look wrong in a way nobody can name but everybody sees. Returns [source] itself when it is
     * already a small square, so the caller must check identity before recycling.
     */
    private fun squareThumbnail(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (side <= 0) throw IllegalArgumentException("empty image")
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val target = minOf(side, SIZE_PX)
        if (left == 0 && top == 0 && side == source.width && side == source.height && target == side) return source
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, target, target),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    companion object {
        private const val TAG = "ProfileAvatar"
        private const val DIR = "avatars"

        /** Comfortably above the largest size either app draws a profile picture at. */
        const val SIZE_PX = 512
        private const val QUALITY = 90
    }
}
