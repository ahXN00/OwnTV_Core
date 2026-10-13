package tv.own.owntv.core.network

import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsConfigHolderTest {

    @Test
    fun `initial config is available before flow collection`() {
        val selected = DnsConfig(
            enabled = true,
            dohUrl = DohPresets.CLOUDFLARE,
        )

        val holder = DnsConfigHolder(
            configFlow = emptyFlow(),
            initialConfig = selected,
            fallbackToSystem = false,
        )

        assertEquals(selected, holder.snapshot())
    }

    @Test
    fun `strict lookup does not hide a broken custom server with system dns`() {
        val holder = DnsConfigHolder(
            configFlow = emptyFlow(),
            initialConfig = DnsConfig(enabled = true, dohUrl = "not-a-valid-url"),
            fallbackToSystem = false,
        )

        val ex = assertThrows(DohLookupException::class.java) {
            holder.dns.lookup("dns.google")
        }
        assertTrue(ex.cause is IllegalArgumentException)
    }
}
