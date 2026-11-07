package tv.own.owntv.core.sync

import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelNumberProjection

/**
 * Channel numbers for an M3U playlist that carries none.
 *
 * A playlist without a single `tvg-chno` used to leave every channel unnumbered, so the Live list,
 * the guide and direct tune had nothing to show or dial. Such a playlist now numbers its channels
 * 1, 2, 3… in file order, as other IPTV players do. The decision is per playlist and all-or-nothing:
 * one `tvg-chno` anywhere and only the provider's numbers are used, so the two are never mixed.
 *
 * Numbers follow the playlist's own order (`sortOrder`), never a view of it: favourites, a manual
 * reorder and hidden channels change what is shown, not a channel's number. A resync of an
 * unchanged file gives the same numbers; a channel added mid-file moves the ones after it, exactly as
 * it moves them in the file.
 *
 * Runs after the sync has written and pruned the rows. Like `sortOrder`, a derived number is kept out
 * of the content hash: the upsert writes each row with the provider's (absent) number, so a stored
 * row still hashes equal on the next resync, and this pass then writes only the rows whose number
 * actually changes. An unchanged playlist resyncs without a single number write.
 */
internal object PlaylistNumbering {

    data class Change(val id: Long, val number: Int?)

    /**
     * The number writes for one playlist. [rows] is every stored channel of the source in playlist
     * order; [providerNumbered] is whether this parse saw any `tvg-chno`; [unnumberedKeys] are the
     * keys of the channels it saw without one.
     */
    fun changes(
        rows: List<ChannelNumberProjection>,
        providerNumbered: Boolean,
        unnumberedKeys: Set<String>,
    ): List<Change> =
        if (!providerNumbered) {
            rows.mapIndexedNotNull { i, row -> Change(row.id, i + 1).takeIf { row.number != it.number } }
        } else {
            // The playlist numbers its channels now: clear what was derived when it did not.
            rows.mapNotNull { row ->
                val derivedLeftOver = row.number != null && row.remoteId?.let { it in unnumberedKeys } == true
                Change(row.id, null).takeIf { derivedLeftOver }
            }
        }

    /** Applies [changes] to source [sourceId]; returns how many channels were renumbered. */
    suspend fun apply(channelDao: ChannelDao, sourceId: Long, providerNumbered: Boolean, unnumberedKeys: Set<String>): Int {
        val writes = changes(channelDao.numbersForSource(sourceId), providerNumbered, unnumberedKeys)
        if (writes.isNotEmpty()) channelDao.applyNumbers(writes.map { it.id }, writes.map { it.number })
        return writes.size
    }
}
