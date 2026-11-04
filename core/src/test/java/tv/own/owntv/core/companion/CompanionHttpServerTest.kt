package tv.own.owntv.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.SyncScopeChoice

/**
 * Tests the pure parse/auth logic of the companion server (no socket bound). The JSON branch relies
 * on `org.json`, which is only a stub in plain JVM unit tests, so it is exercised on-device; the
 * form-encoded path (what the HTML form actually submits) is fully covered here.
 */
class CompanionHttpServerTest {

    private val protocol = CompanionHttpProtocol
    private val form = "application/x-www-form-urlencoded"

    // ---- PIN comparison ----

    @Test
    fun `pin matches only when equal and same length`() {
        assertTrue(protocol.pinEquals("123456", "123456"))
        assertFalse(protocol.pinEquals("123456", "123457"))
        assertFalse(protocol.pinEquals("12345", "123456"))
        assertFalse(protocol.pinEquals("", "123456"))
    }

    // ---- query / form parsing ----

    @Test
    fun `parseQuery decodes pairs and plus-as-space`() {
        val map = protocol.parseQuery("a=1&b=hello+world&c=")
        assertEquals("1", map["a"])
        assertEquals("hello world", map["b"])
        assertEquals("", map["c"])
    }

    // ---- Xtream ----

    @Test
    fun `xtream form parses all fields`() {
        val body = "type=xtream&name=My+IPTV&server=http://h:80&user=u&pass=p&userAgent=UA&epgUrl=http://e" +
            "&syncLive=now&syncMovies=later&syncSeries=off&isDefault=on&autoRefresh=HOURS_6"
        val p = protocol.parsePayload(form, body, SourceType.XTREAM)!!
        assertEquals(SourceType.XTREAM, p.type)
        assertEquals("My IPTV", p.name)
        assertEquals("http://h:80", p.server)
        assertEquals("u", p.user)
        assertEquals("p", p.pass)
        assertEquals("UA", p.userAgent)
        assertEquals("http://e", p.epgUrl)
        assertEquals("HOURS_6", p.autoRefresh)
        assertEquals(SyncScopeChoice.Now, p.syncLive)
        assertEquals(SyncScopeChoice.Later, p.syncMovies)
        assertEquals(SyncScopeChoice.Off, p.syncSeries)
        assertTrue(p.isDefault)
    }

    @Test
    fun `xtream missing credentials is rejected`() {
        assertNull(protocol.parsePayload(form, "type=xtream&server=http://h", SourceType.XTREAM))
    }

    @Test
    fun `an edit may leave the password empty`() {
        val p = protocol.parsePayload(form, "type=xtream&server=http://h&user=u&pass=", SourceType.XTREAM, requireFields = false)!!
        assertEquals("", p.pass)
        assertEquals("u", p.user)
    }

    @Test
    fun `legacy boolean sync fields map to Now or Off`() {
        val off = protocol.parsePayload(
            form,
            "type=xtream&server=h&user=u&pass=p&syncLive=false&syncMovies=false&syncSeries=false&isDefault=false",
            SourceType.XTREAM,
        )!!
        assertEquals(SyncScopeChoice.Off, off.syncLive)
        assertEquals(SyncScopeChoice.Off, off.syncMovies)
        assertEquals(SyncScopeChoice.Off, off.syncSeries)
        assertFalse(off.isDefault)

        val on = protocol.parsePayload(
            form,
            "type=xtream&server=h&user=u&pass=p&syncLive=true&syncMovies=on&syncSeries=1",
            SourceType.XTREAM,
        )!!
        assertEquals(SyncScopeChoice.Now, on.syncLive)
        assertEquals(SyncScopeChoice.Now, on.syncMovies)
        assertEquals(SyncScopeChoice.Now, on.syncSeries)
    }

    // ---- M3U ----

    @Test
    fun `m3u form parses with url alias and only needs the url`() {
        val p = protocol.parsePayload(form, "type=m3u&name=P&url=http://x/p.m3u", SourceType.M3U)!!
        assertEquals(SourceType.M3U, p.type)
        assertEquals("http://x/p.m3u", p.server)
        assertEquals("P", p.name)
    }

    @Test
    fun `m3u without a url is rejected`() {
        assertNull(protocol.parsePayload(form, "type=m3u&name=P", SourceType.M3U))
    }

    /**
     * Uploading a playlist from the companion page: the browser reads the file and posts its text as
     * JSON, because a file input cannot ride in a urlencoded body and the TV has no multipart parser.
     * The URL is then legitimately absent — a submission with neither is still rejected below.
     */
    @Test
    fun `m3u accepts an uploaded playlist instead of a url`() {
        // Built with JSONObject rather than hand-written: the playlist text contains newlines, which
        // must be escaped, and that is exactly what the browser's JSON.stringify does.
        val playlist = "#EXTM3U\n#EXTINF:-1,A\nhttp://h/a.ts\n"
        val body = org.json.JSONObject()
            .put("type", "m3u").put("name", "Jio")
            .put("playlistFileName", "drm-test.m3u")
            .put("playlistFile", playlist)
            .toString()
        val p = protocol.parsePayload("application/json", body, SourceType.M3U)!!
        assertEquals(playlist, p.playlistContent)
        assertEquals(SourceType.M3U, p.type)
        assertEquals("drm-test.m3u", p.playlistFileName)
        assertTrue(p.playlistContent.startsWith("#EXTM3U"))
        // The controller fills this in once the file is on disk; the protocol leaves it blank.
        assertEquals("", p.server)
    }

