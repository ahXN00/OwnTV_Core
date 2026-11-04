package tv.own.owntv.core.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class CompanionSourceEditTest {

    private val xtream = SourceEntity(
        name = "Home", type = SourceType.XTREAM, url = "http://h:80", username = "u", password = "secret",
        userAgent = "UA", epgUrl = "http://e",
    )
    private val m3u = SourceEntity(
        name = "List", type = SourceType.M3U, url = "http://h/get.php?username=u&password=secret", userAgent = "UA",
    )
    private val stalker = SourceEntity(
        name = "Portal", type = SourceType.STALKER, url = "http://p/c/", username = "login", password = "pw",
        mac = "00:1A:79:00:00:01", stalkerSerialNumber = "SN", stalkerDeviceId = "D1", stalkerDeviceId2 = "D2",
        stalkerSignature = "SIG",
    )

    @Test
    fun `the page never gets a password, a MAC, the device identity or an M3U address`() {
        val x = CompanionSourceEdit.prefill(xtream)
        assertEquals(CompanionPayload(type = SourceType.XTREAM, name = "Home", server = "http://h:80", user = "u", userAgent = "UA", epgUrl = "http://e"), x)

        val m = CompanionSourceEdit.prefill(m3u)
        assertEquals("", m.server)
        assertEquals("UA", m.userAgent)

        val s = CompanionSourceEdit.prefill(stalker)
        assertEquals(CompanionPayload(type = SourceType.STALKER, name = "Portal", portalUrl = "http://p/c/", user = "login"), s)
    }

    @Test
    fun `an empty field keeps the playlist's value`() {
        val x = CompanionSourceEdit.merge(xtream, CompanionPayload(type = SourceType.XTREAM, user = "new"))
        assertEquals("Home", x.name)
        assertEquals("http://h:80", x.server)
        assertEquals("new", x.user)
        assertEquals("secret", x.pass)

        val m = CompanionSourceEdit.merge(m3u, CompanionPayload(type = SourceType.M3U, name = "Renamed"))
        assertEquals("Renamed", m.name)
        assertEquals(m3u.url, m.server)

        val s = CompanionSourceEdit.merge(stalker, CompanionPayload(type = SourceType.STALKER, mac = "00:1A:79:00:00:02"))
        assertEquals("00:1A:79:00:00:02", s.mac)
        assertEquals("http://p/c/", s.portalUrl)
        assertEquals("pw", s.pass)
        assertEquals(listOf("SN", "D1", "D2", "SIG"), listOf(s.serialNumber, s.deviceId, s.deviceId2, s.signature))
    }

    @Test
    fun `optional fields sent empty are cleared`() {
        val x = CompanionSourceEdit.merge(xtream, CompanionPayload(type = SourceType.XTREAM))
        assertEquals("", x.userAgent)
        assertEquals("", x.epgUrl)

        val s = CompanionSourceEdit.merge(stalker, CompanionPayload(type = SourceType.STALKER))
        assertEquals("", s.user)
    }

    @Test
    fun `a new password replaces the stored one`() {
        val x = CompanionSourceEdit.merge(xtream, CompanionPayload(type = SourceType.XTREAM, pass = "changed"))
        assertEquals("changed", x.pass)
    }
}
