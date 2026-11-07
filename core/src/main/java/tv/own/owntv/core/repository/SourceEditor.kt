package tv.own.owntv.core.repository

import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * Saves an edited playlist and decides whether it must be fetched again — the one edit path both
 * apps use, so the two cannot disagree about when an edit takes effect.
 */
class SourceEditor(
    private val sourceDao: SourceDao,
    private val stalkerAuth: StalkerAuthManager,
    private val catalogSync: CatalogSyncScheduler,
    private val importFinalizer: ImportFinalizer,
) {
    /**
     * Stores [edited] over [existing]. A Stalker edit drops the cached portal session, so the next
     * call handshakes again instead of using a token issued for the old MAC or portal. A section
     * switched off cancels the running sync; Off never deletes the cached catalogue.
     */
    suspend fun save(existing: SourceEntity, edited: SourceEntity) {
        if (existing.type == SourceType.STALKER) stalkerAuth.invalidate(existing.id)
        sourceDao.update(edited)
        if (scopeChanged(existing, edited)) catalogSync.cancelSync(existing.id)
        if (needsResync(existing, edited)) {
            val counts = importFinalizer.contentCounts(existing.id)
            catalogSync.enqueueSync(
                existing.id,
                reason = "playlist_edit",
                contentTypes = SyncContentTypes.enabledFor(edited),
                baseItemCount = counts.channels + counts.movies + counts.series,
            )
        }
    }

    internal companion object {
        fun scopeChanged(a: SourceEntity, b: SourceEntity): Boolean =
            a.syncLive != b.syncLive || a.syncMovies != b.syncMovies || a.syncSeries != b.syncSeries

        /**
         * Fetch again when a section was switched on, when the playlist has never completed a sync,
         * or when anything that decides *what the provider sends* changed. The last is what makes a
         * corrected password take effect: a pass that ran with the wrong one may already be stamped
         * synced, so neither of the first two rules would ever fire.
         */
        fun needsResync(existing: SourceEntity, edited: SourceEntity): Boolean {
            val scopeTurnedOn = (!existing.syncLive && edited.syncLive) ||
                (!existing.syncMovies && edited.syncMovies) ||
                (!existing.syncSeries && edited.syncSeries)
            return scopeTurnedOn || edited.lastSyncAt == null || accountChanged(existing, edited)
        }

        private fun accountChanged(a: SourceEntity, b: SourceEntity): Boolean =
            a.url != b.url || a.username != b.username || a.password != b.password ||
                a.mac != b.mac || a.stalkerSerialNumber != b.stalkerSerialNumber ||
                a.stalkerDeviceId != b.stalkerDeviceId || a.stalkerDeviceId2 != b.stalkerDeviceId2 ||
                a.stalkerSignature != b.stalkerSignature
    }
}
