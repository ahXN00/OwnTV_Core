package tv.own.owntv.core.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only bridge allowing the launcher to open only generated logo art: a 64-hex-character PNG name
 * directly inside [LiveLogoPosterArt.DIRECTORY], nothing else. Ships disabled; [LiveLogoPosterArt]
 * enables it when it writes its first file.
 */
class LiveLogoArtProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException(uri.toString())
        val segments = uri.pathSegments
        val name = segments.singleOrNull().orEmpty()
        if (name.length != 68 || !name.endsWith(".png") || !name.dropLast(4).all { it in '0'..'9' || it in 'a'..'f' }) {
            throw FileNotFoundException(uri.toString())
        }
        val file = File(File(requireNotNull(context).cacheDir, LiveLogoPosterArt.DIRECTORY), name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
