package tv.own.owntv.core.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StalkerClientTest {

    // ---- MAC canonicalization ----

    @Test
    fun mac_plainHexBecomesColonForm() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("001a79aabbcc"))
    }

    @Test
    fun mac_acceptsSeparatorsAndCase() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("00:1a:79:aa:bb:cc"))
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac("00-1A-79-AA-BB-CC"))
        assertEquals("00:1A:79:AA:BB:CC", StalkerClient.canonicalizeMac(" 00.1a.79.aa.bb.cc "))
    }

    /** Some panels issue "virtual" MACs with letters past F — those must be accepted, not rejected. */
    @Test
    fun mac_acceptsNonHexLetters() {
        assertEquals("00:1A:79:AA:BB:PQ", StalkerClient.canonicalizeMac("00:1a:79:aa:bb:pq"))
        assertEquals("ZZ:ZZ:ZZ:ZZ:ZZ:ZZ", StalkerClient.canonicalizeMac("zzzzzzzzzzzz"))
    }

    @Test
    fun mac_rejectsWrongLengthAndNonAlphanumeric()  {
        assertNull(StalkerClient.canonicalizeMac("001a79aabbc"))
        assertNull(StalkerClient.canonicalizeMac("001a79aabbccdd"))
        assertNull(StalkerClient.canonicalizeMac("001&79aabbcc"))
        assertNull(StalkerClient.canonicalizeMac(""))
    }

    // ---- portal URL normalization ----

    @Test
    fun portalRoot_stripsCPathAndSlashes() {
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/c/"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/c"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080/"))
        assertEquals("http://host:8080", StalkerClient.portalRoot("http://host:8080"))
    }

    @Test
    fun apiCandidates_probeOrder() {
        assertEquals(
            listOf(
                "http://host:8080/portal.php",
                "http://host:8080/stalker_portal/server/load.php",
                "http://host:8080/server/load.php",
            ),
            StalkerClient.apiCandidates("http://host:8080/c/"),
        )
    }

    @Test
    fun apiCandidates_directPhpEndpointComesFirst() {
        val candidates = StalkerClient.apiCandidates("http://host:8080/portal.php")
        assertEquals("http://host:8080/portal.php", candidates.first())
        assertFalse(candidates.drop(1).contains("http://host:8080/portal.php"))
    }

    @Test
    fun isValidPortalUrl_basicCheck() {
        assertTrue(StalkerClient.isValidPortalUrl("http://host:8080/c/"))
        assertTrue(StalkerClient.isValidPortalUrl("https://portal.example.com"))
        assertFalse(StalkerClient.isValidPortalUrl("host:8080/c/"))
        assertFalse(StalkerClient.isValidPortalUrl(""))
    }

    @Test
    fun profileUrl_macOnlyKeepsLegacySecondStepOff() {
        assertEquals(
            "http://host/portal.php?type=stb&action=get_profile&hd=1&auth_second_step=0&JsHttpRequest=1-xml",
            StalkerClient.profileUrl("http://host/portal.php", StalkerDeviceIdentity()),
        )
    }

    @Test
    fun profileUrl_advancedIdentityIsEncodedAndEnablesSecondStep() {
        val url = StalkerClient.profileUrl(
            "http://host/portal.php",
            StalkerDeviceIdentity(
                serialNumber = "SN 12/34",
                deviceId = "device+one",
                deviceId2 = "device two",
                signature = "sig=&value",
            ),
        )
        assertTrue(url.contains("auth_second_step=1"))
        assertTrue(url.contains("sn=SN+12%2F34"))
        assertTrue(url.contains("device_id=device%2Bone"))
        assertTrue(url.contains("device_id2=device+two"))
        assertTrue(url.contains("signature=sig%3D%26value"))
    }

    // ---- create_link cmd prefix stripping ----

    @Test
    fun stripCmdPrefix_removesKnownPrefixes() {
        assertEquals("http://real/play/index.m3u8?token=X", StalkerClient.stripCmdPrefix("ffmpeg http://real/play/index.m3u8?token=X"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("auto http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt2 http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt3 http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("ffrt http://real/1.ts"))
    }

    @Test
    fun stripCmdPrefix_leavesPlainUrls() {
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("http://real/1.ts"))
        assertEquals("http://real/1.ts", StalkerClient.stripCmdPrefix("  http://real/1.ts  "))
    }

    // ---- direct-play URL detection (portals that embed the real URL in the cmd) ----

    @Test
    fun isDirectPlayUrl_realHostWithQueryOrExtension() {
        // The light-ott portal shape: a full play.php URL with stream/token already present.
        assertTrue(StalkerClient.isDirectPlayUrl("http://host:80/play/live.php?mac=00:1A:79:1C:F8:46&stream=1745079&extension=ts&play_token=abc"))
        assertTrue(StalkerClient.isDirectPlayUrl("http://host/live/1.ts"))
    }

    @Test
    fun isDirectPlayUrl_localhostPlaceholderNeedsCreateLink() {
        assertFalse(StalkerClient.isDirectPlayUrl("http://localhost/ch/12345_"))
        assertFalse(StalkerClient.isDirectPlayUrl("http://127.0.0.1/ch/12345_0"))
    }

    @Test
    fun isDirectPlayUrl_nonUrlIsFalse() {
        assertFalse(StalkerClient.isDirectPlayUrl("ffmpeg localhost/ch/1"))
        assertFalse(StalkerClient.isDirectPlayUrl(""))
    }

    // ---- which status means "logged out" and which means "slow down" ----

    @Test
    fun httpFailure_401IsAnAuthFailure() {
        val e = StalkerClient.httpFailure(401, "http://host/portal.php")
        assertTrue(e is StalkerClient.StalkerAuthException)
    }

    /**
     * The regression this phase exists for: portals answer 403 for "too many connections for this
     * MAC". Turning that into an auth failure made every one of them tear down a working session and
     * handshake again, which is the worst possible reply to being asked to slow down.
     */
    @Test
    fun httpFailure_403IsThrottleNotAuth() {
        val e = StalkerClient.httpFailure(403, "http://host/portal.php")
        assertFalse("403 must never re-handshake", e is StalkerClient.StalkerAuthException)
        assertEquals(403, (e as StalkerClient.StalkerHttpException).code)
    }

    @Test
    fun httpFailure_otherStatusesKeepTheirCode() {
        assertEquals(503, (StalkerClient.httpFailure(503, "u") as StalkerClient.StalkerHttpException).code)
        assertEquals(429, (StalkerClient.httpFailure(429, "u") as StalkerClient.StalkerHttpException).code)
    }

    // ---- catch-up: which field says a channel has an archive, and in what unit ----

    /**
     * The bug this pins. `tv_archive` is **Xtream's** field name; Ministra sends `enable_tv_archive`
     * and `archive`. Reading only the Xtream name marked every portal channel as having no archive, so
     * catch-up never appeared on a Stalker portal — 427 of the test portal's 11 545 channels have one.
     */
    @Test
    fun archive_isReadFromTheFieldsMinistraActuallySends() {
        assertTrue(StalkerClient.hasArchive(mapOf("enable_tv_archive" to "1")))
        assertTrue(StalkerClient.hasArchive(mapOf("archive" to "1")))
        // …and a panel that does use the Xtream name is still understood.
        assertTrue(StalkerClient.hasArchive(mapOf("tv_archive" to "1")))
        assertTrue(StalkerClient.hasArchive(mapOf("enable_tv_archive" to "1", "archive" to "1", "tv_archive_duration" to "72")))
    }

    @Test
    fun archive_absentOrZeroMeansNoCatchUp() {
        assertFalse(StalkerClient.hasArchive(emptyMap()))
        assertFalse(StalkerClient.hasArchive(mapOf("enable_tv_archive" to "0", "archive" to "0")))
        assertFalse(StalkerClient.hasArchive(mapOf("archive" to "")))
        // A duration alone is not a claim that the archive is switched on.
        assertFalse(StalkerClient.hasArchive(mapOf("tv_archive_duration" to "72")))
    }

    /**
     * Ministra states the archive length in HOURS. The test portal reports exactly 24, 48, 72 and 168
     * — one, two, three and seven days. Stored raw it would have offered a 72-day archive on a
     * three-day one.
     */
    @Test
    fun archiveDuration_hoursBecomeDays() {
        assertEquals(1, StalkerClient.archiveDays("24"))
        assertEquals(2, StalkerClient.archiveDays("48"))
        assertEquals(3, StalkerClient.archiveDays("72"))
        assertEquals(7, StalkerClient.archiveDays("168"))
    }

    /** A panel reporting a small number plainly means days — nobody sells a seven-hour archive. */
    @Test
    fun archiveDuration_smallValuesAreTakenAsDays() {
        assertEquals(7, StalkerClient.archiveDays("7"))
        assertEquals(3, StalkerClient.archiveDays("3"))
    }

    @Test
    fun archiveDuration_missingOrJunkIsNone() {
        assertEquals(0, StalkerClient.archiveDays(null))
        assertEquals(0, StalkerClient.archiveDays(""))
        assertEquals(0, StalkerClient.archiveDays("0"))
        assertEquals(0, StalkerClient.archiveDays("-5"))
        assertEquals(0, StalkerClient.archiveDays("lots"))
    }
}
