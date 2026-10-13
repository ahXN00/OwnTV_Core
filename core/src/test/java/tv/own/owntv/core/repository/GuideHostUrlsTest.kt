package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool

class GuideHostUrlsTest {

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

    private val guide = "http://a.example.top/xmltv.php?username=u&password=p"

    @Test
    fun apiHostsRewriteOntoEachHostKeepingPath() {
        val urls = guideHostUrlsFor(guide, listOf(source)) {
            listOf("http://b.example.top", "http://a.example.top")
        }
        assertEquals(2, urls.size)
        assertEquals("http://b.example.top/xmltv.php?username=u&password=p", urls[0])
        assertEquals("http://a.example.top/xmltv.php?username=u&password=p", urls[1])
    }

    @Test
    fun foreignUrlIsUnchanged() {
        val foreign = "http://epg.example.org/guide.xml"
        assertEquals(listOf(foreign), guideHostUrlsFor(foreign, listOf(source)) { listOf("http://b.example.top") })
    }

    @Test
    fun emptyApiHostsKeepsOriginal() {
        assertEquals(listOf(guide), guideHostUrlsFor(guide, listOf(source)) { emptyList() })
    }
}
