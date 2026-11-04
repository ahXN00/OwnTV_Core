package tv.own.owntv.core.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.ContentOrderEntity

class OldListOrderTest {

    private fun order(ctx: String) = JSONObject().put("kind", "order").put("ctx", ctx).put("pos", 0)

    @Test
    fun `an older file loses its folder orders but keeps Favorites and everything else`() {
        val arr = JSONArray().put(order("1:live:7")).put(order(ContentOrderEntity.FAV_CONTEXT)).put(JSONObject().put("kind", "fav"))
        val kept = arr.withoutOldListOrders(25)
        assertEquals(2, kept.length())
        assertEquals(ContentOrderEntity.FAV_CONTEXT, kept.getJSONObject(0).getString("ctx"))
        assertEquals("fav", kept.getJSONObject(1).getString("kind"))
    }

    @Test
    fun `a v26 file keeps every order`() {
        val arr = JSONArray().put(order("1:live:7")).put(order("custom:x"))
        assertEquals(2, arr.withoutOldListOrders(26).length())
    }
}
