package tv.own.owntv.core.sync.local

import tv.own.owntv.core.backup.BackupManager

/**
 * What this device does by itself with **one** paired device, and with what of its own.
 *
 * One record per pairing, held in [LocalAutoSyncSettings]. The questions are the same ones a backup
 * asks — which sections, whose profiles — plus the one a backup does not have: how often.
 *
 * [timing] is the switch: [LocalAutoSyncTiming.OFF] means nothing happens with this device, and
 * anything else means it happens on that schedule. There is no separate "on" flag, because a flag
 * and a mode that can say "off" are two places for the same answer to live, and they would
 * eventually disagree.
 *
 * [sections] are the parts of the data that travel, and they are the very same parts a backup
 * holds — so "sync my playlists" means the same thing here as it does in Backup & Restore, and the
 * file that travels is a backup container either way.
 *
 * [profileIds] narrows the pass to the viewers the user named — the same ids the export flow's
 * profile picker uses, because it is the same question a backup asks. The scoping is on the export,
 * exactly as a manual backup's is: it decides what leaves this device, while what arrives is
 * decided by the other device's own answer. Empty means every profile of this device.
 *
 * Per device rather than one setting for the household, because "keep the television current but
 * leave the tablet alone" is a thing a household wants, and a list of exceptions to one global
 * answer is one more place for the two to disagree.
 */
data class LocalAutoSyncConfig(
    val timing: LocalAutoSyncTiming = LocalAutoSyncTiming.DEFAULT,
    val sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
    /** Empty = every profile on this device. */
    val profileIds: Set<Long> = emptySet(),
    val direction: SyncDirection = SyncDirection.MERGE,
    /** When the last automatic pass with this device started, whatever came of it. */
    val lastRunAt: Long = 0,
    /** When the last automatic pass with this device actually moved something. */
    val lastSuccessAt: Long = 0,
) {
    /** Off is a timing like any other, and this is what the switch on the screen shows. */
    val enabled: Boolean
        get() = timing != LocalAutoSyncTiming.OFF
}
