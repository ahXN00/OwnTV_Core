package tv.own.owntv.core.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * The folder downloads and recordings are written into — the other half of [MediaTarget].
 *
 * Where [MediaTarget] is one file, this is the place new ones are made, and it comes in the same two
 * shapes for the same reason: a real directory on a volume the app may write to, or a Storage Access
 * Framework tree the user granted from the system folder picker.
 *
 * The `downloadRoot` preference is a `String`, so a tree is stored in it as its `content://` URI and
 * nothing else had to change to hold one. [of] is the single place that decides which shape a stored
 * value is.
 */
sealed interface MediaRoot {

    /** Exactly what the `downloadRoot` preference holds, and what [of] turns back into this object. */
    val stored: String

    /**
     * Make — or find, if a previous download already made it — the file at
     * `<root>/<relativeDir>/<fileName>`, e.g. `Movies/Interstellar.mp4`.
     *
     * Null when the destination cannot be created at all — the volume went away between [of] choosing
     * this root and the file being made. The caller turns that into a failure the user can read.
     */
    fun child(relativeDir: String, fileName: String): MediaTarget?

    /** Free and total bytes of whatever backs this root, for the Downloads storage bar. */
    fun space(): Space

    /** Create `TV/`, `Movies/` and `Series/` if they are not already there. Best-effort and silent. */
    fun ensureFolders()

    /** What the storage bar shows. */
    data class Space(val freeBytes: Long, val totalBytes: Long)

    /** An ordinary directory on a mounted volume. */
    class Path(val dir: File) : MediaRoot {
        override val stored: String get() = dir.absolutePath

        override fun child(relativeDir: String, fileName: String): MediaTarget? {
            val parent = if (relativeDir.isBlank()) dir else File(dir, relativeDir)
            if (!parent.exists() && !runCatching { parent.mkdirs() }.getOrDefault(false)) return null
            return MediaTarget.Path(File(parent, fileName))
        }

        override fun space(): Space = Space(dir.usableSpace, dir.totalSpace)

        override fun ensureFolders() = MediaFolders.ensureIn(dir)
    }

    /** A SAF tree the user picked. */
    class Tree(private val context: Context, val treeUri: Uri) : MediaRoot {
        override val stored: String get() = treeUri.toString()

        private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

        /** The grant still stands and the folder behind it is there — its volume is mounted. */
        fun isAvailable(): Boolean = StorageAccess.hasTree(context, stored) &&
            runCatching { root()?.let { it.exists() && it.canWrite() } == true }.getOrDefault(false)

        override fun child(relativeDir: String, fileName: String): MediaTarget? {
            var dir = root() ?: return null
            // `Series/The Wire/Season 3` is three directories, each found or made in turn. A name
            // that already exists as a FILE is not reused as a folder — that would append a season
            // of episodes into somebody's video.
            for (segment in relativeDir.split('/').filter { it.isNotBlank() }) {
                dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(segment)
                    ?: return null
            }
            // A part-finished download is resumed, not started beside itself as `film (1).mp4` —
            // which is what createFile would produce, because SAF de-duplicates names rather than
            // overwriting. Only a name that is genuinely absent is created.
            val existing = dir.findFile(fileName)?.takeIf { it.isFile }
            val doc = existing ?: dir.createFile(mimeOf(fileName), fileName) ?: return null
            return MediaTarget.Document(context, doc.uri)
        }

        /**
         * SAF does not report free space, so this resolves the tree back to the volume behind it and
         * measures that. It works for the system's own `externalstorage` provider — the one every
         * folder on internal storage or an SD card comes from — whose document ids are
         * `primary:Some/Folder` or `<volume-uuid>:Some/Folder`.
         *
         * A tree from anywhere else (a cloud provider) has no volume to measure, and rather than
         * invent a number the bar falls back to the app's own storage. That is the volume the other
         * entries in the picker are on, so it is at worst the wrong volume rather than a fiction.
         */
        override fun space(): Space {
            val dir = volumeDir() ?: StorageAccess.defaultRoot(context)
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                ?: return Space(dir.usableSpace, dir.totalSpace)
            return Space(stat.availableBytes, stat.totalBytes)
        }

        private fun volumeDir(): File? = runCatching {
            val id = DocumentVolumes.volumeIdOf(DocumentsContract.getTreeDocumentId(treeUri))
            DocumentVolumes.dirOf(context, id)
        }.getOrNull()

        override fun ensureFolders() {
            val dir = root() ?: return
            listOf(MediaFolders.TV, MediaFolders.MOVIES, MediaFolders.SERIES).forEach { name ->
                runCatching {
                    if (dir.findFile(name)?.isDirectory != true) dir.createDirectory(name)
                }
            }
        }
    }

    companion object {
        /**
         * The download root as configured, or the app's own folder when the setting is unset or
         * names somewhere that is not there right now — a USB stick or card that has been removed,
         * or a folder whose permission was withdrawn.
         *
         * The chosen folder always comes first and the setting is never changed: the moment the
         * stick is back, the next download goes to it again. Only while it is missing do downloads
         * and recordings fall back, rather than failing one after another (see [isFallback]).
         */
        fun of(context: Context, configured: String?): MediaRoot {
            val value = configured?.takeIf { it.isNotBlank() }
            if (MediaTarget.isDocument(value)) {
                val tree = Tree(context, Uri.parse(value))
                return if (tree.isAvailable()) tree else Path(StorageAccess.defaultRoot(context))
            }
            return Path(StorageAccess.resolveRoot(context, value))
        }

        /** True when a folder was chosen but [of] is using the app's own folder because it is missing. */
        fun isFallback(context: Context, configured: String?): Boolean {
            val value = configured?.takeIf { it.isNotBlank() } ?: return false
            if (MediaTarget.isDocument(value)) return !Tree(context, Uri.parse(value)).isAvailable()
            return !StorageAccess.isUsableDir(java.io.File(value))
        }

        /** Best-effort MIME from the file's extension — SAF wants one to create a document. */
        internal fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "video/*"
        }
    }
}
