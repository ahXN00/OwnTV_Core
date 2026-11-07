package tv.own.owntv.core.sync.local

import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.backup.BackupManager.Section
import tv.own.owntv.core.sync.local.LocalAutoSyncAuth.Verdict
import tv.own.owntv.core.sync.local.LocalAutoSyncServer.Route

class LocalAutoSyncAuthTest {
    private var now = 1_000_000_000L
    private val secret = "a".repeat(64)
    private val body = "{\"x\":1}".toByteArray()

    private fun signed(nonce: String = "n1", time: Long = now, payload: ByteArray = body) =
        LocalAutoSyncAuth.sign(secret, "POST", "/sync/auto/import", time, nonce, payload)

    @Test
    fun `a request signed with the pairing secret is accepted`() {
        val verifier = LocalAutoSyncAuth.Verifier { now }
        assertEquals(Verdict.OK, verifier.verify(secret, "POST", "/sync/auto/import", now, "n1", body, signed()))
    }

    @Test
    fun `a tampered body, path or secret fails the signature`() {
        val verifier = LocalAutoSyncAuth.Verifier { now }
        assertEquals(Verdict.BAD_SIGNATURE, verifier.verify(secret, "POST", "/sync/auto/import", now, "n1", "{\"x\":2}".toByteArray(), signed()))
        assertEquals(Verdict.BAD_SIGNATURE, verifier.verify(secret, "POST", "/sync/auto/export", now, "n1", body, signed()))
        assertEquals(Verdict.BAD_SIGNATURE, verifier.verify("b".repeat(64), "POST", "/sync/auto/import", now, "n1", body, signed()))
    }

    @Test
    fun `a time more than five minutes off is refused`() {
        val verifier = LocalAutoSyncAuth.Verifier { now }
        val old = now - LocalAutoSyncAuth.WINDOW_MS - 1
        assertEquals(Verdict.STALE, verifier.verify(secret, "POST", "/sync/auto/import", old, "n1", body, signed(time = old)))
        val near = now - LocalAutoSyncAuth.WINDOW_MS + 1_000
        assertEquals(Verdict.OK, verifier.verify(secret, "POST", "/sync/auto/import", near, "n2", body, signed(nonce = "n2", time = near)))
    }

    @Test
    fun `the same nonce twice is a replay`() {
        val verifier = LocalAutoSyncAuth.Verifier { now }
        assertEquals(Verdict.OK, verifier.verify(secret, "POST", "/sync/auto/import", now, "n1", body, signed()))
        assertEquals(Verdict.REPLAY, verifier.verify(secret, "POST", "/sync/auto/import", now, "n1", body, signed()))
    }

    @Test
    fun `an unknown device is refused before anything is checked`() {
        val verifier = LocalAutoSyncAuth.Verifier { now }
        assertEquals(Verdict.UNKNOWN_DEVICE, verifier.verify(null, "POST", "/sync/auto/import", now, "n1", body, signed()))
    }

    @Test
    fun `nonces are random`() {
        assertNotEquals(LocalAutoSyncAuth.newNonce(), LocalAutoSyncAuth.newNonce())
    }

    @Test
    fun `failures slow an address down, up to ten seconds, and a right request clears it`() {
        val slowdown = LocalAutoSyncAuth.Slowdown()
        assertEquals(0L, slowdown.delayFor("10.0.0.2"))
        repeat(3) { slowdown.failed("10.0.0.2") }
        assertEquals(3_000L, slowdown.delayFor("10.0.0.2"))
        assertEquals(0L, slowdown.delayFor("10.0.0.3"))
        repeat(20) { slowdown.failed("10.0.0.2") }
        assertEquals(10_000L, slowdown.delayFor("10.0.0.2"))
        slowdown.succeeded("10.0.0.2")
        assertEquals(0L, slowdown.delayFor("10.0.0.2"))
    }
}

class AutoSyncAgreementTest {
    private val hour = 3600_000L

    @Test
    fun `a new agreement is due at once`() {
        assertTrue(AutoSyncAgreement("d").isDue(1L))
    }

