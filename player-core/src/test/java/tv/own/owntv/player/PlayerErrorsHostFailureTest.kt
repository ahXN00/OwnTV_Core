package tv.own.owntv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.network.HostFailureClass
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** AC9 (ExoPlayer side): typed classification; never-class rows produce no switch. */
class PlayerErrorsHostFailureTest {

    private fun of(code: Int, http: Int? = null, opening: Boolean? = null, cause: Throwable? = null, headers: Boolean = false) =
        PlayerErrors.hostFailureOf(code, http, opening, cause, headers)

    private val io = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    @Test
    fun `dead before headers is host-class`() {
        assertEquals(HostFailureClass.HOST, of(io, cause = UnknownHostException("x")))
        assertEquals(HostFailureClass.HOST, of(io, cause = ConnectException("refused")))
        assertEquals(HostFailureClass.HOST, of(io, cause = SSLHandshakeException("handshake")))
        assertEquals(HostFailureClass.HOST, of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, opening = true, cause = SocketTimeoutException("timeout")))
        listOf(500, 502, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530).forEach {
            assertEquals("$it", HostFailureClass.HOST, of(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http = it))
        }
    }

    @Test
    fun `alive after headers never switches`() {
        // A read timeout on a stream that already answered: a slow cold restream, not a dead server.
        assertEquals(HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, opening = false, cause = SocketTimeoutException("timeout")))
        assertEquals(HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, opening = true, cause = SocketTimeoutException("timeout"), headers = true))
        listOf(503, 401, 402, 403, 404, 429).forEach { assertEquals("$it", HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http = it)) }
        // HTML / Invalid data / UnrecognizedInputFormat: the container parser ran, so headers existed.
        assertEquals(HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, cause = ConnectException("x")))
        assertEquals(HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(HostFailureClass.NEVER, of(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
    }

    @Test
    fun `session limits wait`() {
        listOf(458, 407, 509).forEach { assertEquals("$it", HostFailureClass.WAIT, of(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http = it)) }
    }
}
