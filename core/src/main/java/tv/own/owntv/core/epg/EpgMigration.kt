package tv.own.owntv.core.epg

import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.EpgRepository

/**
 * One-time migration when EPG moved out of playlists into standalone EPG sources (v2.2.0): every
 * existing playlist that had a guide URL (Xtream `xmltv.php` or a stored M3U `url-tvg`/manual link)
 * becomes an EPG source and is synced once — so users who already had a guide don't lose it.
 */
class EpgMigration(
    private val store: EpgSourceStore,
    private val sourceDao: SourceDao,
    private val epgRepository: EpgRepository,
) {
    suspend fun run() {
        if (store.isMigrated()) return
        runCatching {
            val existingUrls = store.getAll().map { it.url }.toMutableSet()
            for (src in sourceDao.getAllOnce()) {
                // A playlist header may name several feeds in one comma-separated url-tvg; each
                // becomes its own EPG source, because only a single address can be downloaded.
                for (url in epgRepository.guideUrls(src)) {
                    if (url in existingUrls) continue
                    existingUrls += url
                    // Keep the provider/source name as the persisted label; translated suffixes would
                    // freeze the migration language and become stale on a later locale switch. Two feeds
                    // of one playlist share the name and are told apart by the URL shown beneath it.
                    val epg = store.add(src.name, url, src.userAgent)
                    val now = System.currentTimeMillis()
                    runCatching { epgRepository.refreshUrl(epg.id, epg.url, epg.userAgent) }
                        .onSuccess { store.setSynced(epg.id, now, null) }
                        .onFailure { store.setSynced(epg.id, now, it.message) }
                }
            }
        }
        store.markMigrated() // mark regardless, so a transient failure doesn't re-run forever
    }
}
