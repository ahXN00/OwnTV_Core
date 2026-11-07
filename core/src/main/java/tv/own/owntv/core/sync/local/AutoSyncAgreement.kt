package tv.own.owntv.core.sync.local

import tv.own.owntv.core.backup.BackupManager

/**
 * This device's agreement to sync by itself with one paired device: one per device, on each side.
 *
 * Each side keeps — and enforces — its own [sections]: what this device sends out and what it lets in.
 * [theirSections] is the other side's answer as it last said it, for the row to show; it changes
 * nothing here. The agreement pairs one profile here ([localProfileId]) with the one in use on the other
 * device when it was set up ([theirProfileName], for the row): their data is merged as one viewer's,
 * whatever the names, and switching profile later does not move the pair.
 *
 * [lastSuccessAt] moves only when a pass completed; a failed or unreachable try moves [lastAttemptAt]
 * and [failures] only, so the next try backs off without pretending anything was synced.
 */
data class AutoSyncAgreement(
    val deviceId: String,
    val sections: Set<BackupManager.Section> = DEFAULT_SECTIONS,
    val localProfileId: Long = -1L,
    val theirProfileName: String = "",
    val timing: LocalAutoSyncTiming = LocalAutoSyncTiming.DEFAULT,
    val theirSections: Set<BackupManager.Section> = emptySet(),
    val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val failures: Int = 0,
) {
    /**
     * The earliest moment the next pass may start. With no failure, [timing]'s gap after the last
     * success; after failures, also not before the backoff after the last try — 15 minutes doubled
     * per failure, never more than the gap itself (and never more than a day).
     */
    fun dueAt(): Long {
        // Never synced: the first chance is the first pass, whatever the timing.
        val afterSuccess = if (lastSuccessAt == 0L) 0L else lastSuccessAt + timing.gapMs
        if (failures <= 0) return afterSuccess
        return maxOf(afterSuccess, lastAttemptAt + backoffMs(failures, timing.gapMs))
    }

    fun isDue(now: Long): Boolean = dueAt() <= now

    /** After one try: a success clears the failures, anything else counts one more. */
    fun afterAttempt(at: Long, success: Boolean): AutoSyncAgreement =
        if (success) copy(lastAttemptAt = at, lastSuccessAt = at, failures = 0)
        else copy(lastAttemptAt = at, failures = failures + 1)

    companion object {
        /** On by default: the viewer's own things. Playlists (with logins) and Settings are ticked by hand. */
        val DEFAULT_SECTIONS: Set<BackupManager.Section> = setOf(
            BackupManager.Section.FAVORITES,
            BackupManager.Section.HISTORY,
            BackupManager.Section.RESUME,
            BackupManager.Section.CUSTOMIZE,
            BackupManager.Section.MANUAL_REORDER,
        )

        private const val BACKOFF_STEP_MS = 15 * 60_000L
        private const val BACKOFF_CAP_MS = 24 * 3600_000L

        /** 15 minutes doubled per failure in a row, capped by [gapMs] (when it has one) and by a day. */
        fun backoffMs(failures: Int, gapMs: Long): Long {
            val doubled = BACKOFF_STEP_MS shl (failures - 1).coerceIn(0, 10)
            val ceiling = if (gapMs > 0) minOf(gapMs, BACKOFF_CAP_MS) else BACKOFF_CAP_MS
            return minOf(doubled, ceiling).coerceAtLeast(BACKOFF_STEP_MS)
        }

        /** What one side lets in from a file: its own sections, and only those the file carries. */
        fun allowedIn(mine: Set<BackupManager.Section>, inFile: Set<BackupManager.Section>): Set<BackupManager.Section> =
            mine intersect inFile
    }
}
