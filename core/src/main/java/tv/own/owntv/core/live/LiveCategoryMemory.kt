package tv.own.owntv.core.live

/**
 * The remembered Live TV category to bring back, given the category list as loaded for this profile
 * and these playlists — or null to leave the screen where it is. Both apps restore through this.
 *
 * Favorites and History are always there. Anything else comes back only while the list holds it and
 * it opens without a PIN: a hidden All or Catch-up, a deleted or re-synced-away folder and a locked
 * folder not opened this session all stay where they are. The stored value itself is never touched,
 * so unhiding or unlocking the category brings it back next time.
 *
 * [stored] is the saved form ([serialize]); [sealed] says which entries need a PIN first.
 */
fun <T> liveRestoreKey(stored: String, items: List<T>, key: (T) -> LiveKey, sealed: (T) -> Boolean): LiveKey? {
    val wanted = parseLiveKey(stored) ?: return null
    if (wanted == LiveKey.Favorites || wanted == LiveKey.History) return wanted
    return wanted.takeIf { items.any { key(it) == wanted && !sealed(it) } }
}
