package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient.XtAccountDetails
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.classifySyncFailure

/**
 * A panel that rejects the username or password answers every list call with an error object, which
 * reads as a complete, empty list. The sync must fail instead — before anything is written or the
 * playlist is stamped synced — and the row must say the credentials were refused.
 */
class XtreamRejectedCredentialsTest {

    @Test
    fun `auth 0 fails the sync as rejected credentials`() {
        val e = assertThrows(HttpClient.HttpStatusException::class.java) {
            XtreamSyncer.requireAccepted(XtAccountDetails(authOk = false))
        }
        assertEquals(401, e.code)
        assertEquals(FriendlySyncFailure.AuthenticationRejected, classifySyncFailure(e.message, online = true))
    }

    @Test
    fun `accepted credentials and an unanswered call both go ahead`() {
        XtreamSyncer.requireAccepted(XtAccountDetails(authOk = true))
        XtreamSyncer.requireAccepted(null)
    }
}
