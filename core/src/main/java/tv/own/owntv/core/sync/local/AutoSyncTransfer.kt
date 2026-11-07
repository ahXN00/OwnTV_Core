package tv.own.owntv.core.sync.local

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.profile.ProfileProtection

/**
 * Builds and applies the containers of an automatic sync, the same way on both ends of a pass.
 *
 * What leaves this device is its own agreement's sections and profiles — protected profiles only when
 * they are the one in use ([ProfileProtection]). What comes in is merged
 * ([BackupManager.ImportMode.MERGE], so deletions made here still stand), limited to this side's
 * sections and to what the file carries, never another device's hardware settings, and never over a
 * protected profile that is not in use here.
 */
class AutoSyncTransfer(
    private val backups: BackupManager,
    private val protection: ProfileProtection,
    private val cacheDir: () -> File,
) {
    /** This device's export for [agreement], sealed with the pairing [secret]. Delete it after use. */
    suspend fun exportFor(agreement: AutoSyncAgreement, secret: String): File {
        val folder = File(cacheDir(), "auto-sync/${UUID.randomUUID()}").apply { mkdirs() }
        val shareable = protection.includableProfileIds()
        val profiles = if (agreement.profileIds.isEmpty()) shareable else shareable intersect agreement.profileIds
        val path = backups.export(folder, agreement.sections, secret, profiles, recordAsBackup = false).getOrThrow()
        return File(path)
    }

    /** Merges [file] into this device as [agreement] allows; the number of rows that landed. */
    suspend fun importFrom(agreement: AutoSyncAgreement, file: File, secret: String): Int {
        val inFile = backups.sectionsIn(file, secret).getOrThrow().sections
        val allowed = AutoSyncAgreement.allowedIn(agreement.sections, inFile)
        if (allowed.isEmpty()) return 0
        return backups.import(
            file, allowed, secret, BackupManager.ImportMode.MERGE,
            deviceSettings = false,
            holdBack = protection.heldBack(),
        ).getOrThrow().items
    }

    /** A place for a container on its way in. Delete it after use. */
    fun incomingFile(): File = File(cacheDir(), "auto-sync").apply { mkdirs() }.let { File(it, "${UUID.randomUUID()}.own") }
}

/**
 * One pass with a paired device at a time, on this device — whichever side started it. A second one
 * (this device's runner, or the other device's request) finds it taken and is answered "busy".
 */
class AutoSyncLocks {
    private val held = ConcurrentHashMap.newKeySet<String>()

    fun tryLock(deviceId: String): Boolean = held.add(deviceId)

    fun unlock(deviceId: String) {
        held.remove(deviceId)
    }
}