    @Test
    fun `m3u with neither a url nor a file is still rejected`() {
        assertNull(protocol.parsePayload("application/json", """{"type":"m3u","name":"P"}""", SourceType.M3U))
        assertNull(protocol.parsePayload("application/json", """{"type":"m3u","playlistFile":"  "}""", SourceType.M3U))
    }

    /** A playlist is far larger than a form field, so `/m3u` must get the upload-sized body limit. */
    @Test
    fun `the m3u endpoint accepts an upload-sized body`() {
        assertEquals(CompanionHttpProtocol.UPLOAD_BODY_LIMIT, protocol.maxBodyBytes("/m3u"))
        assertEquals(CompanionHttpProtocol.FORM_BODY_LIMIT, protocol.maxBodyBytes("/xtream"))
    }

    // ---- Stalker ----

    @Test
    fun `stalker form needs portal and mac`() {
        val p = protocol.parsePayload(
            form,
            "type=stalker&portalUrl=http://h/c/&mac=00:1A:79:AA:BB:CC&sn=SERIAL-1" +
                "&device_id=DEVICE-1&deviceId2=DEVICE-2&signature=SIG-1",
            SourceType.STALKER,
        )!!
        assertEquals(SourceType.STALKER, p.type)
        assertEquals("http://h/c/", p.portalUrl)
        assertEquals("00:1A:79:AA:BB:CC", p.mac)
        assertEquals("SERIAL-1", p.serialNumber)
        assertEquals("DEVICE-1", p.deviceId)
        assertEquals("DEVICE-2", p.deviceId2)
        assertEquals("SIG-1", p.signature)
    }

    @Test
    fun `stalker missing mac is rejected`() {
        assertNull(protocol.parsePayload(form, "type=stalker&portalUrl=http://h/c/", SourceType.STALKER))
    }

    // ---- fallback type ----

    @Test
    fun `blank type falls back to the endpoint's type`() {
        val p = protocol.parsePayload(form, "server=h&user=u&pass=p", SourceType.XTREAM)!!
        assertEquals(SourceType.XTREAM, p.type)
    }

    // ---- robustness ----

    // ---- body cap ----

    private fun read(body: String, contentLength: String, limit: Int): String? =
        protocol.readBody(
            java.io.BufferedInputStream(java.io.ByteArrayInputStream(body.toByteArray())),
            mapOf("content-length" to contentLength),
            limit,
        )

    @Test
    fun `uploads get the large cap and forms the small one`() {
        assertEquals(CompanionHttpProtocol.UPLOAD_BODY_LIMIT, protocol.maxBodyBytes("/backup"))
        assertEquals(CompanionHttpProtocol.UPLOAD_BODY_LIMIT, protocol.maxBodyBytes("/background"))
        assertEquals(CompanionHttpProtocol.FORM_BODY_LIMIT, protocol.maxBodyBytes("/"))
        assertEquals(CompanionHttpProtocol.FORM_BODY_LIMIT, protocol.maxBodyBytes("/xtream"))
    }

    @Test
    fun `a body within the cap is read whole`() {
        assertEquals("pin=123456", read("pin=123456", "10", CompanionHttpProtocol.FORM_BODY_LIMIT))
        assertEquals("", read("", "0", CompanionHttpProtocol.FORM_BODY_LIMIT))
    }

    @Test
    fun `an oversized Content-Length is rejected without allocating it`() {
        // The pre-fix code did ByteArray(2_000_000_000) here — an unauthenticated OOM from the LAN.
        assertNull(read("", "2000000000", CompanionHttpProtocol.FORM_BODY_LIMIT))
        assertNull(read("", (CompanionHttpProtocol.UPLOAD_BODY_LIMIT + 1).toString(), CompanionHttpProtocol.UPLOAD_BODY_LIMIT))
    }

    @Test
    fun `a body longer than the cap is cut off even when the header lies small`() {
        assertNull(read("x".repeat(200), "200", 64))
    }

    /** It must not hang either; the server catches the throw and drops the request unanswered. */
    @Test(expected = java.io.EOFException::class)
    fun `a body that stops short of its Content-Length is refused, not kept in part`() {
        read("abc", "500", CompanionHttpProtocol.FORM_BODY_LIMIT)
    }

    /** A JSON null must be dropped, not turned into the string "null" — the sender omits optional
     *  fields that way, and a literal "null" would be stored as the user-agent or EPG URL. */
    @Test
    fun `json null values are ignored rather than stringified`() {
        val p = protocol.parsePayload(
            "application/json",
            """{"type":"m3u","name":"P","url":"http://x/p.m3u","userAgent":null,"epgUrl":null}""",
            SourceType.M3U,
        )!!
        assertEquals("", p.userAgent)
        assertEquals("", p.epgUrl)
        assertEquals("P", p.name)
    }

    @Test
    fun `malformed json body does not throw and yields null`() {
        // org.json is stubbed in unit tests; parsePayload must swallow it, not crash the accept thread.
        assertNull(protocol.parsePayload("application/json", "{ this is not json", SourceType.XTREAM))
    }
}
