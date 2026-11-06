package tv.own.owntv.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePositionsTest {

    private val shared = listOf("last_live_category", "last_movies_category", "last_series_category")
    private val portable: (String) -> Boolean = { it == "FAV" || it == "ALL" || it == "CATCHUP" || it.startsWith("CUSTOM:") }

    @Test
    fun `each profile reads its own value`() {
        val values = mapOf("last_live_category_1" to "FAV", "last_live_category_2" to "FOLDER:9", "last_live_category" to "ALL")
        assertEquals("FAV", ProfilePositions.read(values, "last_live_category", 1))
        assertEquals("FOLDER:9", ProfilePositions.read(values, "last_live_category", 2))
    }

    @Test
    fun `a profile with no value of its own reads the one the device shared before`() {
        val values = mapOf("last_live_category" to "FOLDER:4", "last_live_channel" to 55L)
        assertEquals("FOLDER:4", ProfilePositions.read(values, "last_live_category", 3))
        assertEquals(55L, ProfilePositions.read(values, "last_live_channel", 3))
        assertNull(ProfilePositions.read(emptyMap(), "last_live_category", 3))
    }

    @Test
    fun `backup carries each profile's portable values and restores them to existing profiles`() {
        val values = mapOf(
            "last_live_category_1" to "FAV",
            "last_movies_category_1" to "CUSTOM:custom:x",
            "last_live_category_2" to "CATCHUP",
            "last_series_category_2" to "FOLDER:12", // a Room id: not portable
            "last_live_category" to "ALL", // the device-wide value is not exported here
            "last_live_channel_1" to 99L,
        )
        val exported = ProfilePositions.export(values, shared, portable)
        assertEquals(setOf("1", "2"), exported.keys().asSequence().toSet())
        assertEquals("FAV", exported.getJSONObject("1").getString("last_live_category"))
        assertEquals("CUSTOM:custom:x", exported.getJSONObject("1").getString("last_movies_category"))
        assertFalse(exported.getJSONObject("2").has("last_series_category"))

        // Through text, and onto a device where file profile 2 no longer exists.
        val restored = ProfilePositions.import(JSONObject(exported.toString()), shared, setOf(1L), portable)
        assertEquals(mapOf("last_live_category_1" to "FAV", "last_movies_category_1" to "CUSTOM:custom:x"), restored)
    }

    @Test
    fun `an import drops values that are not portable and blocks that are not objects`() {
        val o = JSONObject("""{"1":{"last_live_category":"FOLDER:3","last_series_category":"FAV"},"2":"ALL","x":{}}""")
        assertEquals(mapOf("last_series_category_1" to "FAV"), ProfilePositions.import(o, shared, setOf(1L, 2L), portable))
    }
}
