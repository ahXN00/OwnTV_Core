package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.BackupKeys
import tv.own.owntv.core.settings.SettingsRepository.Keys

/**
 * Every stored setting has a backup decision. A new key that is in neither a backup list nor
 * [notInSettingsBackup] fails here, so a user's choice cannot silently go missing from backups again
 * (Multiview and the recording settings did).
 */
class SettingsBackupCoverageTest {

    /** Keys the settings whitelist deliberately leaves out, each with its reason. */
    private val notInSettingsBackup = setOf(
        // Internal state and one-shot migration flags.
        "avatar_id", "active_profile_id", "default_source_id", "refresh_migration_done", "epg_refill_checked",
        "restore_in_progress", "live_latency_reset_416", "auto_frame_rate_reset_416",
        "auto_frame_rate_reset_pre12", "metadata_match_heal_version",
        "last_backup_at", "last_backup_bytes", "last_backup_encrypted", "last_backup_path",
        // A Room channel id — meaningless after the next sync.
        "last_live_channel",
        // Backed up in their own BackupManager blocks, remapped to this device's playlists / EPG sources.
        "playlist_auto_refresh", "epg_auto_refresh", "epg_use_logos", "refresh_source_ids",
        // Exported only in their portable forms (backupLastCategoryKeys).
        "last_live_category", "last_movies_category", "last_series_category",
        // Secrets: sealed into the encrypted section, or never written in plain text.
        "proxy_pass", "tmdb_api_key", "open_subtitles_api_key",
    )

    private fun allKeys(): List<Preferences.Key<*>> =
        Keys::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(Keys) as Preferences.Key<*> }

    private val backedUp: Set<String>
        get() = (BackupKeys.strings + BackupKeys.stringSets + BackupKeys.ints + BackupKeys.bools + BackupKeys.floats)
            .map { it.name }.toSet()

    @Test
    fun `every key is backed up or excluded on purpose`() {
        val keys = allKeys()
        assertTrue("reflection found no keys", keys.size > 100)
        val missing = keys.map { it.name }.filter { it !in backedUp && it !in notInSettingsBackup }
        assertEquals("keys with no backup decision", emptyList<String>(), missing)
    }

    @Test
    fun `an exclusion is not also backed up, and names a real key`() {
        val names = allKeys().map { it.name }.toSet()
        assertEquals(emptySet<String>(), notInSettingsBackup intersect backedUp)
        assertEquals(emptySet<String>(), notInSettingsBackup - names)
    }

    @Test
    fun `the settings the audit found missing are backed up`() {
        listOf(
            "multiview_enabled", "multiview_tiles", "multiview_warning_accepted",
            "recording_reserve_connection", "record_what_im_watching", "recording_pre_roll_minutes",
            "recording_post_roll_minutes", "recording_over_mobile_data",
        ).forEach { assertTrue(it, it in backedUp) }
    }

    @Test
    fun `pip_on_back is never exported but stays importable`() {
        assertTrue(Keys.PIP_ON_BACK.name in backedUp)
        assertFalse(BackupKeys.exports(Keys.PIP_ON_BACK, mutablePreferencesOf(Keys.PIP_ON_BACK to true)))
    }

    @Test
    fun `a legacy key is exported only while it is still the value read`() {
        val old = mutablePreferencesOf(Keys.SUB_SCALE to 1.5f)
        assertTrue(BackupKeys.exports(Keys.SUB_SCALE, old))
        old[Keys.SUB_SCALE_MPV] = 1.5f
        old[Keys.SUB_SCALE_EXO] = 1.5f
        assertTrue("sub_style_enabled still falls back to it", BackupKeys.exports(Keys.SUB_SCALE, old))
        old[Keys.SUB_STYLE_ENABLED] = true
        assertFalse(BackupKeys.exports(Keys.SUB_SCALE, old))

        assertTrue(BackupKeys.exports(Keys.SURROUND_SOUND, mutablePreferencesOf(Keys.SURROUND_SOUND to true)))
        assertFalse(BackupKeys.exports(Keys.SURROUND_SOUND, mutablePreferencesOf(Keys.SURROUND_SOUND to true, Keys.SURROUND_MODE to "AUTO")))

        assertTrue(BackupKeys.exports(Keys.RESUME_LAST_CHANNEL, mutablePreferencesOf(Keys.RESUME_LAST_CHANNEL to true)))
        assertFalse(BackupKeys.exports(Keys.RESUME_LAST_CHANNEL, mutablePreferencesOf(Keys.RESUME_LAST_CHANNEL to false)))
        // Current keys are always exported.
        assertTrue(BackupKeys.exports(Keys.MULTIVIEW_ENABLED, mutablePreferencesOf()))
    }
}