    @Test
    fun `the timing is the least gap after a success`() {
        val synced = AutoSyncAgreement("d", timing = LocalAutoSyncTiming.HOURS_6).afterAttempt(at = 10 * hour, success = true)
        assertFalse(synced.isDue(15 * hour))
        assertTrue(synced.isDue(16 * hour))
        val immediately = synced.copy(timing = LocalAutoSyncTiming.IMMEDIATELY)
        assertTrue(immediately.isDue(10 * hour))
    }

    @Test
    fun `a failure does not move the last success and backs off`() {
        val synced = AutoSyncAgreement("d", timing = LocalAutoSyncTiming.IMMEDIATELY).afterAttempt(at = hour, success = true)
        val failed = synced.afterAttempt(at = 2 * hour, success = false)
        assertEquals(hour, failed.lastSuccessAt)
        assertEquals(1, failed.failures)
        assertFalse(failed.isDue(2 * hour + 14 * 60_000L))
        assertTrue(failed.isDue(2 * hour + 15 * 60_000L))
        val twice = failed.afterAttempt(at = 3 * hour, success = false)
        assertFalse(twice.isDue(3 * hour + 29 * 60_000L))
        assertTrue(twice.isDue(3 * hour + 30 * 60_000L))
    }

    @Test
    fun `the backoff is capped by the timing and by a day`() {
        assertEquals(6 * hour, AutoSyncAgreement.backoffMs(failures = 10, gapMs = 6 * hour))
        assertEquals(24 * hour, AutoSyncAgreement.backoffMs(failures = 30, gapMs = 0))
        assertEquals(24 * hour, AutoSyncAgreement.backoffMs(failures = 30, gapMs = 7 * 24 * hour))
        assertEquals(15 * 60_000L, AutoSyncAgreement.backoffMs(failures = 1, gapMs = 0))
    }

    @Test
    fun `a success after failures clears them`() {
        val failed = AutoSyncAgreement("d").afterAttempt(1, false).afterAttempt(2, false)
        val ok = failed.afterAttempt(3, true)
        assertEquals(0, ok.failures)
        assertEquals(3L, ok.lastSuccessAt)
    }

    @Test
    fun `playlists and settings are off by default`() {
        assertFalse(Section.SOURCES in AutoSyncAgreement.DEFAULT_SECTIONS)
        assertFalse(Section.SETTINGS in AutoSyncAgreement.DEFAULT_SECTIONS)
        assertTrue(Section.FAVORITES in AutoSyncAgreement.DEFAULT_SECTIONS)
    }

    @Test
    fun `each side lets in only its own sections, and only what the file carries`() {
        val mine = setOf(Section.FAVORITES, Section.HISTORY)
        val file = setOf(Section.FAVORITES, Section.SOURCES, Section.SETTINGS)
        assertEquals(setOf(Section.FAVORITES), AutoSyncAgreement.allowedIn(mine, file))
        assertEquals(emptySet<Section>(), AutoSyncAgreement.allowedIn(emptySet(), file))
    }

    @Test
    fun `an agreement survives its own encoding and an unknown timing reads as the default`() {
        val agreement = AutoSyncAgreement("d", setOf(Section.HISTORY), setOf(3L), LocalAutoSyncTiming.DAYS_7, setOf(Section.FAVORITES), 5, 4, 1)
        assertEquals(agreement, LocalAutoSyncStore.decode(LocalAutoSyncStore.encode(agreement)))
        val odd = LocalAutoSyncStore.encode(agreement).replace("DAYS_7", "FORTNIGHTLY")
        assertEquals(LocalAutoSyncTiming.DEFAULT, LocalAutoSyncStore.decode(odd)!!.timing)
    }
}

class LocalAutoSyncServerRulesTest {

    @Test
    fun `only the four automatic-sync routes are served, never the PIN or pairing ones`() {
        assertEquals(Route.HELLO, LocalAutoSyncServer.routeOf("GET", "/sync/auto/hello"))
        assertEquals(Route.EXPORT, LocalAutoSyncServer.routeOf("GET", "/sync/auto/export"))
        assertEquals(Route.IMPORT, LocalAutoSyncServer.routeOf("POST", "/sync/auto/import"))
        assertEquals(Route.NOTICE, LocalAutoSyncServer.routeOf("POST", "/sync/auto/notice"))
        listOf("GET" to "/", "POST" to "/", "POST" to "/sync/pair", "GET" to "/sync/hello", "GET" to "/backup.own",
            "POST" to "/backup", "POST" to "/sync/auto-sync", "POST" to "/sync/auto/export").forEach { (m, p) ->
            assertNull("$m $p", LocalAutoSyncServer.routeOf(m, p))
        }
    }

