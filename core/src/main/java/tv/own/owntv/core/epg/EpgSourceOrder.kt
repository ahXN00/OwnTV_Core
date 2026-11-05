package tv.own.owntv.core.epg

/**
 * [sources] with the one whose id is [id] moved one place up (`up = true`) or down. Null when it is
 * not in the list or is already at that end, so the caller writes nothing.
 */
internal fun movedEpgSource(sources: List<EpgSource>, id: Long, up: Boolean): List<EpgSource>? {
    val from = sources.indexOfFirst { it.id == id }
    if (from < 0) return null
    val to = if (up) from - 1 else from + 1
    if (to !in sources.indices) return null
    return sources.toMutableList().apply { add(to, removeAt(from)) }
}
