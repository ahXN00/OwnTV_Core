package tv.own.owntv.core.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity

/**
 * Backup format v17: the fixes for the three silent-data-loss classes an audit found in the
 * export/restore path. Every case here failed before that change.
 *
 *  1. **Per-item player keys were never remapped.** [tv.own.owntv.core.player.enginePinKey] is
 *     "<sourceId>:<TYPE>:<remoteId>", but the compatibility-mode pins and the per-item zoom/volume
 *     rows were imported verbatim — so after a merge restore they named a source id that either did
 *     not exist here or, worse, belonged to somebody else's playlist.
 *  2. **Source-keyed blocks were exported unscoped.** An id whose source is not in the file cannot
 *     be remapped on restore, and an unmapped numeric id happily matches whatever unrelated source
 *     holds that number on the target device.
 *  3. **New [SourceEntity] fields were quietly dropped.** `preferHls` and `livePrerollSecs` shipped
 *     without ever being added to the backup, and nothing failed. [sourceEntityFieldParity] is the
 *     tripwire: adding a field to the entity now breaks this test until the author decides.
 */
class BackupV17ScopingTest {

    private val idMap = mapOf(7L to 91L, 8L to 92L)

    // --- 1. per-item player keys ---

    @Test
    fun `engine pin key follows its source to the device id`() {
        assertEquals("91:LIVE:bbc-one", remapEnginePinKey("7:LIVE:bbc-one", idMap))
        assertEquals("92:MOVIE:12345", remapEnginePinKey("8:MOVIE:12345", idMap))
    }

    @Test
    fun `engine pin key for a source that took no part in the restore is left alone`() {
        // 5 is not in the map: the file's source was not merged, so there is no device id to point
        // at. Rewriting it to anything would be a guess; passing it through leaves a pin that simply
        // never matches, which is the safe failure.
        assertEquals("5:LIVE:abc", remapEnginePinKey("5:LIVE:abc", idMap))
        assertEquals("7:LIVE:abc", remapEnginePinKey("7:LIVE:abc", emptyMap()))
    }

    @Test
    fun `legacy stream-URL keys are not mistaken for ids`() {
        // "http" is not a Long, so the key must survive untouched — these are the pins made before
        // the stable key existed, and they still work by URL.
        val url = "http://panel.example:8080/live/user/pass/123.ts"
        assertEquals(url, remapEnginePinKey(url, idMap))
    }

    @Test
    fun `typed content keys remap the id in the middle`() {
        // Subtitle rows and TMDB overrides lead with the media type, so the id is the SECOND segment.
        assertEquals("movie:91:12345", remapTypedContentKey("movie:7:12345", idMap))
        assertEquals("episode:91:show1:S1E2", remapTypedContentKey("episode:7:show1:S1E2", idMap))
        assertEquals("movie:5:12345", remapTypedContentKey("movie:5:12345", idMap))
        assertEquals("nonsense", remapTypedContentKey("nonsense", idMap))
    }

    // --- 2. export scoping ---

    @Test
    fun `player keys are scoped to the sources riding in the backup`() {
        val keys = listOf("7:LIVE:a", "8:MOVIE:b", "99:LIVE:c")
        assertEquals(listOf("7:LIVE:a", "8:MOVIE:b"), filterEnginePinKeys(keys, setOf(7L, 8L), false))
    }

    @Test
    fun `a URL-shaped key needs a passphrase because the URL carries the account credentials`() {
        val url = "http://panel.example:8080/live/user/secret/123.ts"
        val keys = listOf("7:LIVE:a", url)
        assertEquals(listOf("7:LIVE:a"), filterEnginePinKeys(keys, setOf(7L), allowUrlKeys = false))
        assertEquals(keys, filterEnginePinKeys(keys, setOf(7L), allowUrlKeys = true))
    }

