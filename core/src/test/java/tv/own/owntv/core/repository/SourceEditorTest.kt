package tv.own.owntv.core.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * When an edited playlist is fetched again. The case that matters most: a playlist that synced with a
 * wrong or missing password is already stamped synced, so only a changed account detail can make the
 * corrected password take effect.
 */
class SourceEditorTest {

    private val synced = SourceEntity(
        id = 7, name = "My IPTV", type = SourceType.XTREAM, url = "http://panel.test",
        username = "user", password = null, lastSyncAt = 1_000L,
    )

    @Test
    fun `a typed password on a synced playlist fetches it again`() {
        assertTrue(SourceEditor.needsResync(synced, synced.copy(password = "secret")))
    }

    @Test
    fun `server, username, MAC and portal identity changes fetch it again`() {
        assertTrue(SourceEditor.needsResync(synced, synced.copy(url = "http://other.test")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(username = "other")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(mac = "00:1A:79:00:00:01")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(stalkerSerialNumber = "SN1")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(stalkerDeviceId = "D1")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(stalkerDeviceId2 = "D2")))
        assertTrue(SourceEditor.needsResync(synced, synced.copy(stalkerSignature = "S")))
    }

    @Test
    fun `a rename or a section switched off does not`() {
        assertFalse(SourceEditor.needsResync(synced, synced.copy(name = "Renamed")))
        assertFalse(SourceEditor.needsResync(synced, synced.copy(syncMovies = false)))
    }

    @Test
    fun `the scope rules are unchanged`() {
        val moviesOff = synced.copy(syncMovies = false)
        assertTrue(SourceEditor.needsResync(moviesOff, synced))
        assertTrue(SourceEditor.scopeChanged(synced, moviesOff))
        assertFalse(SourceEditor.scopeChanged(synced, synced.copy(password = "secret")))
        assertTrue(SourceEditor.needsResync(synced.copy(lastSyncAt = null), synced.copy(lastSyncAt = null, name = "x")))
    }
}
