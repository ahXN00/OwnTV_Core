package tv.own.owntv.core.stalker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.StalkerSyncer
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.core.util.isTransientSyncError

/**
 * The optional portal login (`do_auth`) and the portal's bare-text refusal. A MAC-only playlist
 * must make exactly the requests it always made; a playlist with a login sends it once per session,
 * between the handshake and the profile.
 */
class StalkerLoginTest {

    private val creds = StalkerCredentials(sourceId = 7L, portalUrl = "http://portal/c/", mac = "00:1A:79:AA:BB:CC")

    /** Records the order of the session calls. */
    private class FakeClient(private val loginAccepted: Boolean = true) : StalkerClient(okhttp3.OkHttpClient()) {
        val calls = ArrayList<String>()
        var login: String? = null
        override suspend fun resolveHandshake(portalUrl: String, mac: String, userAgent: String?): Handshake {
            calls += "handshake"
            return Handshake("http://portal/portal.php", "t${calls.size}")
        }

        override suspend fun doAuth(
            apiBase: String, mac: String, token: String, userAgent: String?,
            login: String, password: String, identity: StalkerDeviceIdentity,
        ) {
            calls += "do_auth"
            this.login = login
            if (!loginAccepted) throw StalkerAuthException("Portal login rejected — check the username and password")
        }

        override suspend fun getProfile(
            apiBase: String, mac: String, token: String, userAgent: String?, identity: StalkerDeviceIdentity,
        ): Map<String, String> {
            calls += "profile"
            return mapOf("status" to "1")
        }
    }

    @Test
    fun aMacOnlyPlaylistNeverSendsALogin() = runBlocking {
        val client = FakeClient()
        StalkerAuthManager(client).sessionFor(creds)
        assertEquals(listOf("handshake", "profile"), client.calls)
    }

    @Test
    fun theLoginGoesBetweenHandshakeAndProfile() = runBlocking {
        val client = FakeClient()
        StalkerAuthManager(client).sessionFor(creds.copy(login = "  ", password = "x"))
        assertEquals("a blank login is no login", listOf("handshake", "profile"), client.calls)

        val withLogin = FakeClient()
        StalkerAuthManager(withLogin).sessionFor(creds.copy(login = "anna", password = "secret"))
        assertEquals(listOf("handshake", "do_auth", "profile"), withLogin.calls)
        assertEquals("anna", withLogin.login)
    }

    @Test
    fun aRefusedLoginIsReportedAsAWrongLogin() {
        val auth = StalkerAuthManager(FakeClient(loginAccepted = false))
        val e = assertThrows(StalkerClient.StalkerAuthException::class.java) {
            runBlocking { auth.sessionFor(creds.copy(login = "anna", password = "wrong")) }
        }
        assertEquals(FriendlySyncFailure.AuthenticationRejected, classifySyncFailure(e.message, online = true))
    }

    @Test
    fun doAuthRequestShape() {
        val url = StalkerClient.doAuthUrl(
            "http://portal/portal.php", "anna smith", "p&ss",
            StalkerDeviceIdentity(deviceId = "D1", deviceId2 = "D2"),
        )
        assertEquals(
            "http://portal/portal.php?type=stb&action=do_auth&login=anna+smith&password=p%26ss" +
                "&device_id=D1&device_id2=D2&JsHttpRequest=1-xml",
            url,
        )
    }

    @Test
    fun thePlaylistsLoginReachesTheCredentials() {
        val source = SourceEntity(name = "P", type = SourceType.STALKER, url = "http://portal/c/", username = "anna", password = "secret")
        val c = source.stalkerCredentials("00:1A:79:AA:BB:CC")
        assertEquals("anna", c.login)
        assertEquals("secret", c.password)
        assertFalse("credentials are logged; the password must not be", c.toString().contains("secret"))
    }

    // ---- "Authorization failed." ----

    @Test
    fun aBareTextRefusalIsRecognised() {
        assertTrue(StalkerClient.isRefusal("Authorization failed."))
        assertTrue(StalkerClient.isRefusal("  authorization failed\n"))
        assertFalse(StalkerClient.isRefusal("<html>Not found</html>"))
    }

    @Test
    fun aRefusalIsAThrottleWorthRetrying() {
        val e = StalkerClient.StalkerRefusedException("Portal refused the request (Authorization failed)")
        assertTrue(StalkerSyncer.isTransientPortalError(e))
        assertTrue(StalkerSyncer.isPortalThrottle(e))
        assertEquals(FriendlySyncFailure.ServerError, classifySyncFailure(e.message, online = true))
        assertTrue(isTransientSyncError(e.message, online = true))
    }

    @Test
    fun aRefusalDropsTheSessionButIsNotRetriedOnTheSpot() = runBlocking {
        val client = FakeClient()
        val auth = StalkerAuthManager(client)
        var calls = 0
        assertThrows(StalkerClient.StalkerRefusedException::class.java) {
            runBlocking {
                auth.withAuthRetry(creds) {
                    calls++
                    throw StalkerClient.StalkerRefusedException("Portal refused the request (Authorization failed)")
                }
            }
        }
        assertEquals("the backoff, not this layer, decides when to ask again", 1, calls)
        auth.sessionFor(creds)
        assertEquals("the next attempt starts on a fresh handshake", 2, client.calls.count { it == "handshake" })
    }

    @Test
    fun retryAfterIsReadInSeconds() {
        assertEquals(30_000L, StalkerClient.retryAfterMs("30"))
        assertNull(StalkerClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(StalkerClient.retryAfterMs(null))
    }
}
