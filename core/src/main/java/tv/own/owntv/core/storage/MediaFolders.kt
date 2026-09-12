package tv.own.owntv.core.storage

import java.io.File

/**
 * The three folders OwnTV keeps inside the storage root the user chose (D1).
 *
 * They existed before this class did — as the string `"Movies"` at two call sites in the television
 * and two more in the phone, and as `"Series/<show>/Season N"` built by hand in both. That is exactly
 * the shape a drift takes: rename one and a user's library quietly splits in two. **Core owns the
 * names now**, both apps ask for them, and recordings get `TV/` alongside them rather than a fourth
 * private convention.
 *
 * The names are deliberately **not** translated. They are folder names on the user's disk, shared
 * between two apps and read by file managers and media players that know nothing about locales; a
 * library that renamed itself when the phone's language changed would be a library in two halves.
 */
object MediaFolders {

    /** Live recordings (Feature A). */
    const val TV = "TV"

    /** Downloaded films. The name the television has written since downloads existed. */
    const val MOVIES = "Movies"

    /** Downloaded episodes, under `Series/<show>/Season N`. */
    const val SERIES = "Series"

    /**
     * Where one show's season goes, relative to the root: `Series/<show>/Season N`.
     *
     * [showName] is sanitised here rather than by the caller, because a show called `Marvel's What
     * If…?` is a perfectly ordinary title and a perfectly impossible directory name.
     */
    fun seasonDir(showName: String, seasonNumber: Int): String =
        "$SERIES/${StorageAccess.sanitize(showName)}/$SEASON_PREFIX $seasonNumber"

    /**
     * Create all three inside [root] if they are not there.
     *
     * Best-effort and silent: a folder that cannot be created is a storage problem the download or
     * recording itself will report in words, and creating folders is not the moment to raise it.
     */
    fun ensureIn(root: File) {
        listOf(TV, MOVIES, SERIES).forEach { name ->
            runCatching { File(root, name).mkdirs() }
        }
    }

    /**
     * `Season 3` — the folder inside a show's own. Left as its own constant because the downloads
     * screen reads these paths back to show a file's location.
     */
    const val SEASON_PREFIX = "Season"
}
