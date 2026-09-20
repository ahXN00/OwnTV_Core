package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import tv.own.owntv.core.database.entity.UserDataTombstoneEntity

/**
 * Deleted-user-data markers (v36) — see [UserDataTombstoneEntity]. Written when the user removes a
 * favorite, a history entry, a resume position or a custom-category membership, read by local sync
 * so the deletion travels to the other device instead of being undone by it.
 */
@Dao
interface TombstoneDao {

    /**
     * Records a deletion, keeping the LATER moment when one is already there. `INSERT OR REPLACE`
     * would drop back to an older timestamp if two devices exchanged tombstones out of order, and an
     * older tombstone loses to a re-add that happened in between — so the newest wins here too.
     */
    @Query(
        "INSERT INTO user_data_tombstones (profileId, kind, identity, deletedAt) VALUES (:profileId, :kind, :identity, :deletedAt) " +
            "ON CONFLICT(profileId, kind, identity) DO UPDATE SET deletedAt = MAX(deletedAt, :deletedAt)",
    )
    suspend fun record(profileId: Long, kind: String, identity: String, deletedAt: Long)

    /** When this row was deleted, or null if it never was. Gates a merge insert of the same record. */
    @Query("SELECT deletedAt FROM user_data_tombstones WHERE profileId = :profileId AND kind = :kind AND identity = :identity")
    suspend fun deletedAt(profileId: Long, kind: String, identity: String): Long?

    /** Everything, for the sync payload. */
    @Query("SELECT * FROM user_data_tombstones")
    suspend fun getAllOnce(): List<UserDataTombstoneEntity>

    @Query("SELECT COUNT(*) FROM user_data_tombstones")
    suspend fun count(): Int

    /**
     * Forgets this device's deletions for [profileIds] — what a *restore* does before applying a
     * file, and only a restore.
     *
     * A restore says "my data is what this file says", so a deletion made after the file was written
     * is no longer true. Left in place these markers silently refuse the very rows the user asked to
     * bring back ([deletedAt] gates the insert), the restore reports success anyway, and the next
     * local sync would delete them a second time. A merge must NOT call this: there the deletion is a
     * fact the other device still has to hear about.
     */
    @Query("DELETE FROM user_data_tombstones WHERE profileId IN (:profileIds)")
    suspend fun deleteForProfiles(profileIds: List<Long>)

    /**
     * Drops all but the [keep] newest. A tombstone is only useful until every device has seen it, and
     * "Clear watch history" on a big library writes one per row — without a cap the table would grow
     * for ever to remember deletions nothing will ever ask about again.
     */
    @Query(
        "DELETE FROM user_data_tombstones WHERE id NOT IN " +
            "(SELECT id FROM user_data_tombstones ORDER BY deletedAt DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)
}
