package tv.own.owntv.core.customize

/**
 * Items the user moved by hand keep the place they were put in, whatever the sort chip says: [rest] is
 * everything never moved, already in the chosen order (A–Z or the playlist's), and each pinned item goes
 * back in at its saved position. A position past the end puts the item last.
 *
 * The database queries for channels, films and series place moved rows the same way, so a list built in
 * memory and a paged list agree.
 */
fun <T> placePinned(rest: List<T>, pinned: List<Pair<T, Int>>): List<T> {
    if (pinned.isEmpty()) return rest
    val out = rest.toMutableList()
    pinned.sortedBy { it.second }.forEach { (item, pos) -> out.add(pos.coerceIn(0, out.size), item) }
    return out
}

/**
 * The saved positions after a Move: the items that were already pinned and the ones carried in this move,
 * each at its place in [order] (the list as the move left it). Items only pushed aside by the move stay
 * unpinned, so they keep following the sort.
 */
fun <K> pinnedAfterMove(order: List<K>, pinnedBefore: Set<K>, carried: Set<K>): Map<K, Int> =
    order.withIndex().filter { (_, k) -> k in pinnedBefore || k in carried }.associate { (i, k) -> k to i }
