package tv.own.owntv.player

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri

/**
 * Lets mpv play a file it has no path to.
 *
 * A download or a recording saved into a folder the user picked through the system picker is a
 * Storage Access Framework document, and a document is a `content://` URI rather than a path.
 * ExoPlayer needs nothing for this — its `DefaultDataSource` already routes `content://` to
 * `ContentDataSource` — but **mpv opens by path**, and there is no path to give it.
 *
 * What mpv does understand is `fd://<n>`: an already-open file descriptor. So the URI is opened
 * here, and mpv is handed the number.
 *
 * **The descriptor has to outlive the call.** mpv does not duplicate it — it reads from the number
 * for as long as the file is playing — so closing it after `loadfile` returns would end playback
 * somewhere unpredictable a moment later. It is therefore held until the *next* stream replaces it
 * or the player is released, which is the only lifetime that is always long enough and still
 * bounded. At most one is open at a time.
 */
internal class ContentFds(private val context: Context) {

    private var open: ParcelFileDescriptor? = null

    /**
     * What mpv should be asked to load: [url] untouched for anything with a path or a network
     * address, `fd://<n>` for a document.
     *
     * A document that cannot be opened returns the original URI rather than throwing. mpv then fails
     * it through the ordinary error path — with a message, a retry ladder and a card the user can
     * read — which is a far better outcome than an exception escaping the load.
     */
    fun playable(url: String): String {
        if (!isDocument(url)) {
            // Only a document can leave a descriptor behind, so anything else means the previous one
            // is finished with.
            closeOpen()
            return url
        }
        val fd = runCatching {
            context.contentResolver.openFileDescriptor(url.toUri(), READ_MODE)
        }.getOrNull()
        if (fd == null) {
            android.util.Log.w(TAG, "cannot open a descriptor for a document — letting mpv fail it")
            return url
        }
        closeOpen()
        open = fd
        return FD_SCHEME + fd.fd
    }

    /** Let go of whatever is held. Called when the player is torn down. */
    fun release() = closeOpen()

    private fun closeOpen() {
        runCatching { open?.close() }
        open = null
    }

    private fun isDocument(url: String): Boolean = url.startsWith(CONTENT_SCHEME, ignoreCase = true)

    private companion object {
        const val TAG = "ContentFds"
        const val CONTENT_SCHEME = "content://"
        const val FD_SCHEME = "fd://"
        const val READ_MODE = "r"
    }
}
