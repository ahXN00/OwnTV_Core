package tv.own.owntv.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Filesystem access for downloads & backup. Android TV usually lacks the SAF document-picker, so we
 * use plain [File] access: an app-specific dir works with no permission, and "All files access"
 * (MANAGE_EXTERNAL_STORAGE) unlocks user-chosen folders elsewhere on storage.
 */
object StorageAccess {

    enum class RootKind { INTERNAL, REMOVABLE, APP }

    /** A storage root's kind and path; wording belongs to the Compose file picker. */
    data class StorageRoot(val kind: RootKind, val file: File, val volumeName: String? = null)

    /**
     * Whether shared storage can be browsed: All-files access on Android 11+, or the classic
     * READ_EXTERNAL_STORAGE grant on Android 10 and below. A media-only grant on 11–12L does NOT
     * count — the picker offers exactly one grant path (full access), never a media tier.
     */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One uniform grant route on every device: OwnTV's own App-info settings screen, where the
     * user picks storage access themselves (Permissions → Files and media / Storage →
     * "Allow management of all files"). Deliberately NOT the All-files intent — OEM builds hijack
     * it (TCL Android 12 routes it to "Permission Shield", which has no storage entry) — and NOT
     * a runtime permission dialog, which on 11–12L could only grant a useless media-only tier.
     * READ_EXTERNAL_STORAGE stays declared (maxSdk 32) so "Files and media" is listed there.
     */
    fun openStoragePermissionSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** App-specific external dir — always writable, no permission. Visible under Android/data/<pkg>/files. */
    fun defaultRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "OwnTV").apply { mkdirs() }

    /** The effective base folder: the configured path if usable, else the default. */
    fun resolveRoot(context: Context, configured: String?): File {
        val dir = configured?.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (dir != null && isUsableDir(dir)) dir else defaultRoot(context)
    }

    /**
     * A folder that exists — or can be made — and can be written to. A USB stick that has been pulled
     * leaves its path behind with nothing under it, and that must count as gone, not as a folder.
     */
    internal fun isUsableDir(dir: File): Boolean =
        runCatching { (dir.isDirectory || dir.mkdirs()) && dir.canWrite() }.getOrDefault(false)

    /** Top-level browsable storage roots for the Compose folder picker. */
    fun storageRoots(context: Context): List<StorageRoot> {
        val roots = LinkedHashMap<String, StorageRoot>()
        val internal = Environment.getExternalStorageDirectory()
        if (internal != null && internal.exists()) roots[internal.absolutePath] = StorageRoot(RootKind.INTERNAL, internal)
        // Removable volumes: derive each volume root from its app-specific dir (…/Android/data/pkg/files).
        context.getExternalFilesDirs(null).forEach { f ->
            val vol = f?.parentFile?.parentFile?.parentFile?.parentFile
            if (vol != null && vol.exists() && vol.absolutePath != internal?.absolutePath) {
                roots[vol.absolutePath] = StorageRoot(RootKind.REMOVABLE, vol, vol.name.takeIf { it.isNotBlank() })
            }
        }
        val appRoot = defaultRoot(context)
        roots[appRoot.absolutePath] = StorageRoot(RootKind.APP, appRoot)
        return roots.values.toList()
    }

    /**
     * The volumes this app can write to with no permission at all: its own folder on internal
     * storage, and one on each SD card or USB stick that is mounted.
     *
     * This is what a phone offers instead of [storageRoots]: there is no All-files access to ask for
     * there, so the choice a user makes is *which volume*, not which folder. The first entry is
     * always internal storage and equals [defaultRoot].
     */
    fun appRoots(context: Context): List<StorageRoot> {
        val internal = context.getExternalFilesDir(null)?.absolutePath
        return context.getExternalFilesDirs(null).filterNotNull().map { dir ->
            val removable = dir.absolutePath != internal
            StorageRoot(
                kind = if (removable) RootKind.REMOVABLE else RootKind.INTERNAL,
                file = File(dir, "OwnTV").apply { mkdirs() },
                // …/<volume>/Android/data/<pkg>/files — four levels up is the volume itself.
                volumeName = dir.parentFile?.parentFile?.parentFile?.parentFile?.name
                    ?.takeIf { removable && it.isNotBlank() },
            )
        }
    }

    /**
     * Keep something the user just picked — a download folder, or a file they exported to — across
     * reboots.
     *
     * A URI handed back by the system picker is granted **only to the activity that asked**, and
     * only until the process dies. What it points at outlives both: a transfer runs in a foreground
     * service, a scheduled recording starts days later, and an exported film is meant to be played
     * next week. So the grant has to be made persistable or the file is silently unreachable the
     * next time the app starts.
     *
     * Returns false when the system refuses — which happens when the picker was not asked for a
     * persistable grant in the first place. A caller must then treat the choice as **not made**
     * rather than made and quietly broken tomorrow.
     */
    fun persistAccess(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /**
     * Whether a folder chosen earlier is still ours to write to.
     *
     * The user can withdraw it from system settings, and the volume it lives on can be unmounted, so
     * a stored URI is a claim rather than a fact and is checked before it is relied on. A stored
     * value that is not a document at all — an ordinary path, which is what a television uses — is
     * always "granted": there is no grant involved.
     */
    fun hasTree(context: Context, stored: String?): Boolean {
        if (!MediaTarget.isDocument(stored)) return true
        val value = stored ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.isWritePermission && it.uri.toString() == value
            }
        }.getOrDefault(false)
    }

    /**
     * Give a folder back when it stops being the download folder, so the app does not sit on a pile
     * of grants the user can see in system settings and did not ask to keep.
     */
    fun releaseTree(context: Context, stored: String?) {
        if (!MediaTarget.isDocument(stored)) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(stored),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * The chosen folder as something to show a person: `Films/OwnTV` rather than
     * `content://com.android.externalstorage.documents/tree/primary%3AFilms%2FOwnTV`.
     *
     * A path is returned unchanged, because a path already reads as one.
     */
    fun folderLabel(stored: String?): String? {
        val value = stored?.takeIf { it.isNotBlank() } ?: return null
        if (!MediaTarget.isDocument(value)) return value
        val id = runCatching { java.net.URLDecoder.decode(value.substringAfterLast('/'), "UTF-8") }
            .getOrDefault(value.substringAfterLast('/'))
        return id.substringAfter(':').takeIf { it.isNotBlank() } ?: id
    }

    /** Strips characters that are illegal in file/folder names. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "untitled" }.take(120)

    /** Best-effort file extension from a stream URL (defaults to mp4). */
    fun extOf(url: String): String {
        val ext = url.substringAfterLast('/', "").substringBefore('?').substringAfterLast('.', "")
        return ext.takeIf { it.isNotBlank() && it.length <= 4 } ?: "mp4"
    }
}
