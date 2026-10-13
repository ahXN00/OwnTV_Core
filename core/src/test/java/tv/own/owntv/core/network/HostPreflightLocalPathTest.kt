package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/** AC30: CONNECT/proxy failures count only when a proxy or DoH is configured. */
class HostPreflightLocalPathTest {

    private val off = HostPreflight.LocalPathConfig()
    private val proxy = HostPreflight.LocalPathConfig(proxyHost = "proxy.example", proxyPort = 8080)
    private val doh = HostPreflight.LocalPathConfig(dohOn = true)

    @Test
    fun `CONNECT is local only when a proxy is configured`() {
        val connect = IOException("Unexpected response code for CONNECT: 407")
        assertFalse(HostPreflight.interpretFailure(connect, off) is HostPreflight.Result.LocalPath)
        assertEquals(
            HostPreflight.Result.LocalPath(HostPreflight.Result.LocalPath.Kind.PROXY),
            HostPreflight.interpretFailure(connect, proxy),
        )
        assertEquals(
            HostPreflight.Result.LocalPath(HostPreflight.Result.LocalPath.Kind.PROXY),
            HostPreflight.interpretFailure(
                ConnectException("Failed to connect to proxy.example/1.2.3.4:8080"),
                proxy,
            ),
        )
    }

    @Test
    fun `a host whose name contains proxy is still the server`() {
        assertEquals(
            HostPreflight.Result.Down("myproxy.example.top"),
            HostPreflight.interpretFailure(UnknownHostException("myproxy.example.top"), off),
        )
    }

    @Test
    fun `DoH failure is local only while DoH is on`() {
        val e = DohLookupException(IOException())
        assertEquals(
            HostPreflight.Result.LocalPath(HostPreflight.Result.LocalPath.Kind.DOH),
            HostPreflight.interpretFailure(e, doh),
        )
        assertEquals(HostPreflight.Result.Down(e.message), HostPreflight.interpretFailure(e, off))
    }
}
