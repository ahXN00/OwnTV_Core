package tv.own.owntv.core.sync

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.io.InputStream

/** A playlist picked from the device — an absolute path, a `file://` or a `content://` address — rather than downloaded. */
internal class LocalPlaylistFile(private val context: Context) {
    /**
     * Size of a local playlist, for the progress log — read without opening the stream, so the
     * descriptor is only ever held around the parse itself. Null when it can't be determined.
     */
    fun size(url: String): Long? = when {
        url.startsWith("/") -> File(url).length().takeIf { it > 0 }
        url.startsWith("file://") -> url.toUri().path?.let { File(it).length().takeIf { len -> len > 0 } }
        url.startsWith("content://") -> runCatching {
            context.contentResolver.openAssetFileDescriptor(url.toUri(), "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        }.getOrNull()
        else -> null
    }

    /** Opens a local playlist for reading. The caller MUST close it — always via `use { }`:
     *  before, an exception between opening and parsing leaked the fd (and, for `content://`, the
     *  provider's file handle) for the life of the process. */
    fun open(url: String): InputStream = when {
        url.startsWith("/") -> File(url).inputStream()
        url.startsWith("file://") -> {
            val uri = url.toUri()
            File(uri.path ?: throw java.io.IOException("playlist_file_unavailable")).inputStream()
        }
        url.startsWith("content://") ->
            context.contentResolver.openInputStream(url.toUri())
                ?: throw java.io.IOException("playlist_file_unavailable")
        else -> throw java.io.IOException("playlist_path_unsupported")
    }
}