    @Test
    fun `source-id-keyed maps drop entries whose source is absent`() {
        val map = JSONObject().put("7", "HOURS_6").put("99", "HOURS_24")
        val filtered = filterBySourceId(map, setOf(7L))
        assertEquals("HOURS_6", filtered.optString("7"))
        assertFalse(filtered.has("99"))
    }

    @Test
    fun `tmdb overrides are scoped by the source id in the middle of the key`() {
        val raw = JSONObject()
            .put("movie:7:1|Old Name", "New Name")
            .put("movie:99:2|Other", "Nope")
            .toString()
        val out = JSONObject(filterTmdbOverridesBySourceId(raw, setOf(7L)))
        assertTrue(out.has("movie:7:1|Old Name"))
        assertFalse(out.has("movie:99:2|Other"))
    }

    @Test
    fun `scoping a map with nothing in scope yields an empty payload, not the original`() {
        val raw = JSONObject().put("movie:99:2|Other", "Nope").toString()
        assertEquals("", filterTmdbOverridesBySourceId(raw, setOf(7L)))
    }

    // --- PIN hashes: sealed with a passphrase, never written in the clear ---

    @Test
    fun `sealed map values round-trip and unreadable ones are dropped rather than restored blank`() {
        // Stand-ins for the real AES-GCM pair (android.util.Base64 is not available in a JVM test).
        val seal: (String) -> JSONObject = { JSONObject().put("ct", it.reversed()) }
        val unseal: (Any?) -> String? = { v -> (v as? JSONObject)?.optString("ct")?.reversed() }

        val pins = JSONObject().put("1", "salt:hash").put("2", "other:hash")
        val sealed = sealValues(pins, seal)
        assertFalse("the hash must not survive in readable form", sealed.optString("1") == "salt:hash")

        assertEquals("salt:hash", unsealValues(sealed, unseal).optString("1"))
        assertEquals("other:hash", unsealValues(sealed, unseal).optString("2"))

        // No key (no passphrase given for an encrypted file) → the entry is skipped entirely, so a
        // restore leaves the device's own PIN in place instead of clearing the lock.
        assertEquals(0, unsealValues(sealed) { null }.length())
    }

    // --- 3. schema parity tripwire ---

    @Test
    fun sourceEntityFieldParity() {
        // Every user-controlled playlist setting must be in the backup. When this fails you have
        // added a field to SourceEntity: decide whether it belongs in sourceJson()/sourceFrom() and
        // in the existing-source merge branch of import(), then add it to the right list below.
        val backedUp = setOf(
            "id", "name", "type", "url", "username", "password", "mac",
            "stalkerSerialNumber", "stalkerDeviceId", "stalkerDeviceId2", "stalkerSignature",
            "userAgent", "epgUrl", "syncLive", "syncMovies", "syncSeries", "importPortalEpg",
            "preferHls", "livePrerollSecs", "hlsSupported", "createdAt", "lastSyncAt",
            "liveEnginePreference", "liveLatencyMode", "liveLatencyCustomSecs",
        )
        // Intentionally re-derived rather than carried. `maxConnections` is the provider's own
        // answer, written only by the Xtream sync (XtreamClient → SourceDao.setMaxConnections), so
        // the first sync after a restore fills it in — and a stale value copied from another device
        // would mis-describe the account until then.
        val deliberatelyExcluded = setOf("maxConnections")
        // Not a field of the entity at all: the Compose compiler adds `$stable` as a static, and
        // Kotlin does not flag it synthetic.
        val compilerGenerated = setOf("\$stable")

        // Java reflection, not kotlin-reflect: the latter is not on the unit-test classpath, and a
        // data class's backing fields carry the property names anyway.
        val actual = SourceEntity::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet() - compilerGenerated

        assertEquals(
            "SourceEntity fields not accounted for in the backup",
            emptySet<String>(),
            actual - backedUp - deliberatelyExcluded,
        )
        assertEquals(
            "backup lists name fields SourceEntity no longer has",
            emptySet<String>(),
            backedUp - actual,
        )
    }
}
