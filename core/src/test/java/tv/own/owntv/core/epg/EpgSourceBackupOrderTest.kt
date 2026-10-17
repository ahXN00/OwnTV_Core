package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * The EPG Sources order is the guide priority, and a backup carries it as the order of the stored
 * list. These follow it out through the backup text and back in through the merge-restore.
 */
class EpgSourceBackupOrderTest {

    private fun source(id: Long, name: String, host: String) =
        EpgSource(id = id, name = name, url = "https://$host/epg.xml")

    private val a = source(-1, "Guide A", "a.example")
    private val b = source(-2, "Guide B", "b.example")
    private val c = source(-3, "Guide C", "c.example")

    private fun urls(list: List<EpgSource>) = list.map { it.url }

    @Test
    fun `the backup text keeps the list order and every field`() {
        val list = listOf(c.copy(userAgent = "Player/1.0", lastSyncAt = 42L, lastError = "timeout"), a, b)
        assertEquals(list, EpgSourceJson.parse(EpgSourceJson.write(list)))
    }

    @Test
    fun `restoring onto a fresh install reproduces the backup's order`() {
        val backup = EpgSourceJson.write(listOf(c, a, b))
        val merged = mergeEpgSources(emptyList(), EpgSourceJson.parse(backup), nextId = -1)
        assertEquals(urls(listOf(c, a, b)), urls(merged.sources))
        // Fresh ids, counting down, mapped back from the backup's own.
        assertEquals(listOf(-1L, -2L, -3L), merged.sources.map { it.id })
        assertEquals(mapOf(-3L to -1L, -1L to -2L, -2L to -3L), merged.idMap)
        assertEquals(-4L, merged.nextId)
    }

    @Test
    fun `feeds the device already has take the backup's order among themselves`() {
        val deviceOnly = source(-7, "Local Guide", "local.example")
        val device = listOf(a, deviceOnly, b)
        val backup = listOf(b.copy(id = -20), a.copy(id = -10), c.copy(id = -30))
        val merged = mergeEpgSources(device, backup, nextId = -8)
        assertEquals(
            listOf("https://b.example/epg.xml", "https://local.example/epg.xml", "https://a.example/epg.xml", "https://c.example/epg.xml"),
            urls(merged.sources),
        )
        // The device's ids stay, so its synced guide rows keep their place in the priority.
        assertEquals(listOf(-2L, -7L, -1L, -8L), merged.sources.map { it.id })
    }

    @Test
    fun `the restored list ranks the guides in the backup's order`() {
        val merged = mergeEpgSources(emptyList(), listOf(b, a), nextId = -1)
        val priority = GuidePriority.of(merged.sources.map { it.id })
        val fromB = EpgProgrammeEntity(
            id = 1, sourceId = merged.idMap.getValue(b.id), epgChannelId = "news.example",
            startMs = 0, stopMs = 60_000, title = "From B",
        )
        val fromA = fromB.copy(id = 2, sourceId = merged.idMap.getValue(a.id), title = "From A")
        assertEquals(listOf("From B"), priority.resolve(listOf(fromA, fromB)).map { it.title })
    }
}
