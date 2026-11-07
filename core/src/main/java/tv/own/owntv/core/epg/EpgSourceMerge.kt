package tv.own.owntv.core.epg

/** A backup's EPG sources merged into the device's: the list to store, the next free id, and backup id to device id. */
internal class EpgSourceMerge(val sources: List<EpgSource>, val nextId: Long, val idMap: Map<Long, Long>)

/**
 * Adds [incoming] to [existing] without dropping the device's own entries: an incoming source whose
 * URL already exists maps to the existing entry, and the rest are appended in the backup's order with
 * ids counting down from [nextId].
 *
 * The list's order is the guide priority, so the feeds the backup and the device share take the
 * backup's order among themselves, in the places they already hold. A feed only the device has keeps
 * its place; restoring onto a fresh install simply reproduces the backup's list.
 */
internal fun mergeEpgSources(existing: List<EpgSource>, incoming: List<EpgSource>, nextId: Long): EpgSourceMerge {
    val merged = existing.toMutableList()
    val existingIds = existing.mapTo(HashSet()) { it.id }
    var next = nextId
    val idMap = HashMap<Long, Long>()
    incoming.forEach { src ->
        val match = merged.firstOrNull { it.url.trim() == src.url.trim() }
        if (match != null) {
            // Same feed already here: adopt the backup's name and user agent. Matching is by
            // URL alone, so keeping the device's copy meant a restore silently discarded a
            // renamed feed and any custom user agent it needed to fetch at all. Sync state
            // (lastSyncAt/lastError) stays local — it describes THIS device.
            merged[merged.indexOf(match)] =
                match.copy(name = src.name, userAgent = src.userAgent ?: match.userAgent)
            idMap[src.id] = match.id
        } else {
            val added = src.copy(id = next, lastSyncAt = null, lastError = null)
            next -= 1
            merged += added
            idMap[src.id] = added.id
        }
    }
    val shared = incoming.mapNotNull { idMap[it.id] }.filter { it in existingIds }.distinct()
    return EpgSourceMerge(inOrder(merged, shared), next, idMap)
}

/** [list] with the entries named by [ids] rearranged into that order, within the slots they occupy. */
private fun inOrder(list: List<EpgSource>, ids: List<Long>): List<EpgSource> {
    val wanted = ids.toHashSet()
    val byId = list.associateBy { it.id }
    val reordered = ids.iterator()
    return list.map { if (it.id in wanted) byId.getValue(reordered.next()) else it }
}
