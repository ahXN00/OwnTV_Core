package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HttpDiagnosticsTest {

    @Test
    fun `a readable refusal is kept, html, short and binary bodies are not`() {
        assertEquals("Channel limit has been reached", HttpDiagnostics.providerMessage("text/plain", "  Channel limit has been reached "))
        assertNull(HttpDiagnostics.providerMessage("text/html; charset=utf-8", "Just a moment, enable JavaScript"))
        assertNull(HttpDiagnostics.providerMessage(null, "error"))
        assertNull(HttpDiagnostics.providerMessage(null, "G@#\$%^&*()_+{}|:<>?~!@#"))
    }

    @Test
    fun `the body prefix drops markup and never carries the account`() {
        val url = "http://panel.example/live/alice/s3cret/123.ts?token=abcdef123"
        val body = "<html><b>User alice</b>\n with password s3cret and token abcdef123 is   blocked</html>".toByteArray()
        val text = HttpDiagnostics.textPrefix(body, url)
        assertEquals("User *** with password *** and token *** is blocked", text)
        assertFalse(text.contains("alice"))
    }

    @Test
    fun `the prefix is one short line with no double quotes`() {
        val text = HttpDiagnostics.textPrefix(("say \"no\" " + "x".repeat(300)).toByteArray(), "http://h/x.ts")
        assertFalse(text.contains('"'))
        assertEquals(160, text.length)
    }

    @Test
    fun `request roles`() {
        assertEquals("playlist", HttpDiagnostics.requestRole("http://h/a/index.M3U8?t=1"))
        assertEquals("segment", HttpDiagnostics.requestRole("http://h/a/seg1.ts"))
        assertEquals("segment", HttpDiagnostics.requestRole("http://h/a/seg1.m4s"))
        assertEquals("key", HttpDiagnostics.requestRole("http://h/a/k.key"))
        assertEquals("stream", HttpDiagnostics.requestRole("http://h/live/u/p/1"))
    }

    @Test
    fun `media signatures`() {
        val ts = ByteArray(400).also { it[0] = 0x47; it[188] = 0x47; it[376] = 0x47 }
        assertEquals("mpeg-ts", HttpDiagnostics.mediaSignature(ts))
        // One sync byte is an error page starting with 'G', not a transport stream.
        assertEquals("unknown", HttpDiagnostics.mediaSignature("Gateway timeout".toByteArray()))
        assertEquals("hls-manifest", HttpDiagnostics.mediaSignature("  #EXTM3U\n".toByteArray()))
        assertEquals("html", HttpDiagnostics.mediaSignature("<!DOCTYPE html>".toByteArray()))
        assertEquals("json", HttpDiagnostics.mediaSignature("{\"e\":1}".toByteArray()))
        assertEquals("mp4", HttpDiagnostics.mediaSignature(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        assertEquals("matroska", HttpDiagnostics.mediaSignature(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertEquals("empty", HttpDiagnostics.mediaSignature(byteArrayOf()))
    }
}