    @Test
    fun `refusals`() {
        assertEquals(AutoSyncRefusal.UNPAIRED, LocalAutoSyncServer.refusalFor(Verdict.UNKNOWN_DEVICE, true, Route.HELLO))
        assertEquals(AutoSyncRefusal.UNPAIRED, LocalAutoSyncServer.refusalFor(Verdict.BAD_SIGNATURE, true, Route.EXPORT))
        assertEquals(AutoSyncRefusal.BAD_REQUEST, LocalAutoSyncServer.refusalFor(Verdict.STALE, true, Route.EXPORT))
        assertEquals(AutoSyncRefusal.BAD_REQUEST, LocalAutoSyncServer.refusalFor(Verdict.REPLAY, true, Route.IMPORT))
        assertEquals(AutoSyncRefusal.NO_AGREEMENT, LocalAutoSyncServer.refusalFor(Verdict.OK, false, Route.EXPORT))
        assertEquals(AutoSyncRefusal.NO_AGREEMENT, LocalAutoSyncServer.refusalFor(Verdict.OK, false, Route.HELLO))
        assertNull(LocalAutoSyncServer.refusalFor(Verdict.OK, false, Route.NOTICE))
        assertNull(LocalAutoSyncServer.refusalFor(Verdict.OK, true, Route.IMPORT))
    }

    @Test
    fun `only peers on the local network`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "169.254.1.1", "127.0.0.1", "fe80::1", "fd12::1").forEach {
            assertTrue(it, LocalAutoSyncServer.isLocal(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "100.64.0.1", "2001:4860::8888").forEach {
            assertFalse(it, LocalAutoSyncServer.isLocal(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `the listener runs with an agreement on the local network, always or only in Sync mode`() {
        assertFalse(autoSyncShouldListen(hasAgreement = false, onLan = true, listenAlways = true, appVisible = true, syncModeOn = true))
        assertFalse(autoSyncShouldListen(hasAgreement = true, onLan = false, listenAlways = true, appVisible = true, syncModeOn = true))
        assertTrue(autoSyncShouldListen(hasAgreement = true, onLan = true, listenAlways = true, appVisible = true, syncModeOn = false))
        assertFalse(autoSyncShouldListen(hasAgreement = true, onLan = true, listenAlways = true, appVisible = false, syncModeOn = false))
        assertFalse(autoSyncShouldListen(hasAgreement = true, onLan = true, listenAlways = false, appVisible = true, syncModeOn = false))
        assertTrue(autoSyncShouldListen(hasAgreement = true, onLan = true, listenAlways = false, appVisible = true, syncModeOn = true))
    }

    @Test
    fun `what an answer means for this side`() {
        assertEquals(AutoSyncOutcome.SYNCED, autoSyncOutcomeOf(null))
        assertEquals(AutoSyncOutcome.SKIPPED, autoSyncOutcomeOf(AutoSyncHttpException(409, "busy")))
        assertEquals(AutoSyncOutcome.TURNED_OFF, autoSyncOutcomeOf(AutoSyncHttpException(403, "no-agreement")))
        assertEquals(AutoSyncOutcome.FAILED, autoSyncOutcomeOf(AutoSyncHttpException(403, "not-lan")))
        assertEquals(AutoSyncOutcome.UNPAIRED, autoSyncOutcomeOf(AutoSyncHttpException(401, "unpaired")))
        assertEquals(AutoSyncOutcome.FAILED, autoSyncOutcomeOf(AutoSyncHttpException(500, "failed")))
        assertEquals(AutoSyncOutcome.UNREACHABLE, autoSyncOutcomeOf(IOException("timeout")))
        assertEquals(AutoSyncOutcome.FAILED, autoSyncOutcomeOf(IllegalStateException()))
    }
}
