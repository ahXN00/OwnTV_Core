package tv.own.owntv.core.epg

/**
 * Drops the second copy of a programme inside one feed, while it is being read.
 *
 * Feeds list some channels twice under ids that differ only by case ("SkyCinemaClassics.de" and
 * "skycinemaclassics.de"), with the same films a minute or two apart. Ids are stored lowercased, so
 * both copies landed on one guide row. They used to be written and then collapsed after the sync —
 * and because the collapsed copy was missing next time, it was written again, every sync, for ever.
 *
 * Same test as [EpgDedupe]: same title and overlapping time. The first copy the feed lists wins, so
 * the kept row is the same on every sync and nothing churns.
 */
class FeedDedupe {
    private class Slot(val startMs: Long, val stopMs: Long, val title: String)

    private val byChannel = HashMap<String, ArrayList<Slot>>()

    /** True when this programme is new for [channelKey]; false for a copy of one already accepted. */
    fun accept(channelKey: String, startMs: Long, stopMs: Long, title: String): Boolean {
        val slots = byChannel.getOrPut(channelKey) { ArrayList(32) }
        if (slots.any { it.title == title && it.startMs < stopMs && startMs < it.stopMs }) return false
        slots.add(Slot(startMs, stopMs, title))
        return true
    }
}
