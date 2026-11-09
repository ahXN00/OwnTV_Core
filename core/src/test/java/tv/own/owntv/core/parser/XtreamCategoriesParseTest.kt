package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.network.HttpClient

/**
 * Category lists with non-string values. A boolean anywhere in a category object used to fail the
 * whole import with "Expected a string but was BOOLEAN". Runs on the real reader.
 */
class XtreamCategoriesParseTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun parse(json: String) = runBlocking { client.readCategories(json.byteInputStream()) }

    @Test
    fun `booleans numbers nulls and nested values are tolerated`() {
        val categories = parse(
            """[
                {"category_id":"1","category_name":"News","parent_id":0,"is_adult":false},
                {"category_id":2,"category_name":"Sports","is_adult":true,"tags":["a"],"meta":{"x":1}},
                {"category_id":"3","category_name":null}
            ]""",
        )
        assertEquals(listOf("1", "2", "3"), categories.map { it.id })
        assertEquals(listOf("News", "Sports", "3"), categories.map { it.name })
    }
}
