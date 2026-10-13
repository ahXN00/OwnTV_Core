package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool

class StoredArchiveUrlTest {

    private val source = SourceEntity(
        id = 1,
        name = "X",
        type = SourceType.XTREAM,
        url = "http://a.example.top",
        username = "u",
        password = "p",
        maxConnections = 1,
        backupUrls = HostPool.encode(listOf("http://b.example.top")),
    )

    @Test
    fun `a backup timeshift URL is stored on the primary with the same path`() {
        val path = "/timeshift/u/p/60/2026-10-14:07-00/7.ts"
        assertEquals(
            "http://a.example.top$path",
            storedArchiveUrl("http://b.example.top$path", source),
        )
    }

    @Test
    fun `a foreign URL is unchanged`() {
        val foreign = "http://other.example/timeshift/u/p/60/2026-10-14:07-00/7.ts"
        assertEquals(foreign, storedArchiveUrl(foreign, source))
    }
}
