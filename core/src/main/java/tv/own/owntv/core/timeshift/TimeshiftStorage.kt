package tv.own.owntv.core.timeshift

import android.content.Context
import java.io.File

/**
 * Where timeshift buffers live: internal storage only (decision 25 — no USB, it is temporary). The
 * no-backup folder rather than the cache, because the system may clear a cache under storage pressure
 * while a buffer is being watched; the 1 GB floor is what protects the device instead.
 */
object TimeshiftStorage {

    fun root(context: Context): File = File(context.noBackupFilesDir, DIR)

    /** Everything left from a previous run — a crash, a kill, a restart. Called once at process start. */
    fun clearAll(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }

    private const val DIR = "timeshift"
}
