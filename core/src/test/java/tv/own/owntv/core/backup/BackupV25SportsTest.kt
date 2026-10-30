package tv.own.owntv.core.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.AlertKind
import tv.own.owntv.core.sports.AutoRebuild
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.GoalAlerts
import tv.own.owntv.core.sports.MinQuality
import tv.own.owntv.core.sports.RememberedChannel
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Sport
import java.io.File

/**
 * Backup format v25: each profile's Settings → Sports blob rides in "sportsConfigs", keyed by profile
 * id, scoped to the ticked profiles and remapped to this device's ids on restore — the same path as
 * Home's "homeConfigs". No database needed: this is the JSON the export writes and the restore reads.
 */
class BackupV25SportsTest {

    private val anna = SportsConfig(
        leagues = listOf("soccer/ger.1", "soccer/uefa.champions"),
        autoRebuild = AutoRebuild.MANUAL,
        strictFilter = false,
        sourceIds = setOf(2L),
        teams = listOf(FollowedTeam(Sport.SOCCER, "125", "Eintracht Frankfurt", "SGE")),
        languages = setOf("DE", "EN"),
        minQuality = MinQuality.FHD,
        spoilerFree = true,
        rememberedChannels = mapOf("soccer/ger.1" to RememberedChannel("Sky Sport Bundesliga 1", "DE")),
        alerts = GoalAlerts(enabled = true, kinds = setOf(AlertKind.GOALS, AlertKind.HALF_TIME)),
        reminderLeadMinutes = 15,
    )
    private val kids = SportsConfig(leagues = listOf("basketball/nba"), liveRefreshSeconds = 60)

    /** What exportSportsConfigs writes: { "<profileId>": blob }. */
    private val exported = JSONObject()
        .put("1", anna.toJson())
        .put("2", kids.toJson())

    @Test
    fun `export then restore onto another device gives identical values`() {
        // Ticked: profile 1 only. On the new device profile 1 became 41.
        val file = JSONObject().put("version", 25).put("sportsConfigs", filterByProfile(exported, setOf("1")))
        val parsed = JSONObject(file.toString())
        val restored = remapProfileKeys(parsed.getJSONObject("sportsConfigs"), mapOf(1L to 41L))

        assertEquals(setOf("41"), restored.keys().asSequence().toSet())
        assertEquals(anna, SportsConfig.fromJson(restored.getJSONObject("41").toString()))
    }

    @Test
    fun `an unticked profile's sports settings do not enter the file`() {
        val scoped = filterByProfile(exported, setOf("1"))
        assertFalse(scoped.has("2"))
        assertEquals(kids, SportsConfig.fromJson(filterByProfile(exported, setOf("2")).getJSONObject("2").toString()))
    }

    @Test
    fun `a v24 file has no block, so every profile keeps the defaults`() {
        val v24 = JSONObject().put("version", 24).put("settings", JSONObject()).put("homeConfigs", JSONObject())
        assertNull(v24.optJSONObject("sportsConfigs"))
        assertEquals(SportsConfig(), SportsConfig.fromJson(null))
    }

    @Test
    fun `the export and the restore both name the block, inside the per-profile settings`() {
        val source = File("src/main/java/tv/own/owntv/core/backup/BackupManager.kt").readText()
        assertTrue(source.contains("put(\"version\", 26)"))
        assertTrue(source.contains("put(\"sportsConfigs\", filterByProfile(settings.exportSportsConfigs(), pidKeys))"))
        assertTrue(source.contains("root.optJSONObject(\"sportsConfigs\")?.let { settings.importSportsConfigs(remapProfileKeys(it, profileIdMap), pids) }"))
    }
}
