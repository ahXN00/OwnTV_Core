package tv.own.owntv.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool

/** AC16: backup v25 carries the backup servers and the switch, never the in-use server, and sanitises on import. */
class BackupV25Test {

    private val device = SourceEntity(
        id = 3, name = "X", type = SourceType.XTREAM, url = "http://a.example.top:8080", username = "u", password = "p",
        backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
        activeUrl = "http://c.example.top",
        failoverEnabled = false,
    )

    @Test
    fun `the list and the switch round-trip and the in-use server is never written`() {
        val o = JSONObject().also { BackupManager.writeFailover(it, device) }
        assertFalse(o.has("activeUrl"))
        assertEquals(2, o.getJSONArray("backupUrls").length())
        val restored = BackupManager.mergeFailover(device.copy(backupUrls = null, activeUrl = null, failoverEnabled = true), o)
        assertEquals(device.backupUrls, restored.backupUrls)
        assertFalse(restored.failoverEnabled)
        assertNull(restored.activeUrl)
    }

    @Test
    fun `a pre-v25 file leaves the device's servers, switch and in-use server alone`() {
        val old = JSONObject().put("name", "X").put("url", device.url)
        val merged = BackupManager.mergeFailover(device, old)
        assertEquals(device.backupUrls, merged.backupUrls)
        assertEquals(device.failoverEnabled, merged.failoverEnabled)
        assertEquals("http://c.example.top", merged.activeUrl)
    }

    @Test
    fun `import strips logins, queries and panel paths, drops junk and caps at 50`() {
        val hostile = JSONArray()
            .put("http://evil:secret@b.example.top/get.php?username=u&password=p")
            .put("not an address")
            .put("http://d.example.top/iptv")
        (1..60).forEach { hostile.put("h$it.example.top") }
        val merged = BackupManager.mergeFailover(device, JSONObject().put("backupUrls", hostile))
        val list = HostPool.decode(merged.backupUrls)
        assertEquals(HostPool.MAX_BACKUPS, list.size)
        assertEquals("http://b.example.top", list.first())
        assertTrue(list.none { '@' in it || '?' in it || "iptv" in it || ' ' in it })
    }

    @Test
    fun `a merge that drops the in-use server resets it to the primary`() {
        val file = JSONObject().put("backupUrls", JSONArray().put("http://b.example.top"))
        assertNull(BackupManager.mergeFailover(device, file).activeUrl)
        val keeps = JSONObject().put("backupUrls", JSONArray().put("http://c.example.top"))
        assertEquals("http://c.example.top", BackupManager.mergeFailover(device, keeps).activeUrl)
    }
}
